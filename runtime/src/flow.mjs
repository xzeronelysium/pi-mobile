// The flow layer: typed state with reducers, named steps executed in deterministic supersteps, and
// one checkpoint per superstep boundary in the app's own database.
//
// WHY THIS EXISTS, AND WHY IT IS JAVASCRIPT
// The harness already runs an agent on a packaged JavaScript engine in the WebView, and we measured
// on device that the app cannot execute a file it wrote: SELinux's W^X rule refuses an app process
// the exec of app storage data, and the workspace is mounted `noexec`. `/system/bin/sh <script>`
// does work, because a script is data to the interpreter. So a multi-step capability has to be
// *evaluated* inside the engine that is already in-process, not delegated to a second interpreter:
// this module is that capability, and it adds no dependency and forks nothing.
//
// THE MODEL, IN ONE PARAGRAPH
// A flow is a frozen definition: a state schema (named fields with an initial value and a reducer),
// named async steps, and transitions (a static next step, a router function, or `END`). A run moves
// in *supersteps*: the ready steps are determined, they all run concurrently against the same
// frozen snapshot of the state, and only when every one of them has settled are their writes applied
// through the reducers. No step can observe another step's write inside its own superstep, which is
// exactly what makes the concurrency deterministic. A step may instead return an `interrupt`, which
// pauses the run at a committed boundary and records what it awaits; `resume(threadId, value)`
// re-runs that step with the value and continues. Every superstep boundary is one checkpoint, so a
// thread that is restored after a crash reproduces the exact state of its last completed superstep.
//
// The bounds are honest, not decorative: the step budget and the serialized-state size are checked
// at every boundary and reported as coded errors, never as a hang or a silent truncation. A failure
// never leaves a half-applied superstep: the writes of a superstep are applied only after all of its
// steps have settled, and a failure is recorded against the last boundary that was actually
// committed.

/** The end sentinel a transition or a step's `goto` may return. */
export const END = Symbol.for('pi.flow.end');

/** How many steps one `run`/`resume` invocation may execute before `flow_max_steps`. */
export const DEFAULT_MAX_STEPS = 100;
/** How many bytes one checkpoint's serialized state may occupy before `flow_state_too_large`. */
export const DEFAULT_MAX_STATE_BYTES = 256 * 1024;

/** The lifecycle a thread's newest checkpoint can be in. */
export const FLOW_STATUSES = Object.freeze(['running', 'paused', 'done', 'failed']);

/** The keys a step's return value reserves for routing and interrupting; state fields may not use them. */
export const RESERVED_RESULT_KEYS = Object.freeze(['update', 'goto', 'interrupt']);

/**
 * Every code this layer raises. `details` always carries the identifiers a Kotlin consumer needs
 * (thread, flow, step, field, seq) and never any secret material.
 */
export const FLOW_ERROR_CODES = Object.freeze([
  'flow_invalid_definition', 'flow_invalid_limits', 'flow_invalid_input', 'flow_invalid_result',
  'flow_invalid_transition', 'flow_transition_failed', 'flow_unknown_field', 'flow_reducer_error',
  'flow_unserializable_state', 'flow_unsupported_state', 'flow_cyclic_state', 'flow_state_too_large',
  'flow_max_steps', 'flow_step_failed', 'flow_unknown_flow', 'flow_unknown_thread',
  'flow_thread_exists', 'flow_thread_busy', 'flow_not_paused', 'flow_corrupt_checkpoint',
]);

export class FlowError extends Error {
  constructor(code, message, details = {}, cause) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'FlowError';
    this.code = code;
    this.details = details;
    // Convenience for callers that only want the step: `error.step` reads better than
    // `error.details.step` at a call site that already knows the code.
    if (typeof details.step === 'string') this.step = details.step;
  }
}

const message = error => (error instanceof Error ? error.message : String(error));

const isPlainObject = value => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
};

const describePath = path => path.map(segment => (typeof segment === 'number' ? `[${segment}]` : segment)).join('.');

const unsupported = (where, path, detail) => {
  const named = path.length === 0 ? '' : `.${describePath(path)}`;
  return new FlowError('flow_unsupported_state', `${where.name}${named} ${detail}`, { threadId: where.threadId ?? null, seq: where.seq ?? null, field: path[0] ?? null, path: describePath(path) });
};

