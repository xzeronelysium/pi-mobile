// MCP over Streamable HTTP: configuration, connection, tool registration and real tool execution.
//
// The mock server answers `tools/list` and `tools/call` with `text/event-stream`, which is the only
// path that touches the Node `Buffer` global in the transport — so this suite fails if the bundle's
// injected `Buffer` stand-in is ever dropped.

import test from 'node:test';
import assert from 'node:assert/strict';
import { fauxAssistantMessage, fauxToolCall } from '@earendil-works/pi-ai/providers/faux';
import { createRegistry } from '@earendil-works/pi-durable';
import {
  MCP_CONFIG_READ,
  MCP_CONFIG_WRITE,
  MCP_EXTENSION_PREFIX,
  createMcpManager,
  mcpTool,
  mcpToolName,
  normalizeServers,
  serverId,
} from '../src/mcp.mjs';
import { closedPort, startMockMcpServer } from './support/mcp-server.mjs';
import { withFauxHarness } from './support/durable.mjs';

const ECHO_TOOL = { name: 'echo', description: 'Echo text back', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } };

/** A store the manager owns: the persisted list plus the events it published. */
function fakeStore(initial = []) {
  const state = { servers: initial };
  const events = [];
  const writes = [];
  return {
    state,
    events,
    writes,
    call: async (op, args) => {
      if (op === MCP_CONFIG_READ) return { servers: state.servers };
      if (op === MCP_CONFIG_WRITE) { writes.push(args.servers); state.servers = args.servers; return null; }
      throw new Error(`Unexpected op ${op}`);
    },
    emit: ({ type, ...fields }) => events.push({ event: type, ...fields }),
  };
}

test('a server list is validated, named and normalised before it is stored', () => {
  assert.equal(serverId('My Server!'), 'my-server');
  const { servers, errors } = normalizeServers([
    { name: 'Local tools', url: 'https://mcp.example.com/rpc' },
    { id: 'dup', name: 'Local tools', url: 'https://mcp.example.com/rpc' },
    { name: 'Bad', url: 'not-a-url' },
    { name: 'Ftp', url: 'ftp://example.com' },
    { url: 'http://127.0.0.1:8931/mcp' },
  ]);
  // The name-less entry is addressed by its host, and an http server is carried with the fact noted.
  assert.deepEqual(servers.map(server => [server.id, server.url, server.plaintext]), [
    ['local-tools', 'https://mcp.example.com/rpc', false],
    ['dup', 'https://mcp.example.com/rpc', false],
    ['127-0-0-1', 'http://127.0.0.1:8931/mcp', true],
  ]);
  assert.equal(errors.length, 2);
  assert.match(errors[0], /not a URL/);
  assert.match(errors[1], /http\(s\)/);
});

test('an MCP tool keeps the server schema verbatim and renders its result for the model', async () => {
  const schema = { $schema: 'https://json-schema.org/draft/2020-12/schema', type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false };
  const calls = [];
  const tool = mcpTool('srv', { name: 'echo', description: 'Echo', inputSchema: schema }, async (name, args) => {
    calls.push([name, args]);
    return { content: [{ type: 'text', text: `echo:${args.text}` }] };
  });
  assert.equal(tool.name, 'mcp__srv__echo');
  assert.equal(tool.replay, 'unsafe');
  // No conversion: the durable validator accepts plain JSON Schema, so `inputSchema` is registered as-is.
  assert.equal(tool.parameters, schema);
  const result = await tool.execute({ text: 'hi' });
  assert.deepEqual(calls, [['echo', { text: 'hi' }]]);
  assert.deepEqual(result.content, [{ type: 'text', text: 'echo:hi' }]);
  assert.deepEqual(result.details, { server: 'srv', tool: 'echo' });
  // A server that publishes no schema gets an open object, never a guessed one.
  const bare = mcpTool('srv', { name: 'bare' }, async () => ({ content: [] }));
  assert.ok(bare.parameters.type === 'object' && bare.parameters.additionalProperties === true);
  assert.equal(bare.description, 'MCP tool bare');
  assert.equal(mcpToolName('a', 'b'), 'mcp__a__b');
});

test('configuration is persisted app-side and only the configured server is addressed', async () => {
  const store = fakeStore();
  const registry = createRegistry();
  const manager = createMcpManager({ call: store.call, emit: store.emit, registry });
  assert.deepEqual((await manager.load()).servers, []);
  await manager.add({ name: 'Echo', url: 'https://mcp.example.com/rpc' });
  assert.deepEqual(store.writes.at(-1).map(server => server.id), ['echo']);
  assert.equal(store.events.at(-1).event, 'mcp');
  assert.equal(store.events.at(-1).value.servers[0].state, 'disconnected');
  await assert.rejects(manager.add({ name: 'Bad', url: 'nope' }), /not a URL/);
  await assert.rejects(manager.remove({ id: 'missing' }), /Unknown MCP server/);
  await manager.remove({ id: 'echo' });
  assert.deepEqual(store.writes.at(-1), []);
  await manager.close();
});

