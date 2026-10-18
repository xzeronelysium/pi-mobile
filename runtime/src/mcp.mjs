// MCP over HTTP/SSE, on the durable engine.
//
// The pre-durable app loaded MCP servers through the bundled Node, so `stdio` servers and the
// `npx`-style packages that launched them are gone for good. What remains reachable is the network
// transport: `@earendil-works/pi-mcp`'s `StreamableHttpTransport` is plain `fetch` + a `ReadableStream`
// SSE reader, and its client core has no Node dependency at all.
//
// Two accommodations make it run as bundled JavaScript in the WebView, and both are enforced by
// `build.mjs` rather than promised here:
//
//  1. The package's `exports` map has no subpath for its transports, and its root re-exports
//     `StdioTransport`, which imports `cross-spawn` (CommonJS) — that drags `path`/`fs`/`child_process`
//     into a `platform: 'browser'` bundle. The client and the HTTP transport are therefore resolved
//     to their own dist files by a build-time plugin.
//  2. Its SSE reader measures events with the Node `Buffer` global. `src/shims/buffer.mjs` is injected
//     for that identifier; without it every SSE response fails with `Buffer is not defined`.
//
// Tool definitions are registered as they arrive: an MCP server publishes JSON Schema and Pi Durable
// validates plain JSON Schema as well as TypeBox (`pi-ai`'s `validateToolArguments` has a dedicated
// non-TypeBox branch), so `inputSchema` is registered verbatim rather than converted.

import { McpClient, StreamableHttpTransport, toLlmContent } from '@earendil-works/pi-mcp';

/** Extension names are namespaced so a server's tools can be installed and dropped as one unit. */
export const MCP_EXTENSION_PREFIX = 'mcp:';

/** Tool names are namespaced because two servers may publish the same tool name. */
export const MCP_TOOL_PREFIX = 'mcp__';

/** The bridge op that reads the persisted server list. */
export const MCP_CONFIG_READ = 'mcp.servers';
/** The bridge op that writes it. */
export const MCP_CONFIG_WRITE = 'mcp.save';

const MAX_SERVERS = 24;
const MAX_TOOLS = 200;
const ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,31}$/;

/**
 * Validate and normalise a persisted or newly typed server list.
 *
 * A URL the WebView could never reach is refused here rather than failing silently at connect time:
 * the shipped page's CSP is `connect-src https:`, so a plain-`http` server is carried through with
 * that fact recorded rather than dropped.
 *
 * @returns `{ servers, errors }`; `errors` names every entry that was refused and why.
 */
export function normalizeServers(value) {
  const servers = [];
  const errors = [];
  const seen = new Set();
  const list = Array.isArray(value) ? value : [];
  for (const entry of list.slice(0, MAX_SERVERS)) {
    const raw = typeof entry === 'string' ? { url: entry } : entry;
    if (!raw || typeof raw !== 'object') {
      errors.push('Server entry is not an object');
      continue;
    }
    let url;
    try {
      url = new URL(String(raw.url ?? ''));
    } catch {
      errors.push(`${String(raw.url ?? '')}: not a URL`);
      continue;
    }
    if (url.protocol !== 'https:' && url.protocol !== 'http:') {
      errors.push(`${url.href}: only http(s) MCP servers are supported`);
      continue;
    }
    const name = String(raw.name ?? '').trim() || url.hostname;
    let id = String(raw.id ?? '').trim().toLowerCase();
    if (!ID_PATTERN.test(id)) id = serverId(name);
    if (id === '') {
      errors.push(`${url.href}: no usable server name`);
      continue;
    }
    while (seen.has(id)) id = `${id}-1`.slice(0, 32);
    seen.add(id);
    servers.push({
      id,
      name: name.slice(0, 60),
      url: url.href,
      // Recorded, not enforced: an http server is reachable only if the page's CSP allows it.
      plaintext: url.protocol === 'http:',
    });
  }
  return { servers, errors };
}

