// The app-facing Pi native host.
//
// This factory is the one public entry point (`index.mjs` hands it to the WebView). It owns the
// long-lived services that cannot be rebuilt per call — the SQLite facade, the durable harness, the
// model collection and the tool registry — and composes the domain surfaces below it:
//
//   state.mjs      the open conversation, its durable event stream and transcript projection
//   providers.mjs  model catalog, credentials, sign-in and the one-shot operations
//   sessions.mjs   transcript reads, send, session switching, queue/usage/export
//   commands.mjs   app-owned skills and templates
//   mcp.mjs        configured MCP servers over Streamable HTTP
//   flows.mjs      the flow catalogue, authored specs and their runs
//
// Keeping those apart means a change to, say, sign-in never has to be reviewed against session
// switching, and the line-level diffs stay small enough to test.

import { Harness, createRegistry, defineExtension } from '@earendil-works/pi-durable';
import { SqliteStorage } from '@earendil-works/pi-durable/storage/sqlite';
import { createModels } from '@earendil-works/pi-ai/models';
import { BACKGROUND_CONTEXT } from '@earendil-works/chord/context';
import { createEditTool, createReadTool, createWriteTool } from '@earendil-works/pi-durable/tools';
import { createSqliteDatabase } from './sqlite.mjs';
import { createAndroidExecutionEnv } from './env.mjs';
import { createCredentialStore } from './credentials.mjs';
import { createModelsStore } from './models-store.mjs';
import { createBridgeFetch, withBridgeFetch } from './fetch.mjs';
import { createFetchTools } from './fetch-tool.mjs';
import { createAndroidShellTools } from './shell-tool.mjs';
import { createJsTools } from './js-tools.mjs';
import { createRunScriptTools } from './run-script-tool.mjs';
import { baseExtensions } from './skills.mjs';
import { installProviders, updateProviderCatalog } from './providers.mjs';
import { installOAuthFlows } from './oauth-flows.mjs';
import { createMcpManager } from './mcp.mjs';
import { message } from './host/util.mjs';
import { createHostState } from './host/state.mjs';
import { createProviderSurface } from './host/providers.mjs';
import { createSessionSurface } from './host/sessions.mjs';
import { createCommandSurface } from './host/commands.mjs';
import { createMcpSurface } from './host/mcp.mjs';
import { createFlowSurface } from './host/flows.mjs';

const context = BACKGROUND_CONTEXT;