test('a configured server connects, lists its tools and lets the agent call them', async () => {
  const server = await startMockMcpServer({ name: 'echo-server', tools: [ECHO_TOOL] });
  const store = fakeStore();
  const registry = createRegistry();
  const manager = createMcpManager({ call: store.call, emit: store.emit, registry });
  try {
    await manager.add({ name: 'Echo', url: server.url });
    const connected = await manager.connect({ id: 'echo' });
    assert.equal(connected.servers[0].state, 'connected');
    assert.equal(connected.servers[0].serverName, 'echo-server');
    assert.deepEqual(connected.servers[0].tools.map(tool => [tool.name, tool.tool]), [['mcp__echo__echo', 'echo']]);
    assert.deepEqual(server.methods(), ['initialize', 'tools/list']);
    // The server's tools are installed as one extension, which the default selection offers.
    assert.deepEqual(registry.snapshot().installed().map(extension => extension.name), [MCP_EXTENSION_PREFIX + 'echo']);

    await withFauxHarness(async ({ context, faux, root, registry: selected }) => {
      assert.deepEqual(selected, registry);
      faux.setResponses([
        fauxAssistantMessage([fauxToolCall('mcp__echo__echo', { text: 'hi' })], { stopReason: 'toolUse' }),
        fauxAssistantMessage('the server said hi'),
      ]);
      const submission = await root.submit({ type: 'input', content: 'use the echo tool', requestId: 'r1' }, context);
      const settled = await submission.wait(context);
      assert.equal(settled.status, 'done');
      const page = await root.entries({ order: 'ascending' }, 50, undefined, context);
      const result = page.items.find(entry => entry.kind === 'pi.tool-result');
      assert.equal(result === undefined, false, `no tool result among ${JSON.stringify(page.items.map(entry => entry.kind))}`);
      // The result the model reads is the MCP server's own reply, not a synthesized one.
      assert.match(JSON.stringify(result.model[0].content), /echo:hi/);
    }, { registry });
    assert.deepEqual(server.methods(), ['initialize', 'tools/list', 'tools/call']);

    // Disconnecting drops the tools with the connection.
    const disconnected = await manager.disconnect({ id: 'echo' });
    assert.equal(disconnected.servers[0].state, 'disconnected');
    assert.deepEqual(disconnected.servers[0].tools, []);
    assert.deepEqual(registry.snapshot().installed(), []);
  } finally {
    await manager.close();
    await server.close();
  }
});

test('a server that cannot be reached keeps its reason, reports no tools and installs nothing', async () => {
  const url = await closedPort();
  const store = fakeStore();
  const registry = createRegistry();
  const manager = createMcpManager({ call: store.call, emit: store.emit, registry });
  try {
    await manager.add({ name: 'Dead', url });
    const state = await manager.connect({ id: 'dead' });
    assert.equal(state.servers[0].state, 'failed');
    assert.equal(typeof state.servers[0].error, 'string');
    assert.equal(state.servers[0].error.length > 0, true);
    assert.deepEqual(state.servers[0].tools, []);
    assert.deepEqual(registry.snapshot().installed(), []);
  } finally {
    await manager.close();
  }
});

test('a server that answers with an error status is reported, never claimed connected', async () => {
  const server = await startMockMcpServer({ failWith: 503 });
  const store = fakeStore();
  const manager = createMcpManager({ call: store.call, emit: store.emit, registry: createRegistry() });
  try {
    await manager.add({ name: 'Broken', url: server.url });
    const state = await manager.connect({ id: 'broken' });
    assert.equal(state.servers[0].state, 'failed');
    assert.match(state.servers[0].error, /503|failed/i);
  } finally {
    await manager.close();
    await server.close();
  }
});

test('a persisted list is read back on load and a bad record is reported rather than dropped', async () => {
  const store = fakeStore([
    { id: 'echo', name: 'Echo', url: 'https://mcp.example.com/rpc' },
    { name: 'Bad', url: '%%%' },
  ]);
  const manager = createMcpManager({ call: store.call, emit: store.emit, registry: createRegistry() });
  const state = await manager.load();
  assert.deepEqual(state.servers.map(server => server.id), ['echo']);
  assert.equal(state.errors.some(error => /not a URL/.test(error)), true);
  // A store the host cannot read is empty, and says so.
  const unreadable = createMcpManager({ call: async () => { throw new Error('permission_denied'); }, emit: () => {}, registry: createRegistry() });
  const empty = await unreadable.load();
  assert.deepEqual(empty.servers, []);
  assert.equal(empty.errors[0].includes('permission_denied'), true);
});