/**
 * Walk a value the way JSON would, and refuse — with the path to the offender — anything JSON would
 * drop or refuse. `JSON.stringify` silently omits `undefined`, functions and symbols; a flow state is
 * the durable record of a run, so a value it cannot carry is an error at the boundary, not a silent
 * loss.
 */
function assertJson(value, where, path = []) {
  if (value === null) return;
  const type = typeof value;
  if (type === 'string' || type === 'boolean') return;
  if (type === 'number') {
    if (!Number.isFinite(value)) {
      throw new FlowError('flow_unserializable_state', `${where.name}${path.length ? `.${describePath(path)}` : ''} is ${value}, which JSON cannot carry`, { threadId: where.threadId ?? null, seq: where.seq ?? null, field: path[0] ?? null, path: describePath(path) });
    }
    return;
  }
  if (type !== 'object') {
    const detail = type === 'undefined' ? 'is undefined, which JSON would silently drop' : `is a ${type}, which JSON would silently drop`;
    throw new FlowError('flow_unserializable_state', `${where.name}${path.length ? `.${describePath(path)}` : ''} ${detail}`, { threadId: where.threadId ?? null, seq: where.seq ?? null, field: path[0] ?? null, path: describePath(path) });
  }
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index += 1) assertJson(value[index], where, [...path, index]);
    return;
  }
  if (!isPlainObject(value)) throw unsupported(where, path, `is a ${value.constructor?.name ?? 'non-plain object'}; a flow state holds JSON data only`);
  if (Object.getOwnPropertySymbols(value).length > 0) throw unsupported(where, path, 'has symbol keys, which JSON would silently drop');
  for (const [key, entry] of Object.entries(value)) assertJson(entry, where, [...path, key]);
}

/** The state, and the awaiting marker, are the same JSON contract; the state additionally has a size. */
function serializeJson(value, where) {
  assertJson(value, where, []);
  const text = JSON.stringify(value);
  if (typeof text !== 'string') {
    throw new FlowError('flow_unserializable_state', `${where.name} is ${typeof value}, which JSON cannot carry`, { threadId: where.threadId ?? null, seq: where.seq ?? null, field: null, path: '' });
  }
  return { text, bytes: new TextEncoder().encode(text).length };
}

function serializeState(state, where, bounds) {
  const json = serializeJson(state, { ...where, name: `${where.name} state` });
  if (json.bytes > bounds.maxStateBytes) {
    throw new FlowError('flow_state_too_large', `${where.name}: the state is ${json.bytes} bytes, past the ${bounds.maxStateBytes}-byte checkpoint limit`, { threadId: where.threadId ?? null, seq: where.seq ?? null, bytes: json.bytes, limit: bounds.maxStateBytes });
  }
  return json;
}

/** A detached copy, so a step's own object can never alias into the state it is stored in. */
function cloneJson(value, where, seen = new Set()) {
  if (value === null || typeof value !== 'object') return value;
  if (seen.has(value)) throw new FlowError('flow_cyclic_state', `${where.name} is cyclic; a flow state is a tree`, { threadId: where.threadId ?? null, field: where.field ?? null });
  seen.add(value);
  let copy;
  if (Array.isArray(value)) copy = value.map(entry => cloneJson(entry, where, seen));
  else if (isPlainObject(value)) {
    copy = {};
    for (const [key, entry] of Object.entries(value)) copy[key] = cloneJson(entry, where, seen);
  } else {
    seen.delete(value);
    throw unsupported(where, [], `is a ${value.constructor?.name ?? 'non-plain object'}; a flow state holds JSON data only`);
  }
  seen.delete(value);
  return copy;
}

function deepFreeze(value) {
  if (value === null || typeof value !== 'object') return value;
  Object.freeze(value);
  for (const key of Object.keys(value)) deepFreeze(value[key]);
  return value;
}

/**
 * The three reducers. Each one is pure: it returns a new value and never mutates the current one,
 * so the state a checkpoint already recorded cannot be edited by a later superstep.
 */
export const REDUCERS = Object.freeze({
  replace: (_current, value) => value,
  append: (current, value) => {
    if (!Array.isArray(current)) throw new TypeError('append needs an array to append to');
    return [...current, ...(Array.isArray(value) ? value : [value])];
  },
  merge: (current, value) => {
    if (!isPlainObject(current) || !isPlainObject(value)) throw new TypeError('merge needs an object on both sides');
    return { ...current, ...value };
  },
});

