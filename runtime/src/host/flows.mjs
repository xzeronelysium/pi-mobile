// Flow layer surface: the shipped catalogue, the app's own definitions, and the workspace's
// authored specs. The spec set is compiled into the same runner that resolves `resumeFlow` by
// name, so a spec the agent just wrote is runnable, listed and resumable without a restart.

import { createFlowRunner, describeFlow } from '../flow.mjs';
import { createSqliteFlowStore } from '../flow-store.mjs';
import { loadFlowSpecs } from '../flow-specs.mjs';
import { createFlowTools } from '../flow-tool.mjs';
import { BUILT_IN_FLOWS } from '../flow-example.mjs';

export function createFlowSurface({ bridge, context, requireOpen, workspaceRoot, transport, flows = [] }) {
  /**
   * The flows this build ships. Static, so the catalogue can be read (`flows()`) before `init()`: a
   * caller that wants to offer a flow needs to know its shape without a database. `init()` may add
   * more through `options.flows` (the app's own definitions, or a test's).
   */
  const catalogue = [...BUILT_IN_FLOWS];
  // The flows this host can run: the shipped catalogue plus whatever the app configured. Definitions
  // are validated when the runner is built, so a malformed one fails `init()` rather than a run.
  const definitions = [...catalogue, ...(Array.isArray(flows) ? flows : [])];

  // The workspace's own specs, compiled by `loadFlowSpecs` and registered beside the shipped
  // catalogue. `store` is kept so the runner can be rebuilt when the spec set changes without
  // re-opening the database, and `specSignature` is what tells a rebuild from a no-op.
  let store = null;
  let runner = null;
  let registeredSpecs = new Map();
  let specSignature = null;

  /**
   * Build the runner over the shipped catalogue, the app's own definitions and the workspace's specs.
   *
   * The flow context reaches the platform exactly the way every other part of this runtime does —
   * the bridge `call` — plus the native HTTP transport for `fetch_url`. That transport is handed in
   * because the WebView's own fetch cannot stand in for it: the document origin is `https://pi.local`,
   * which no provider and no server grants via CORS, so a flow's `fetch_url` step would be refused
   * before a socket was opened. This is the same transport the provider calls use.
   */
  function buildRunner() {
    return createFlowRunner({
      store,
      flows: [...definitions, ...registeredSpecs.values()],
      emit: event => bridge.emit(event),
      now: Date.now,
      context: { call: bridge.call, workspace: workspaceRoot(), fetch: transport },
    });
  }

  /**
   * Re-read `<workspace>/.pi-flows` and, when the set of spec files changed, rebuild the runner so
   * the new specs are runnable AND resumable — a thread resolves its flow BY NAME from the runner's
   * registry, which is why registering through the host (not compiling one on the fly) is the only
   * shape that survives `resumeFlow`.
   *
   * A broken spec is reported and skipped, never thrown: one bad file must not stop the host from
   * starting, hide the specs beside it, or fail a tool call. Error frames are emitted only when the
   * spec set actually changed, so an unchanged workspace does not spam the console.
   */
  async function refresh() {
    const discovery = await loadFlowSpecs(bridge.call, workspaceRoot());
    if (discovery.signature !== specSignature) {
      specSignature = discovery.signature;
      registeredSpecs = new Map(discovery.specs.map(spec => [spec.name, spec.definition]));
      // A broken spec file is loud: one `error` frame per file, already naming the file AND carrying
      // the compiler's own coded message, so the agent can fix it from the frame alone. A
      // directory-level problem (nothing there yet, or a bridge with no listing) is not a broken
      // file and is not framed; it still rides back to the flow tools verbatim.
      for (const error of discovery.errors) {
        if (error.name === null) continue;
        bridge.emit({ type: 'error', operation: 'flow-specs', file: error.path, code: error.code, message: error.message });
      }
      if (store) runner = buildRunner();
    }
    return discovery;
  }

  /** Open the flow store on the host's shared database and register the initial spec set. */
  async function init(database) {
    store = createSqliteFlowStore({ db: database });
    await refresh();
  }

  /** Install the agent's own flow tools against this surface's live runner. */
  function install(registry) {
    registry.install(createFlowTools({ runner: () => runner, refresh }));
  }

  const api = {
    // The flow layer as the app reaches it: run a definition, resume a paused run, read a thread's
    // status and its boundaries, list what this build can run. Every frame the runner emits is
    // forwarded to the bridge verbatim, so the Kotlin consumer reads exactly the payloads the tests
    // pin. `flows()` is the one read that answers before `init()`: the shipped catalogue is static,
    // and a caller offering a flow should not need a database to see its shape.

    /** What this build can run, with each flow's entry, steps and state schema. */
    async flows() {
      // Re-read the workspace's specs first, so a spec the agent just wrote is listed without a
      // restart. Before `init()` there is no runner and no workspace: the static catalogue answers.
      if (runner) await refresh();
      const value = { flows: runner ? runner.flows() : catalogue.map(flow => describeFlow(flow)) };
      bridge.emit({ type: 'flows', value });
      return value;
    },

    /**
     * Run a flow. The summary is the run's own record: `paused` means it is waiting for
     * `resumeFlow`, `done` means it reached an end. A failing step rejects with its code, step and
     * cause after the `flow.finished` frame has reported the same failure.
     */
    async runFlow(config = {}) {
      requireOpen();
      const name = typeof config?.flow === 'string' ? config.flow.trim() : '';
      if (!name) throw new Error('flow required');
      // A spec authored since `init()` is picked up here: the runner is rebuilt with it, so the run
      // is both executable and resumable by name.
      await refresh();
      const threadId = typeof config?.threadId === 'string' && config.threadId.trim() ? config.threadId.trim() : undefined;
      return runner.run(name, { threadId, input: config?.input, limits: config?.limits });
    },

    /** Continue a paused run: the steps it awaited re-run with `value` as their resume result. */
    async resumeFlow(config = {}) {
      requireOpen();
      const threadId = typeof config?.threadId === 'string' ? config.threadId.trim() : '';
      if (!threadId) throw new Error('threadId required');
      // The thread's flow is resolved by name from the runner's registry, so the workspace's specs
      // must be registered before a resume can find one written after `init()`.
      await refresh();
      return runner.resume(threadId, config?.value, { limits: config?.limits });
    },

    /** One thread: its newest state, what it awaits, whether it failed, and its boundary history. */
    async flowStatus(query = {}) {
      requireOpen();
      const threadId = typeof query?.threadId === 'string' ? query.threadId.trim() : '';
      if (!threadId) throw new Error('threadId required');
      const value = await runner.status(threadId, { limit: query?.limit });
      bridge.emit({ type: 'flow.status', value });
      return value;
    },

    /** Every known thread, newest boundary first; `limit` bounds how many are rendered. */
    async flowThreads(query = {}) {
      requireOpen();
      const value = { threads: await runner.threads({ limit: query?.limit }) };
      bridge.emit({ type: 'flow.threads', value });
      return value;
    },
  };

  return { init, refresh, install, api };
}