export function createPiNative(bridge, options = {}) {
  let opening;
  let closed = false;

  // Static OAuth flow registration; without it the providers' lazy loaders cannot resolve in a
  // WebView (no module loader for the bundler-opaque dynamic import).
  installOAuthFlows();
  const credentials = createCredentialStore(bridge.call);
  const database = createSqliteDatabase(bridge.call);
  // Provider HTTP is performed in Kotlin (`fetch.mjs`): the WebView's own fetch leaves from
  // `https://pi.local`, which no provider grants via CORS, so it is refused before a model call.
  // The wrapped collection carries the transport explicitly on every operation pi-durable dispatches
  // (stream, deferred, images, classify); the installed ambient fetch covers the paths that take no
  // option at all (OAuth token refresh, dynamic model listing, the Google SDK).
  const transport = options.fetch ?? createBridgeFetch(bridge);
  const models = withBridgeFetch(createModels({
    credentials,
    modelsStore: createModelsStore(database),
    authContext: { env: async () => undefined, fileExists: async () => false },
  }), transport);
  const catalog = installProviders(models);

  const registry = createRegistry();
  // The upstream file tools (read/write/edit) touch files only through the conversation's
  // ExecutionEnv, which is the Android bridge. The upstream `bash` tool is deliberately NOT
  // installed: Android's `/system/bin/sh` is mksh, not bash, and the app's own tool is the native
  // `shell` below (honest name, host-owned timeout, bounded streaming).
  registry.install(defineExtension({
    name: 'coding-tools',
    tools: [createReadTool(), createWriteTool(), createEditTool()],
  }));
  const shellLimits = () => bridge.call('shell.capabilities');
  // The agent's handle on Android's native shell. The tool is called `shell`, not `bash`, and its
  // limits/timeout come from the Kotlin host (`backend/ShellLimits.kt`) through the reader.
  registry.install(createAndroidShellTools({ shellLimits }));

  const hostState = createHostState({ bridge, context });
  const { state, requireOpen, switchGuard, conversationIdOf, historyFrame, restartEvents, activate, emitConversations, resumeRequested } = hostState;

  // The agent's own way to fetch. The harness could previously only reach the network through
  // `/system/bin/curl`, which is a firmware binary (LG ships it; AOSP's base set does not), so a
  // device without it left the agent with no network at all. The tool drives the same Kotlin HTTP
  // client the provider calls use, so fetching is portable and involves no ambient fetch and no
  // CORS. `workspaceRoot` is read per call: the tool is installed before `init()` knows it.
  registry.install(createFetchTools({ transport, call: bridge.call, workspaceRoot: () => state.workspaceRoot }));
  // The autonomous JS package path: search npm/cdnjs, resolve an exact browser ESM package, and run
  // it in the Kotlin-side sandbox (no app bridge). The registry is the source; there is no
  // hand-curated catalog. `workspaceRoot` is read per call for the same reason as the fetch tool's.
  registry.install(createJsTools({
    transport,
    call: bridge.call,
    workspaceRoot: () => state.workspaceRoot,
  }));
  // The agent's own way to run a script it wrote. Android will not let an app process execute a file
  // in app storage (SELinux W^X since Android 10: `Permission denied`) and the workspace is mounted
  // `noexec`, so the agent's `./tool.sh` cannot work; `/system/bin/sh <script>` can, in both places,
  // because a script is data to the interpreter. The tool builds that ARGV itself and sends it
  // through the same `shell.exec` op. `workspaceRoot` is read per call for the same reason as the
  // fetch tool's: the tool is installed before `init()` knows it.
  registry.install(createRunScriptTools({
    call: bridge.call,
    workspaceRoot: () => state.workspaceRoot,
    // The shell's limits are the host's, not this side's: `shell.capabilities` answers with the cap
    // and the timeout window `shell.exec` really applies (`backend/ShellLimits.kt`), so the tool
    // reports what the host will do instead of a second copy that can drift from it.
    shellLimits,
  }));

  // The agent's own handle on flows. A flow is data it writes into its workspace
  // (`<workspace>/.pi-flows/<name>.json`, compiled by `flow-spec.mjs`), so authoring needs no new
  // authority — what it needs is to find one, run it, answer a paused step and read a thread back.
  // These four tools are that surface; they reach the SAME runner the host built, so a spec run here
  // is resumable through `resumeFlow` exactly like a shipped flow. Both accessors are read per call,
  // because neither the workspace nor its specs exist until `init()`.
  const flowSurface = createFlowSurface({
    bridge,
    context,
    requireOpen,
    workspaceRoot: () => state.workspaceRoot,
    transport,
    flows: options.flows,
  });
  flowSurface.install(registry);

  const commandSurface = createCommandSurface({
    bridge,
    requireOpen,
    workspaceRoot: () => state.workspaceRoot,
    registry,
  });
  const providerSurface = createProviderSurface({ bridge, models, credentials, catalog, requireOpen });
  const mcp = createMcpManager({
    call: bridge.call,
    emit: bridge.emit,
    registry,
    report: error => bridge.emit({ type: 'error', operation: 'mcp', message: message(error) }),
  });
  const mcpSurface = createMcpSurface({ bridge, mcp });
  const sessionSurface = createSessionSurface({
    bridge,
    models,
    context,
    state,
    requireOpen,
    switchGuard,
    conversationIdOf,
    historyFrame,
    activate,
    emitConversations,
    credentials,
    providerOf: providerSurface.providerOf,
    defaultModelId: providerSurface.defaultModelId,
    registry,
    skills: commandSurface.skills,
  });

  const probeShell = async () => {
    const shell = await bridge.call('shell.exec', { command: 'printf native-shell-ok', timeoutMs: 2000, requestId: 'capability-probe' });
    if (shell?.exitCode !== 0 || shell.stdout !== 'native-shell-ok') throw new Error('Shell capability probe failed');
    return { shell };
  };

  function init(options = {}) {
    if (opening) return opening;
    opening = (async () => {
      // One database facade for the whole host, shared with the durable storage: the app has ONE
      // SQLite connection and ONE serial database thread (`sql.begin` refuses a second transaction),
      // so a flow checkpoint and a durable commit have to be ordered through the same queue.
      const storage = await SqliteStorage.open(database);
      await credentials.load();
      // Restore provider-owned catalogs before any ready/picker event, without network or auth
      // refresh. Upstream reports per-provider storage failures without hiding other providers.
      const restored = await models.refresh({ allowNetwork: false });
      for (const [provider, error] of restored.errors) {
        bridge.emit({ type: 'error', operation: 'models.restore', provider, message: message(error) });
      }
      updateProviderCatalog(models, catalog);
      // One environment per harness; the conversation's cwd resolves against its root.
      const env = createAndroidExecutionEnv({
        call: bridge.call,
        cwd: typeof options.workspace === 'string' && options.workspace ? options.workspace : '/',
        subscribe: bridge.subscribe,
      });
      state.workspaceRoot = typeof options.workspace === 'string' && options.workspace ? options.workspace : '/';
      // The flow layer: superstep scheduling with one checkpoint per boundary, in the same
      // database. The store is kept so the runner can be rebuilt when the workspace's spec set
      // changes. `refresh` compiles `<workspace>/.pi-flows/*.json` into the SAME registry the runner
      // resolves `resumeFlow` by name from, then builds the runner — so an agent-authored spec is
      // runnable, `flows()`-listed, and resumable exactly like a shipped one. A broken spec is
      // reported as an error frame and skipped, never thrown here: one bad file must not fail the
      // launch or hide the specs beside it.
      await flowSurface.init(database);
      state.harness = await Harness.open(storage, {
        models,
        registry,
        // The default extension selection excludes every skill: a skill is installed for its
        // identity and selected by the one turn that invokes it, so its instructions never leak
        // into any other request's system prompt. Read at each resolution, never copied.
        settings: {
          get extensions() { return baseExtensions(registry.snapshot()); },
          retry: { enabled: false },
          compaction: { enabled: false },
          stream: { transport: 'sse', maxRetries: 0 },
        },
        onReport: error => bridge.emit({ type: 'error', message: String(error) }),
        env: () => env,
      }, context);
      state.conversation = await state.harness.root(context);
      // A conversation the reader was in when the app last ran is the one to start on — while it
      // still exists. A recorded id that names nothing is never resurrected: the root is the honest
      // default, so a stale preference can neither fail a launch nor reopen something that is gone.
      const resumed = await resumeRequested(options.conversationId);
      if (resumed) state.conversation = resumed;
      const existing = await state.conversation.entries({ order: 'descending' }, 1, undefined, context);
      const probe = await state.conversation.commit(tx => tx.appendEntry(state.conversation.id, { kind: 'app.native.probe', data: { runtime: 'pi-durable', version: '1.1.0' } }), context);
      const verified = await storage.entry(state.conversation.id, probe.id, context);
      if (!verified || verified.entry.kind !== probe.kind) throw new Error('Persistent probe readback failed');
      const shell = options.probeShell ? (await probeShell()).shell : undefined;
      const auth = await providerSurface.api.authState({ quiet: true });
      const value = { ready: true, conversationId: state.conversation.id, providers: catalog, auth, probe: verified.entry, previousProbe: existing.items.find(e => e.kind === 'app.native.probe'), ...(shell ? { shell } : {}) };
      await restartEvents(state.conversation.id);
      bridge.emit({ type: 'ready', ...value, value });
      // The pickers read app-owned files and an app-owned server store. Neither is fatal: a
      // missing skills directory means no skills yet, and a store that was never written is empty.
      await commandSurface.commands().catch(error => bridge.emit({ type: 'error', operation: 'commands', message: message(error) }));
      await mcpSurface.mcpState().catch(error => bridge.emit({ type: 'error', operation: 'mcp', message: message(error) }));
      return value;
    })().catch(error => { bridge.emit({ type: 'error', operation: 'init', message: message(error) }); throw error; });
    return opening;
  }

  /**
   * Release what only this runtime can release.
   *
   * The Kotlin host evaluates this on its teardown path (`NativeRuntimeHost.close()` →
   * `RuntimeTeardown`), with a bounded drain window before the WebView is destroyed under it. It is
   * idempotent — a second close is a no-op — and each resource is released independently, so one
   * failure cannot leave the MCP connections, the event stream and the harness all running. A
   * failure is reported as an `error` frame rather than thrown: this call must not reject while the
   * app is tearing the runtime down, and it must not stop the remaining steps from running either.
   */
  async function close() {
    if (closed) return;
    closed = true;
    const failures = [];
    const step = async (label, run) => {
      try { await run(); } catch (error) { failures.push(`${label}: ${message(error)}`); }
    };
    await step('mcp', () => mcpSurface.close());
    if (state.events) { await step('events', () => state.events.stop()); state.events = undefined; }
    if (state.harness) { await step('harness', () => state.harness.close(context)); }
    state.conversation = undefined;
    if (failures.length > 0) bridge.emit({ type: 'error', operation: 'close', message: failures.join('; ') });
  }

  return {
    probe: probeShell,
    ...providerSurface.api,
    init,
    ...sessionSurface.api,
    commands: commandSurface.commands,
    ...mcpSurface,
    ...flowSurface.api,
    close,
  };
}