const invalidDefinition = text => new FlowError('flow_invalid_definition', text, {});

/** The bounds every flow starts with; a definition may narrow or widen them, a run may override them. */
const DEFAULT_LIMITS = Object.freeze({ maxSteps: DEFAULT_MAX_STEPS, maxStateBytes: DEFAULT_MAX_STATE_BYTES });

function checkLimits(value, where) {
  if (!Number.isInteger(value.maxSteps) || value.maxSteps < 1) throw new FlowError('flow_invalid_limits', `${where}: maxSteps must be a positive whole number`, { limit: 'maxSteps' });
  if (!Number.isInteger(value.maxStateBytes) || value.maxStateBytes < 1) throw new FlowError('flow_invalid_limits', `${where}: maxStateBytes must be a positive whole number`, { limit: 'maxStateBytes' });
  return Object.freeze({ maxSteps: value.maxSteps, maxStateBytes: value.maxStateBytes });
}

/**
 * Normalize and validate a definition, once, when it is declared. Every error a flow author can make
 * that does not need a running state (an unknown step, a bad reducer, a reserved field name) is
 * raised here rather than in the middle of a run.
 */
export function defineFlow(definition = {}) {
  if (!isPlainObject(definition)) throw invalidDefinition('a flow definition must be an object');
  const name = typeof definition.name === 'string' ? definition.name.trim() : '';
  if (name === '') throw invalidDefinition('a flow needs a name');
  if (!isPlainObject(definition.steps) || Object.keys(definition.steps).length === 0) throw invalidDefinition(`${name} needs at least one step`);
  const steps = {};
  for (const [step, run] of Object.entries(definition.steps)) {
    if (typeof run !== 'function') throw invalidDefinition(`${name}: step ${step} is not a function`);
    steps[step] = run;
  }
  const declaredState = definition.state ?? {};
  if (!isPlainObject(declaredState)) throw invalidDefinition(`${name}: state must be an object of fields`);
  const state = {};
  for (const [field, spec] of Object.entries(declaredState)) {
    if (RESERVED_RESULT_KEYS.includes(field)) throw invalidDefinition(`${name}: state field ${field} is a reserved result key (${RESERVED_RESULT_KEYS.join(', ')})`);
    if (!isPlainObject(spec) || !('initial' in spec)) throw invalidDefinition(`${name}: state field ${field} needs an initial value`);
    const reducer = spec.reducer ?? 'replace';
    if (typeof reducer !== 'string' || typeof REDUCERS[reducer] !== 'function') throw invalidDefinition(`${name}: state field ${field} names reducer ${String(reducer)}, which is not one of ${Object.keys(REDUCERS).join(', ')}`);
    if (spec.initial === undefined) throw invalidDefinition(`${name}: state field ${field} needs an initial value, not undefined`);
    state[field] = Object.freeze({ initial: deepFreeze(cloneJson(spec.initial, { name: `${name}: the initial value of ${field}` })), reducer });
  }
  const declaredEntry = definition.entry === undefined ? [] : (Array.isArray(definition.entry) ? definition.entry : [definition.entry]);
  for (const step of declaredEntry) {
    if (typeof step !== 'string' || steps[step] === undefined) throw invalidDefinition(`${name}: entry names ${JSON.stringify(step)}, which is not a step of this flow`);
  }
  const declaredTransitions = definition.transitions ?? {};
  if (!isPlainObject(declaredTransitions)) throw invalidDefinition(`${name}: transitions must be an object`);
  const transitions = {};
  for (const [step, spec] of Object.entries(declaredTransitions)) {
    if (steps[step] === undefined) throw invalidDefinition(`${name}: a transition is declared for unknown step ${step}`);
    if (typeof spec === 'function' || spec === END) { transitions[step] = spec; continue; }
    if (typeof spec === 'string') {
      if (steps[spec] === undefined) throw invalidDefinition(`${name}: the transition from ${step} names unknown step ${spec}`);
      transitions[step] = spec;
      continue;
    }
    if (Array.isArray(spec)) {
      for (const target of spec) if (typeof target !== 'string' || steps[target] === undefined) throw invalidDefinition(`${name}: the transition from ${step} names unknown step ${JSON.stringify(target)}`);
      transitions[step] = Object.freeze([...spec]);
      continue;
    }
    throw invalidDefinition(`${name}: the transition from ${step} must be a step name, an array of them, a router function or END`);
  }
  const declaredLimits = definition.limits ?? {};
  if (!isPlainObject(declaredLimits)) throw invalidDefinition(`${name}: limits must be an object`);
  const limits = checkLimits({ ...DEFAULT_LIMITS, ...declaredLimits }, `${name}: limits`);
  return Object.freeze({
    name,
    entry: Object.freeze([...declaredEntry]),
    steps: Object.freeze(steps),
    transitions: Object.freeze(transitions),
    state: Object.freeze(state),
    limits,
  });
}