/** A stable id derived from a display name. */
export function serverId(name) {
  return String(name ?? '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+/, '')
    .replace(/-+$/, '')
    .slice(0, 32);
}

/** The model-facing tool name for one server tool: `mcp__<server>__<tool>`. */
export function mcpToolName(owner, tool) {
  return `${MCP_TOOL_PREFIX}${owner}__${tool}`;
}

/**
 * One MCP tool as a durable `ToolRegistration`.
 *
 * `parameters` is the server's own `inputSchema`, verbatim. A server that publishes no schema gets an
 * open object schema rather than a guessed one. Execution is marked unsafe to replay: the call is a
 * network side effect the engine cannot undo.
 */
export function mcpTool(owner, tool, callTool, report = () => {}) {
  const schema = tool?.inputSchema && typeof tool.inputSchema === 'object'
    ? tool.inputSchema
    : { type: 'object', properties: {}, additionalProperties: true };
  return {
    name: mcpToolName(owner, tool.name),
    description: typeof tool.description === 'string' && tool.description !== ''
      ? tool.description
      : `MCP tool ${tool.name}`,
    parameters: schema,
    replay: 'unsafe',
    async execute(args) {
      const result = await callTool(tool.name, args);
      const content = toLlmContent(result);
      report({ tool: tool.name, blocks: content.length });
      return {
        content,
        details: {
          server: owner,
          tool: tool.name,
          ...(result.structuredContent === undefined ? {} : { structured: true }),
          ...(result.isError === true ? { error: true } : {}),
        },
        ...(result.isError === true ? { isError: true } : {}),
      };
    },
  };
}

/** One server's tools as one installable extension. */
export function mcpExtension(server, tools, callTool, report) {
  return {
    name: MCP_EXTENSION_PREFIX + server.id,
    tools: tools.slice(0, MAX_TOOLS).map(tool => mcpTool(server.id, tool, callTool, report)),
  };
}

/** Connect one server over Streamable HTTP and read its tool list. */
export async function connectServer(server, options = {}) {
  const client = new McpClient({
    name: options.clientName ?? 'pi-mobile',
    version: options.clientVersion ?? '1.1.0',
    ...(options.instructions === false ? {} : {}),
  });
  const transport = new StreamableHttpTransport({ url: server.url, ...(options.fetch ? { fetch: options.fetch } : {}) });
  const errors = [];
  client.onError(error => errors.push(error instanceof Error ? error.message : String(error)));
  const initialize = await client.connect(transport);
  const tools = await client.listTools();
  const resources = client.serverCapabilities?.resources === undefined
    ? []
    : await client.listResources().catch(() => []);
  return { client, transport, tools, resources, errors, initialize };
}

/**
 * The server surface the app drives: persisted configuration, live clients, and the tools each
 * connected server contributes to the agent's registry.
 *
 * Configuration lives app-side (the host's own store, through the bridge), never in the conversation
 * database and never as a workspace file the agent would see.
 */
export function createMcpManager({ call, emit, registry, report = () => {}, connect = connectServer }) {
  let servers = [];
  let loaded = false;
  const errors = [];
  /** id -> `{ client, tools, resources, info }` for a connected server. */
  const live = new Map();
  /** id -> the last failure, cleared by a successful connect. */
  const failures = new Map();

  const rowOf = server => {
    const connection = live.get(server.id);
    const failure = failures.get(server.id);
    return {
      id: server.id,
      name: server.name,
      url: server.url,
      plaintext: server.plaintext === true,
      state: connection ? 'connected' : failure ? 'failed' : 'disconnected',
      ...(failure ? { error: failure } : {}),
      ...(connection?.info?.serverInfo ? { serverName: connection.info.serverInfo.name, serverVersion: connection.info.serverInfo.version } : {}),
      ...(connection?.info?.instructions ? { instructions: connection.info.instructions } : {}),
      tools: connection
        ? connection.tools.map(tool => ({
          name: mcpToolName(server.id, tool.name),
          tool: tool.name,
          description: typeof tool.description === 'string' ? tool.description : '',
        }))
        : [],
      resources: connection ? connection.resources.map(resource => ({ uri: resource.uri, name: resource.name })) : [],
    };
  };

  const state = () => ({ servers: servers.map(rowOf), errors: [...errors] });

  const publish = () => {
    const value = state();
    emit({ type: 'mcp', value });
    return value;
  };

  const persist = async () => {
    await call(MCP_CONFIG_WRITE, { servers: servers.map(({ id, name, url }) => ({ id, name, url })) });
  };

  const dropExtension = id => {
    const extension = registry.snapshot().extension(MCP_EXTENSION_PREFIX + id);
    if (extension) registry.uninstall(extension);
  };

  const callToolOn = id => async (name, args) => {
    const connection = live.get(id);
    if (!connection) throw new Error(`MCP server ${id} is not connected`);
    return connection.client.callTool(name, args);
  };

  return {
    /** Read the persisted list once. A store that has never been written is empty, not broken. */
    async load() {
      if (loaded) return state();
      loaded = true;
      try {
        const value = await call(MCP_CONFIG_READ, {});
        const normalized = normalizeServers(value?.servers);
        servers = normalized.servers;
        errors.push(...normalized.errors);
      } catch (error) {
        errors.push(`MCP configuration could not be read: ${error instanceof Error ? error.message : String(error)}`);
      }
      return state();
    },

    state,

    /** Add or replace one server by its id. Nothing is connected here; that is an explicit step. */
    async add(config = {}) {
      const normalized = normalizeServers([{ name: config.name, url: config.url, id: config.id }]);
      if (normalized.servers.length === 0) throw new Error(normalized.errors[0] ?? 'A server name and URL are required');
      const next = normalized.servers[0];
      const existing = servers.find(server => server.id === next.id);
      if (existing) {
        if (existing.url !== next.url) await this.disconnect({ id: next.id });
        servers = servers.map(server => (server.id === next.id ? next : server));
      } else {
        if (servers.length >= MAX_SERVERS) throw new Error(`At most ${MAX_SERVERS} MCP servers can be configured`);
        servers = [...servers, next];
      }
      failures.delete(next.id);
      await persist();
      return publish();
    },

    async remove(config = {}) {
      const id = String(config?.id ?? '');
      const found = servers.find(server => server.id === id);
      if (!found) throw new Error(`Unknown MCP server: ${id}`);
      await this.disconnect({ id });
      servers = servers.filter(server => server.id !== id);
      failures.delete(id);
      await persist();
      return publish();
    },

    /**
     * Connect one server and install the tools it publishes.
     *
     * A failure leaves the server configured, disconnected, and carrying its own reason — it never
     * reports a connected server. Tools are registered only after `initialize` and `tools/list`
     * both succeeded.
     */
    async connect(config = {}) {
      const server = servers.find(entry => entry.id === String(config?.id ?? ''));
      if (!server) throw new Error(`Unknown MCP server: ${config?.id}`);
      if (live.has(server.id)) return publish();
      try {
        const connected = await connect(server, { clientName: 'pi-mobile', clientVersion: '1.1.0' });
        live.set(server.id, {
          client: connected.client,
          transport: connected.transport,
          tools: connected.tools ?? [],
          resources: connected.resources ?? [],
          // `connectServer` reports the `initialize` result; an injected connector may call it `info`.
          info: connected.info ?? connected.initialize,
        });
        failures.delete(server.id);
        registry.install(mcpExtension(server, connected.tools ?? [], callToolOn(server.id), report));
      } catch (error) {
        failures.set(server.id, error instanceof Error ? error.message : String(error));
      }
      return publish();
    },

    async disconnect(config = {}) {
      const id = String(config?.id ?? '');
      const connection = live.get(id);
      live.delete(id);
      dropExtension(id);
      if (connection) await connection.client.close().catch(() => {});
      return publish();
    },

    /** Drop every live connection; the configuration is untouched. */
    async close() {
      for (const [id, connection] of [...live]) {
        live.delete(id);
        dropExtension(id);
        await connection.client.close().catch(() => {});
      }
    },
  };
}