/** The definition as a consumer renders it: no functions, no closures, just the declared shape. */
export function describeFlow(flow) {
  return {
    name: flow.name,
    entry: [...flow.entry],
    steps: Object.keys(flow.steps),
    state: Object.entries(flow.state).map(([field, spec]) => ({ field, reducer: spec.reducer, initial: cloneJson(spec.initial, { name: `${flow.name}: the initial value of ${field}` }) })),
    limits: { ...flow.limits },
  };
}

/** A step's return value, split into the three things it can mean. */
function normalizeResult(raw, { flow, step }) {
  const invalid = text => new FlowError('flow_invalid_result', `${flow}: step ${step} ${text}`, { flow, step });
  if (raw === undefined || raw === null) return { writes: {}, goto: undefined, interrupt: undefined };
  if (!isPlainObject(raw)) throw invalid(`returned a ${Array.isArray(raw) ? 'array' : typeof raw}; a step returns an object of state writes, optionally with goto/interrupt`);
  const keys = Object.keys(raw);
  const envelope = keys.includes('update');
  const writes = {};
  if (envelope) {
    for (const key of keys) if (!RESERVED_RESULT_KEYS.includes(key)) throw invalid(`returned an unknown key ${key} beside update`);
    if (raw.update !== undefined && !isPlainObject(raw.update)) throw invalid('returned an update that is not an object of state writes');
    Object.assign(writes, raw.update ?? {});
  } else {
    for (const key of keys) if (!RESERVED_RESULT_KEYS.includes(key)) writes[key] = raw[key];
  }
  const goto = raw.goto;
  if (goto !== undefined && goto !== END && typeof goto !== 'string' && !Array.isArray(goto)) throw invalid('routed with a goto that is not a step name, an array of them, or END');
  const interrupt = raw.interrupt === undefined || raw.interrupt === false ? undefined : (raw.interrupt === true ? null : raw.interrupt);
  if (interrupt !== undefined && Object.keys(writes).length > 0) throw invalid('both interrupted and wrote state; the writes would be applied twice when the step re-runs on resume');
  return { writes, goto, interrupt };
}

const dedupe = names => [...new Set(names)];

/** Route one settled step: its own `goto` first, then the declared transition. */
async function routeStep({ flow, threadId, outcome, state, superstep, steps }) {
  const target = value => targetNames(flow, value, outcome.step, steps);
  if (outcome.result.goto !== undefined) return target(outcome.result.goto);
  const spec = flow.transitions[outcome.step];
  if (spec === undefined || spec === END) return [];
  if (typeof spec !== 'function') return target(spec);
  let routed;
  try {
    routed = await spec(state, { threadId, flow, step: outcome.step, superstep, writes: outcome.result.writes });
  } catch (cause) {
    throw new FlowError('flow_transition_failed', `${flow}: the router for ${outcome.step} failed: ${message(cause)}`, { flow, step: outcome.step, threadId }, cause);
  }
  return target(routed);
}

function targetNames(flow, value, step, steps) {
  const list = value === undefined || value === null || value === END ? [] : (Array.isArray(value) ? value : [value]);
  const names = [];
  for (const name of list) {
    if (name === END) continue;
    if (typeof name !== 'string' || steps[name] === undefined) {
      throw new FlowError('flow_invalid_transition', `${flow}: the transition from ${step} names ${typeof name === 'string' ? name : typeof name}, which is not a step of this flow`, { flow, step, target: typeof name === 'string' ? name : null });
    }
    if (!names.includes(name)) names.push(name);
  }
  return names;
}

/**
 * Apply a superstep's writes through the reducers, in the deterministic order the steps were made
 * ready. The result is a new state object: the caller's state is never edited, so a failure part way
 * through cannot leave a half-applied superstep behind.
 */
function applyWrites(state, entries, flow) {
  let next = state;
  for (const { source, writes } of entries) {
    if (!isPlainObject(writes)) throw new FlowError('flow_invalid_result', `${flow.name}: ${source} must return an object of state writes`, { flow: flow.name, step: source });
    for (const [field, raw] of Object.entries(writes)) {
      const spec = flow.state[field];
      if (spec === undefined) throw new FlowError('flow_unknown_field', `${flow.name}: ${source} wrote undeclared state field ${field}`, { flow: flow.name, step: source, field });
      const value = cloneJson(raw, { name: `${flow.name}: the value ${source} wrote to ${field}`, field });
      let reduced;
      try {
        reduced = REDUCERS[spec.reducer](next[field], value);
      } catch (cause) {
        throw new FlowError('flow_reducer_error', `${flow.name}: the ${spec.reducer} reducer for ${field} refused ${source}'s write: ${message(cause)}`, { flow: flow.name, step: source, field, reducer: spec.reducer }, cause);
      }
      if (next === state) next = { ...state };
      next[field] = reduced;
    }
  }
  return next;
}

/**
 * Create a runner over a store.
 *
 * `store` is the small checkpoint interface (`save`, `latest`, `history`, `threads`, `remove`, `fail`,
 * `ready`); `flows` are definitions; `emit` receives `{ type, ...fields }` frames; `now` is injectable
 * so a test can pin timestamps; `context` is merged into every step's context (the host puts the
 * bridge `call` and the workspace root there).
 *
 * The bounds of a run come from the definition and may be overridden for one invocation
 * (`run(name, { limits })`), which is the only place a caller who is not the flow author decides them.
 */
export function createFlowRunner({ store, flows = [], emit, now = Date.now, context = {} } = {}) {
  if (!store || typeof store.save !== 'function') throw new TypeError('createFlowRunner requires a flow store');
  const registry = new Map();
  for (const definition of flows) {
    const flow = defineFlow(definition);
    registry.set(flow.name, flow);
  }
  const active = new Set();
  let sequence = 0;

  // A broken event sink is a broken display, not a broken run: the checkpoint is the record.
  const emitEvent = event => {
    if (typeof emit !== 'function') return;
    try { emit(event); } catch { /* the frames are observability, never the state */ }
  };

  const resolve = name => {
    const flow = registry.get(name);
    if (flow === undefined) throw new FlowError('flow_unknown_flow', `Unknown flow: ${name}`, { flow: name });
    return flow;
  };

  const boundsFor = (flow, override) => checkLimits({ ...flow.limits, ...(override ?? {}) }, `${flow.name}: limits`);

  const corrupt = (row, field, cause) => new FlowError('flow_corrupt_checkpoint', `Flow thread ${row.threadId} checkpoint ${row.seq} holds a ${field} that cannot be restored`, { threadId: row.threadId, seq: row.seq, field }, cause);

  const parseState = row => {
    let state;
    try {
      state = JSON.parse(row.state);
    } catch (cause) {
      throw corrupt(row, 'state', cause);
    }
    if (!isPlainObject(state)) throw corrupt(row, 'state');
    return state;
  };

  const parseAwaiting = row => {
    if (row.awaiting === null || row.awaiting === undefined) return null;
    let value;
    try {
      value = JSON.parse(row.awaiting);
    } catch (cause) {
      throw corrupt(row, 'awaiting', cause);
    }
    if (!isPlainObject(value) || !Array.isArray(value.steps) || !Array.isArray(value.values) || !Array.isArray(value.next)) throw corrupt(row, 'awaiting');
    return { steps: [...value.steps], values: [...value.values], next: [...value.next] };
  };

  const parseError = text => {
    if (text === null || text === undefined) return null;
    try {
      return JSON.parse(text);
    } catch {
      return { step: null, code: null, message: String(text) };
    }
  };

  /** Where a step's context stops and the engine's own fields begin: the engine always wins. */
  const stepContext = ({ flow, threadId, step, superstep, seq, resume }) => Object.freeze({
    ...context,
    threadId,
    flow: flow.name,
    step,
    superstep,
    seq,
    resume,
  });

  const stepStarted = ({ flow, threadId, step, superstep, seq }) => ({ type: 'flow.step.started', threadId, flow: flow.name, step, superstep, seq });

  // The frame for a boundary is emitted by whoever decided to commit it, so the run can announce
  // `flow.started` before the start boundary's own frame instead of after it: the frames then read in
  // the order the run reached them.
  const announce = ({ flow, threadId, row, steps, next = [] }) => emitEvent({
    type: 'flow.checkpoint',
    threadId,
    flow: flow.name,
    seq: row.seq,
    status: row.status,
    steps: [...steps],
    next: [...next],
    bytes: row.bytes,
  });

  // One failure report for every way a run can fail, so a consumer sees one terminal frame shape and
  // one durable marker, whichever boundary first refused.
  const failureOf = error => ((error instanceof FlowError && error.details?.failure)
    ? error.details.failure
    : { step: error?.details?.step ?? null, code: error?.code ?? 'flow_step_failed', message: message(error) });

  return {
    /** The flows this runner can run, as a consumer renders them. */
    flows() { return [...registry.values()].map(describeFlow); },

    /** Known threads, newest checkpoint first, with the failure marker already parsed. */
    async threads(options = {}) {
      const limit = options.limit === undefined ? null : checkCount(options.limit, 'flowThreads');
      const rows = await store.threads();
      const sliced = limit === null ? rows : rows.slice(0, limit);
      return sliced.map(row => ({ ...row, error: parseError(row.error) }));
    },

    /** One thread: its newest checkpoint's state, what it awaits, and its boundary history. */
    async status(threadId, options = {}) {
      const id = requireThreadId(threadId);
      const limit = options.limit === undefined ? 20 : checkCount(options.limit, 'checkpoints');
      const row = await store.latest(id);
      if (row === null || row === undefined) throw new FlowError('flow_unknown_thread', `Unknown flow thread: ${id}`, { threadId: id });
      const history = await store.history(id, { limit });
      return {
        threadId: id,
        flow: row.flow,
        status: row.status,
        seq: row.seq,
        steps: [...row.steps],
        bytes: row.bytes,
        state: parseState(row),
        awaiting: parseAwaiting(row),
        error: parseError(row.error),
        createdAt: row.createdAt,
        // The newest boundary's state is above; the history is the index a UI pages through.
        checkpoints: history.map(entry => ({ seq: entry.seq, status: entry.status, steps: [...entry.steps], bytes: entry.bytes, error: parseError(entry.error), createdAt: entry.createdAt })),
      };
    },

    /** Start a thread and drive it to its end, its next pause, or its first failure. */
    async run(name, options = {}) {
      const flow = resolve(name);
      const threadId = typeof options.threadId === 'string' && options.threadId.trim() !== '' ? options.threadId.trim() : `${name}-${now().toString(36)}-${++sequence}`;
      if (active.has(threadId)) throw new FlowError('flow_thread_busy', `Flow thread ${threadId} is already running`, { threadId, flow: name });
      const existing = await store.latest(threadId);
      if (existing !== null && existing !== undefined) {
        throw new FlowError('flow_thread_exists', `Flow thread ${threadId} already exists (${existing.status}); resume it instead`, { threadId, flow: name, status: existing.status });
      }
      if (flow.entry.length === 0) throw new FlowError('flow_invalid_definition', `${name} declares no entry step`, { flow: name });
      const bounds = boundsFor(flow, options.limits);
      const state = initialState(flow, options.input);
      active.add(threadId);
      try {
        // The start is a boundary like any other: a crash before the first step still leaves a
        // thread whose state can be read back. A failure here propagates without a terminal frame —
        // the run never started, and there is no boundary to attribute a failure to.
        const started = await commit({ flow, threadId, seq: 0, state, status: 'running', steps: [], awaiting: null, next: flow.entry, bounds });
        emitEvent({ type: 'flow.started', threadId, flow: name, seq: started.seq, ready: [...flow.entry], limits: { ...bounds } });
        announce({ flow, threadId, row: started, steps: [], next: flow.entry });
        return await drive({ flow, threadId, bounds, state, ready: [...flow.entry], seq: 0, carry: undefined, resume: undefined, ran: [], supersteps: 0 });
      } finally {
        active.delete(threadId);
      }
    },

    /** Continue a paused thread: the steps it awaited re-run with `value` as their resume result. */
    async resume(threadId, value, options = {}) {
      const id = requireThreadId(threadId);
      if (active.has(id)) throw new FlowError('flow_thread_busy', `Flow thread ${id} is already running`, { threadId: id });
      const row = await store.latest(id);
      if (row === null || row === undefined) throw new FlowError('flow_unknown_thread', `Unknown flow thread: ${id}`, { threadId: id });
      if (row.status !== 'paused') throw new FlowError('flow_not_paused', `Flow thread ${id} is ${row.status}, not paused`, { threadId: id, status: row.status });
      const flow = resolve(row.flow);
      const bounds = boundsFor(flow, options.limits);
      const state = parseState(row);
      const awaiting = parseAwaiting(row);
      if (awaiting === null || awaiting.steps.length === 0) throw new FlowError('flow_corrupt_checkpoint', `Flow thread ${id} is paused but records nothing awaiting`, { threadId: id, seq: row.seq, field: 'awaiting' });
      active.add(id);
      try {
        emitEvent({ type: 'flow.resumed', threadId: id, flow: flow.name, seq: row.seq, steps: [...awaiting.steps], value });
        return await drive({
          flow,
          threadId: id,
          bounds,
          state,
          ready: [...awaiting.steps],
          seq: row.seq,
          // Routing a completed sibling already decided belongs after the resumed step, not beside it.
          carry: awaiting.next.filter(step => !awaiting.steps.includes(step)),
          resume: value,
          ran: [],
          supersteps: 0,
        });
      } finally {
        active.delete(id);
      }
    },

    /** The store itself, so a caller (or a test) can read the boundaries directly. */
    store,
  };

  function initialState(flow, input) {
    const state = {};
    for (const [field, spec] of Object.entries(flow.state)) state[field] = cloneJson(spec.initial, { name: `${flow.name}: the initial value of ${field}` });
    if (input === undefined || input === null) return state;
    if (!isPlainObject(input)) throw new FlowError('flow_invalid_input', `${flow.name}: input must be an object of state fields`, { flow: flow.name });
    return applyWrites(state, [{ source: 'input', writes: input }], flow);
  }

  async function commit({ flow, threadId, seq, state, status, steps, awaiting, next = [], bounds }) {
    const where = { threadId, seq, name: `${threadId}@${seq}` };
    const json = serializeState(state, { ...where, name: flow.name }, bounds);
    const awaitingText = awaiting === null ? null : serializeJson(awaiting, { ...where, name: `${flow.name} awaiting marker` }).text;
    const row = await store.save({
      threadId,
      flow: flow.name,
      seq,
      status,
      steps: [...steps],
      state: json.text,
      awaiting: awaitingText,
      bytes: json.bytes,
      createdAt: now(),
    });
    return row;
  }

  async function drive({ flow, threadId, bounds, state, ready, seq, carry, resume, ran, supersteps }) {
    let current = state;
    let currentSeq = seq;
    let currentReady = dedupe(ready);
    let pending = carry;
    let executed = 0;
    let executedSteps = ran;
    let executedSupersteps = supersteps;
    for (;;) {
      try {
        const budget = bounds.maxSteps - executed;
        if (currentReady.length > budget) {
          throw new FlowError('flow_max_steps', `${flow.name} would run ${currentReady.length} more step(s) past the ${bounds.maxSteps}-step budget`, { threadId, flow: flow.name, step: currentReady[budget] ?? null, executed, limit: bounds.maxSteps });
        }
        const snapshot = deepFreeze(cloneJson(current, { name: `${flow.name}: the state handed to the steps`, threadId }));
        for (const step of currentReady) emitEvent(stepStarted({ flow, threadId, step, superstep: executedSupersteps, seq: currentSeq }));
        const outcomes = await Promise.all(currentReady.map(async step => {
          const began = now();
          try {
            const result = normalizeResult(await flow.steps[step](snapshot, stepContext({ flow, threadId, step, superstep: executedSupersteps, seq: currentSeq, resume })), { flow: flow.name, step });
            return { step, ok: true, result, durationMs: now() - began };
          } catch (error) {
            return { step, ok: false, error, durationMs: now() - began };
          }
        }));
        executed += outcomes.length;
        executedSteps = [...executedSteps, ...currentReady];
        executedSupersteps += 1;
        for (const outcome of outcomes) {
          if (outcome.ok) {
            emitEvent({ type: 'flow.step.finished', threadId, flow: flow.name, step: outcome.step, superstep: executedSupersteps - 1, seq: currentSeq, ok: true, durationMs: outcome.durationMs, wrote: Object.keys(outcome.result.writes) });
          } else {
            emitEvent({ type: 'flow.step.finished', threadId, flow: flow.name, step: outcome.step, superstep: executedSupersteps - 1, seq: currentSeq, ok: false, durationMs: outcome.durationMs, wrote: [], code: outcome.error?.code ?? 'flow_step_failed', message: message(outcome.error) });
          }
        }
        const failed = outcomes.find(outcome => outcome.ok === false);
        if (failed !== undefined) {
          throw new FlowError('flow_step_failed', `${flow.name} step ${failed.step} failed: ${message(failed.error)}`, {
            threadId, flow: flow.name, step: failed.step, superstep: executedSupersteps - 1, seq: currentSeq,
            failure: { step: failed.step, code: failed.error?.code ?? 'flow_step_failed', message: message(failed.error) },
          }, failed.error);
        }
        const completed = outcomes.filter(outcome => outcome.result.interrupt === undefined);
        const interrupted = outcomes.filter(outcome => outcome.result.interrupt !== undefined);
        const next = applyWrites(current, completed.map(outcome => ({ source: outcome.step, writes: outcome.result.writes })), flow);
        const targets = [];
        for (const outcome of completed) targets.push(...await routeStep({ flow, threadId, outcome, state: next, superstep: executedSupersteps - 1, steps: flow.steps }));
        const ready2 = dedupe([...(pending ?? []), ...targets]);
        pending = undefined;

        if (interrupted.length > 0) {
          // The interrupted steps contribute nothing: their writes (they may carry none) are applied
          // when they re-run on resume, so nothing is counted twice. The completed siblings' routing
          // was decided at this boundary and is recorded with the pause.
          const awaiting = { steps: interrupted.map(outcome => outcome.step), values: interrupted.map(outcome => outcome.result.interrupt), next: ready2 };
          const boundary = await commit({ flow, threadId, seq: currentSeq + 1, state: next, status: 'paused', steps: currentReady, awaiting, bounds });
          announce({ flow, threadId, row: boundary, steps: currentReady, next: ready2 });
          for (const [index, outcome] of interrupted.entries()) {
            emitEvent({ type: 'flow.interrupt', threadId, flow: flow.name, step: outcome.step, superstep: executedSupersteps - 1, seq: boundary.seq, value: awaiting.values[index] });
          }
          const paused = { threadId, flow: flow.name, status: 'paused', seq: boundary.seq, supersteps: executedSupersteps, steps: executedSteps, state: next, awaiting };
          emitEvent({ type: 'flow.finished', ...paused });
          return paused;
        }

        const status = ready2.length === 0 ? 'done' : 'running';
        const boundary = await commit({ flow, threadId, seq: currentSeq + 1, state: next, status, steps: currentReady, next: ready2, awaiting: null, bounds });
        announce({ flow, threadId, row: boundary, steps: currentReady, next: ready2 });
        current = next;
        currentSeq = boundary.seq;
        if (status === 'done') {
          const finished = { threadId, flow: flow.name, status: 'done', seq: boundary.seq, supersteps: executedSupersteps, steps: executedSteps, state: next, awaiting: null };
          emitEvent({ type: 'flow.finished', ...finished });
          return finished;
        }
        currentReady = ready2;
      } catch (error) {
        // Whatever failed, the boundary that stands is the last one committed: a superstep's writes
        // are applied only after every one of its steps settled, so there is never a half-applied
        // state to unwind.
        const failure = failureOf(error);
        try {
          await store.fail({ threadId, seq: currentSeq, error: JSON.stringify(failure), updatedAt: now() });
        } catch { /* the run's own failure is the one worth reporting; a store that cannot record it cannot help */ }
        emitEvent({ type: 'flow.finished', threadId, flow: flow.name, status: 'failed', seq: currentSeq, supersteps: executedSupersteps, steps: executedSteps, state: current, awaiting: null, error: failure });
        throw error;
      }
    }
  }
}

const requireThreadId = threadId => {
  const id = typeof threadId === 'string' ? threadId.trim() : '';
  if (id === '') throw new FlowError('flow_unknown_thread', 'threadId required', { threadId: null });
  return id;
};

const checkCount = (value, where) => {
  if (!Number.isInteger(value) || value < 1) throw new FlowError('flow_invalid_limits', `${where}: limit must be a positive whole number`, { limit: where });
  return value;
};
