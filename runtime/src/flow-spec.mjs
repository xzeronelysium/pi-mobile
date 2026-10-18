// A flow the agent can AUTHOR: a plain JSON spec in the workspace, compiled into the engine that
// already exists (`flow.mjs`), checkpointed by the store that already exists (`flow-store.mjs`).
//
// WHY DATA, AND WHY SO SMALL
// The point is not a language. The point is that a person asks the agent for something repeatable,
// the agent writes a JSON file, and it runs — nobody builds anything by hand. A spec is therefore
// plain JSON: serializable into the transcript, diffable, inspectable in a checkpoint, and small
// enough that its whole shape fits in a tool description. There is no code, no imports and no
// expression language anywhere in it, and there cannot be: the module that reads a spec has no way
// to turn one into code (see the tests, which assert exactly that on this file's source).
//
// THE SHAPE, IN FULL
//   { version: 1, name, state: { field: { initial, reducer } }, entry, steps: { id: step }, limits? }
//   step    = { capability, args, save?, next?, onFailure? }
//   next    = "<step id>" | ["<step id>", …] | { if: <condition>, then: <transition>, else?: <transition> }
//   condition = { field, eq|ne|gt|gte|lt|lte|exists|contains|startsWith|in: <literal> }
//             | { all: [<condition>, …] } | { any: [<condition>, …] } | { not: <condition> }
//
// CONDITIONS ARE COMPARISONS, NOT EXPRESSIONS
// Every condition is one of ten comparisons over a state field, combined with all/any/not. That is
// the whole vocabulary, and it is deliberately not a general expression language: nothing a spec
// contains is ever parsed as code, and a "field path" is a lookup that returns "not there" rather
// than throwing. The common "if it worked do X, else Y" needs no cleverness at all — a step names
// its success branch and its failure branch and the engine routes on the step's own outcome
// (`last.ok`, the exit code, the HTTP status, the error).
//
// WHAT COMPILATION PRODUCES
// A definition the existing engine accepts, which the engine then owns: the superstep scheduler, one
// checkpoint per boundary, the step budget, the interrupts and the resume all come from `flow.mjs`.
// The compiler writes nothing of its own for any of that. Each compiled step runs ONE capability and
// writes down what it did into a reserved state field, `last`:
//   last = { step, ok, result, error }   — `result` is the capability's own JSON answer
// `save` copies named parts of that answer into the flow's own fields, so a later step can read
// them, and `next`/`onFailure` read the post-write state, exactly as the engine's own routers do.
//
// DETERMINISM AND RESUME
// A compiled flow is deterministic given the same spec and the same inputs: the only things that
// vary are what the world answers (a command's output, a file's contents, an HTTP reply, a person's
// answer), and those are exactly what the state records. An `ask` step pauses at a committed
// boundary; `resume(threadId, value)` re-runs ONLY that step with the answer and continues from the
// checkpoint, so a run that was interrupted does not start over.

import { createFlowRunner, defineFlow } from './flow.mjs';
import {
  ARGUMENT_KINDS,
  CAPABILITIES,
  CAPABILITY_ARGUMENTS,
  CAPABILITY_LIST,
  CAPABILITY_NAMES,
  invokeCapability,
} from './flow-capabilities.mjs';

export { CAPABILITY_NAMES };
export { CAPABILITY_LIST };

/** The only spec version this compiler reads. A newer spec is refused, never half-understood. */
export const SPEC_VERSION = 1;
/** The state field the engine writes every step's own outcome into. A spec may not declare it. */
export const SPEC_LAST_FIELD = 'last';
/** The most steps one spec may declare. A spec is a sequence a person can read, not a program. */
export const SPEC_MAX_STEPS = 50;
/** The most steps one RUN may execute, however many times the edges are followed. */
export const SPEC_MAX_RUN_STEPS = 200;
/** The most bytes one step's arguments may occupy. */
export const SPEC_MAX_ARG_BYTES = 16 * 1024;
/** The most bytes one spec file may occupy. */
export const SPEC_MAX_SPEC_BYTES = 256 * 1024;
/** The most bytes one state's checkpoint may occupy (the engine's default is below this). */
export const SPEC_MAX_STATE_BYTES = 1024 * 1024;

/** Field names a spec may not use: the engine's result keys, and the trace field the engine writes. */
export const SPEC_RESERVED_FIELDS = Object.freeze([SPEC_LAST_FIELD, 'update', 'goto', 'interrupt']);

/** The comparison operators, and the three combinators. This list IS the condition vocabulary. */
export const CONDITION_COMPARISONS = Object.freeze(['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'exists', 'contains', 'startsWith', 'in']);
export const CONDITION_COMBINATORS = Object.freeze(['all', 'any', 'not']);
export const CONDITION_OPERATORS = Object.freeze([...CONDITION_COMPARISONS, ...CONDITION_COMBINATORS]);

/** Every code this layer raises. A consumer can branch on the code and read the path to fix it. */
export const SPEC_ERROR_CODES = Object.freeze([
  'spec_not_object', 'spec_invalid_json', 'spec_version_unsupported', 'spec_name_invalid',
  'spec_state_invalid', 'spec_state_field_invalid', 'spec_state_reducer_unknown', 'spec_state_not_json', 'spec_state_reserved',
  'spec_entry_invalid', 'spec_unknown_step', 'spec_step_invalid', 'spec_unknown_capability',
  'spec_args_invalid', 'spec_args_too_large', 'spec_save_invalid', 'spec_state_field_unknown',
  'spec_transition_invalid', 'spec_condition_invalid', 'spec_condition_operator_unknown', 'spec_condition_value_invalid',
  'spec_unbounded_cycle', 'spec_too_many_steps', 'spec_limit_invalid', 'spec_too_large', 'spec_engine_rejected',
]);

/**
 * The one error a spec author sees. `code` is the machine's handle, `details.path` is the location
 * the message already names, and the message always says what was expected — a model's first spec
 * is wrong, and the text is the only teacher it has.
 */
export class SpecError extends Error {
  constructor(code, message, details = {}, cause) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'SpecError';
    this.code = code;
    this.details = { path: details.path ?? '', ...details };
  }
}

/** A step's own failure: it names the step, travels with the cause, and is never a generic error. */
export class StepError extends Error {
  constructor(code, message, cause) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'StepError';
    this.code = code;
  }
}

// --- small shared helpers ---------------------------------------------------------------------

const fail = (code, path, message, details = {}) => new SpecError(code, message, { path, ...details });

const isPlainObject = value => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
};

function describeType(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'an array';
  const type = typeof value;
  if (type === 'object') {
    const name = value?.constructor?.name;
    return typeof name === 'string' && name !== '' && name !== 'Object' ? `a ${name}` : 'an object';
  }
  if (type === 'number') return Number.isFinite(value) ? 'a number' : String(value);
  if (type === 'undefined') return 'undefined';
  return `a ${type}`;
}

const formatSegments = segments => segments.map(segment => (typeof segment === 'number' ? `[${segment}]` : segment)).join('.');

/** A field path inside a value, as a spec writes it: `a.b[0].c`. Returns null when it is not one. */
function parseSegments(text) {
  if (typeof text !== 'string' || !text.startsWith('$') && !/^[A-Za-z_]/.test(text)) return null;
  const body = text.startsWith('$') ? text.slice(1) : text;
  if (body === '') return null;
  const segments = [];
  for (const token of body.split('.')) {
    const match = /^([A-Za-z_][A-Za-z0-9_]*)((?:\[\d+\])*)$/.exec(token);
    if (match === null) return null;
    segments.push(match[1]);
    for (const index of match[2].matchAll(/\[(\d+)\]/g)) segments.push(Number(index[1]));
  }
  return segments;
}

/** Look a path up in a value. Not being there is an answer, never an exception. */
function walkPath(root, segments) {
  let current = root;
  for (const segment of segments) {
    if (current === null || current === undefined) return { found: false, value: undefined };
    if (typeof segment === 'number') {
      if (!Array.isArray(current) || segment >= current.length) return { found: false, value: undefined };
      current = current[segment];
      continue;
    }
    if (typeof current !== 'object' || Array.isArray(current) || !Object.prototype.hasOwnProperty.call(current, segment)) return { found: false, value: undefined };
    current = current[segment];
  }
  return { found: true, value: current };
}

/** JSON equality, by value: two objects with the same shape are equal, two references need not be. */
function deepEqual(left, right) {
  if (left === right) return true;
  if (left === null || right === null) return false;
  if (typeof left !== 'object' || typeof right !== 'object') return false;
  if (Array.isArray(left) !== Array.isArray(right)) return false;
  if (Array.isArray(left)) return left.length === right.length && left.every((entry, index) => deepEqual(entry, right[index]));
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  return leftKeys.length === rightKeys.length
    && leftKeys.every(key => Object.prototype.hasOwnProperty.call(right, key) && deepEqual(left[key], right[key]));
}

const deepCopy = value => (value === undefined ? undefined : JSON.parse(JSON.stringify(value)));

/** Walk a value the way JSON does, refusing anything JSON would drop or refuse. */
function assertJsonValue(value, path, code = 'spec_state_not_json') {
  const seen = new Set();
  const visit = (entry, at) => {
    if (entry === null || typeof entry === 'string' || typeof entry === 'boolean') return;
    if (typeof entry === 'number') {
      if (!Number.isFinite(entry)) throw fail(code, at, `${at}: ${entry} is not JSON; a spec holds plain JSON values only`);
      return;
    }
    if (typeof entry !== 'object') {
      throw fail(code, at, `${at}: ${describeType(entry)} is not JSON (JSON.stringify would drop it); a spec holds plain JSON values only`);
    }
    if (seen.has(entry)) throw fail(code, at, `${at}: this value is cyclic, which JSON cannot carry`);
    if (!isPlainObject(entry) && !Array.isArray(entry)) {
      throw fail(code, at, `${at}: ${describeType(entry)} is not JSON; a spec holds plain JSON values only`);
    }
    seen.add(entry);
    if (Array.isArray(entry)) entry.forEach((child, index) => visit(child, `${at}[${index}]`));
    else for (const [key, child] of Object.entries(entry)) visit(child, `${at}.${key}`);
    seen.delete(entry);
  };
  visit(value, path);
  return value;
}

// --- the compiler ------------------------------------------------------------------------------

/** A spec handed in as text is JSON, or it is refused as text. A spec handed in as a value is it. */
export function parseFlowSpec(value) {
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch (cause) {
    throw new SpecError('spec_invalid_json', `this is not JSON: ${cause?.message ?? String(cause)}`, { path: '' }, cause);
  }
}

/**
 * Compile one spec into a definition the existing engine runs. Everything a spec can get wrong is
 * caught here, with the location and what was expected, rather than in the middle of a run.
 */
export function compileFlowSpec(value) {
  const spec = parseFlowSpec(value);
  if (!isPlainObject(spec)) throw fail('spec_not_object', '', `a flow spec is a JSON object; this is ${describeType(spec)}`);

  // Size first: a spec is a thing a person reads and a checkpoint may carry, so it is bounded before
  // it is understood.
  let size;
  try {
    size = JSON.stringify(spec).length;
  } catch (cause) {
    throw new SpecError('spec_not_object', `a flow spec is JSON, and this one cannot be serialized: ${cause?.message ?? String(cause)}`, { path: '' }, cause);
  }
  if (size > SPEC_MAX_SPEC_BYTES) {
    throw fail('spec_too_large', '', `the spec is ${size} bytes, past the ${SPEC_MAX_SPEC_BYTES}-byte limit for one flow`);
  }

  if (spec.version !== SPEC_VERSION) {
    throw fail('spec_version_unsupported', 'version', `version: this runtime compiles flow spec version ${SPEC_VERSION}, not ${JSON.stringify(spec.version)}`);
  }

  const name = typeof spec.name === 'string' ? spec.name.trim() : '';
  if (name === '') throw fail('spec_name_invalid', 'name', `name: a flow spec needs a name (a non-empty string); got ${JSON.stringify(spec.name ?? null)}`);

  // --- state ---------------------------------------------------------------------------------
  const declared = spec.state ?? {};
  if (!isPlainObject(declared)) {
    throw fail('spec_state_invalid', 'state', `state: expected an object of fields, each { "initial": <value>, "reducer": "replace|append|merge" }; got ${describeType(declared)}`);
  }
  const state = {};
  for (const [field, raw] of Object.entries(declared)) {
    const at = `state.${field}`;
    if (SPEC_RESERVED_FIELDS.includes(field)) {
      throw fail('spec_state_reserved', at, `${at}: "${field}" is reserved (${SPEC_RESERVED_FIELDS.join(', ')}); ${field === SPEC_LAST_FIELD ? 'the engine writes it after every step' : 'the engine uses it for routing'}`);
    }
    if (!isPlainObject(raw)) {
      throw fail('spec_state_field_invalid', at, `${at}: expected { "initial": <value>, "reducer": "replace|append|merge" }; got ${describeType(raw)}`);
    }
    const extra = Object.keys(raw).filter(key => key !== 'initial' && key !== 'reducer');
    if (extra.length > 0) throw fail('spec_state_field_invalid', `${at}.${extra[0]}`, `${at}: unknown key "${extra[0]}" (a field has "initial" and, optionally, "reducer")`);
    if (!Object.prototype.hasOwnProperty.call(raw, 'initial')) {
      throw fail('spec_state_field_invalid', at, `${at}: "initial" is missing; every field starts with a value`);
    }
    assertJsonValue(raw.initial, `${at}.initial`);
    const reducer = raw.reducer ?? 'replace';
    if (typeof reducer !== 'string' || !['replace', 'append', 'merge'].includes(reducer)) {
      throw fail('spec_state_reducer_unknown', `${at}.reducer`, `${at}.reducer: ${JSON.stringify(reducer)} is not a reducer (available: replace, append, merge)`);
    }
    state[field] = { initial: deepCopy(raw.initial), reducer };
  }
  const fields = [...Object.keys(state), SPEC_LAST_FIELD];
  const fieldList = fields.join(', ');
  const knownField = field => Object.prototype.hasOwnProperty.call(state, field) || field === SPEC_LAST_FIELD;

  // --- steps ---------------------------------------------------------------------------------
  if (!isPlainObject(spec.steps) || Object.keys(spec.steps).length === 0) {
    throw fail('spec_step_invalid', 'steps', `steps: expected an object with at least one step; got ${describeType(spec.steps)}`);
  }
  const stepIds = Object.keys(spec.steps);
  if (stepIds.length > SPEC_MAX_STEPS) {
    throw fail('spec_too_many_steps', 'steps', `the spec declares ${stepIds.length} steps, past the ${SPEC_MAX_STEPS}-step limit for one flow`);
  }

  const compiled = {};
  for (const [id, raw] of Object.entries(spec.steps)) {
    const at = `steps.${id}`;
    if (!isPlainObject(raw)) {
      throw fail('spec_step_invalid', at, `${at}: expected an object with "capability" and "args" (and optionally "save", "next", "onFailure"); got ${describeType(raw)}`);
    }
    for (const key of Object.keys(raw)) {
      if (!['capability', 'args', 'save', 'next', 'onFailure'].includes(key)) {
        throw fail('spec_step_invalid', `${at}.${key}`, `${at}.${key}: unknown step key "${key}" (allowed: capability, args, save, next, onFailure)`);
      }
    }
    if (typeof raw.capability !== 'string' || !CAPABILITY_NAMES.includes(raw.capability)) {
      if (raw.capability === undefined) {
        throw fail('spec_step_invalid', `${at}.capability`, `${at}.capability: "capability" is missing; every step names what it does (available: ${CAPABILITY_LIST})`);
      }
      throw fail('spec_unknown_capability', `${at}.capability`, `${at}.capability: ${JSON.stringify(raw.capability)} is not a capability (available: ${CAPABILITY_LIST})`);
    }
    const capability = raw.capability;
    const rules = CAPABILITY_ARGUMENTS[capability];
    const allowedNames = Object.keys(rules);

    if (raw.args !== undefined && !isPlainObject(raw.args)) {
      throw fail('spec_args_invalid', `${at}.args`, `${at}.args: expected an object of arguments for the ${capability} capability; got ${describeType(raw.args)}`);
    }
    const given = raw.args ?? {};
    const givenSize = JSON.stringify(given).length;
    if (givenSize > SPEC_MAX_ARG_BYTES) {
      throw fail('spec_args_too_large', `${at}.args`, `${at}.args: the serialized arguments are ${givenSize} bytes, past the ${SPEC_MAX_ARG_BYTES}-byte limit for one step`);
    }
    for (const key of Object.keys(given)) {
      if (!allowedNames.includes(key)) {
        throw fail('spec_args_invalid', `${at}.args.${key}`, `${at}.args.${key}: unknown argument for the ${capability} capability (allowed: ${allowedNames.join(', ')})`);
      }
    }
    const args = {};
    for (const [argName, rule] of Object.entries(rules)) {
      const argAt = `${at}.args.${argName}`;
      const argument = given[argName];
      if (argument === undefined) {
        if (rule.required === true) throw fail('spec_args_invalid', `${at}.args`, `${at}.args: "${argName}" is required for the ${capability} capability`);
        continue;
      }
      const argumentPath = `${at}.args.${argName}`;
      args[argName] = checkArgument(argument, argumentPath, rule.kind, capability);
      // A state reference is checked by the field it reads: a spec cannot name a field that is not
      // there, so this is a compile-time error rather than a run that fails halfway through.
      if (typeof args[argName] === 'string' && args[argName].startsWith('$')) {
        const root = parseSegments(args[argName])[0];
        if (!knownField(root)) {
          throw fail('spec_state_field_unknown', argumentPath, `${argumentPath}: ${JSON.stringify(args[argName])} reads state field "${root}", which does not exist; this flow declares ${fieldList}`);
        }
      }
    }

    if (raw.save !== undefined && !isPlainObject(raw.save)) {
      throw fail('spec_save_invalid', `${at}.save`, `${at}.save: expected an object mapping state fields to names in the step's result; got ${describeType(raw.save)}`);
    }
    const save = [];
    for (const [field, target] of Object.entries(raw.save ?? {})) {
      const saveAt = `${at}.save.${field}`;
      if (!knownField(field)) throw fail('spec_state_field_unknown', saveAt, `${saveAt}: ${JSON.stringify(field)} is not a state field; this flow declares ${fieldList}`);
      if (typeof target !== 'string' || target.trim() === '') {
        throw fail('spec_save_invalid', saveAt, `${saveAt}: expected the name of a part of the step's result to save; got ${describeType(target)}`);
      }
      save.push([field, target.split('.').flatMap(token => {
        const match = /^([A-Za-z_][A-Za-z0-9_]*)((?:\[\d+\])*)$/.exec(token);
        if (match === null) return [token];
        const segments = [match[1]];
        for (const index of match[2].matchAll(/\[(\d+)\]/g)) segments.push(Number(index[1]));
        return segments;
      })]);
    }

    const step = {
      id,
      capability,
      arguments: deepCopy(args),
      save,
      next: raw.next === undefined ? null : compileTransition(raw.next, `${at}.next`, fields, spec.steps),
      onFailure: raw.onFailure === undefined ? null : compileTransition(raw.onFailure, `${at}.onFailure`, fields, spec.steps),
    };
    if (step.onFailure !== null && CAPABILITIES[capability].fails !== true) {
      throw fail('spec_step_invalid', `${at}.onFailure`, `${at}.onFailure: a ${capability} step cannot fail, so it has no failure branch to take`);
    }
    compiled[id] = step;
  }

  // --- entry -----------------------------------------------------------------------------------
  if (spec.entry === undefined) throw fail('spec_entry_invalid', 'entry', 'entry: expected the step this flow starts at');
  const entry = Array.isArray(spec.entry) ? spec.entry : [spec.entry];
  if (entry.length === 0) throw fail('spec_entry_invalid', 'entry', 'entry: expected at least one step to start at');
  for (const step of entry) {
    if (typeof step !== 'string' || !Object.prototype.hasOwnProperty.call(spec.steps, step)) {
      throw fail('spec_unknown_step', 'entry', `entry: ${JSON.stringify(step)} is not a step of this flow (steps: ${stepIds.join(', ')})`);
    }
  }

  // --- can it run forever? ----------------------------------------------------------------------
  // A cycle of edges that nothing can divert is a run that never ends, so it is refused outright.
  // A cycle that passes through a conditional edge (a conditional `next`, or a failure branch) CAN
  // leave it, and is allowed: the engine's own step budget is the backstop for the rest.
  const edges = new Map();
  for (const [id, step] of Object.entries(compiled)) {
    if (step.next !== null && step.next.conditional) continue;
    if (step.onFailure !== null) continue;
    edges.set(id, step.next === null ? [] : [...step.next.names]);
  }
  const cycle = findCycle(edges);
  if (cycle !== null) {
    throw fail('spec_unbounded_cycle', `steps.${cycle[0]}.next`, `the steps ${cycle.join(' → ')} route to each other with no condition, so the run could never end; give one of them a conditional next (or a failure branch) it can leave by`, { cycle });
  }

  // --- limits -----------------------------------------------------------------------------------
  let limits = null;
  if (spec.limits !== undefined) {
    if (!isPlainObject(spec.limits)) throw fail('spec_limit_invalid', 'limits', `limits: expected an object with "maxSteps" and/or "maxStateBytes"; got ${describeType(spec.limits)}`);
    for (const key of Object.keys(spec.limits)) {
      if (key !== 'maxSteps' && key !== 'maxStateBytes') throw fail('spec_limit_invalid', `limits.${key}`, `limits.${key}: unknown limit (available: maxSteps, maxStateBytes)`);
    }
    limits = {};
    const declaredSteps = spec.limits.maxSteps;
    if (declaredSteps !== undefined) {
      if (!Number.isInteger(declaredSteps) || declaredSteps < 1) throw fail('spec_limit_invalid', 'limits.maxSteps', `limits.maxSteps: expected a positive whole number of steps; got ${JSON.stringify(declaredSteps)}`);
      if (declaredSteps > SPEC_MAX_RUN_STEPS) throw fail('spec_limit_invalid', 'limits.maxSteps', `limits.maxSteps: ${declaredSteps} is past the ${SPEC_MAX_RUN_STEPS}-step ceiling for one run`);
      limits.maxSteps = declaredSteps;
    }
    const declaredBytes = spec.limits.maxStateBytes;
    if (declaredBytes !== undefined) {
      if (!Number.isInteger(declaredBytes) || declaredBytes < 1024) throw fail('spec_limit_invalid', 'limits.maxStateBytes', `limits.maxStateBytes: expected a whole number of bytes, at least 1024; got ${JSON.stringify(declaredBytes)}`);
      if (declaredBytes > SPEC_MAX_STATE_BYTES) throw fail('spec_limit_invalid', 'limits.maxStateBytes', `limits.maxStateBytes: ${declaredBytes} is past the ${SPEC_MAX_STATE_BYTES}-byte ceiling for one state`);
      limits.maxStateBytes = declaredBytes;
    }
  }

  // --- the definition the engine takes ----------------------------------------------------------
  const steps = {};
  const transitions = {};
  for (const [id, step] of Object.entries(compiled)) {
    steps[id] = createStepFunction(step);
    if (step.next !== null || step.onFailure !== null) transitions[id] = createRouter(step);
  }
  const definition = {
    name,
    state: { ...state, [SPEC_LAST_FIELD]: { initial: null, reducer: 'replace' } },
    entry: [...entry],
    steps,
    transitions,
    ...(limits === null ? {} : { limits }),
  };
  // The engine's own `defineFlow` is the last word: if it refuses anything, the spec is wrong, and
  // the caller is told so as a spec problem rather than as a crash in the middle of a run.
  try {
    return defineFlow(definition);
  } catch (cause) {
    throw new SpecError('spec_engine_rejected', `the engine refused the compiled flow: ${cause?.message ?? String(cause)}`, { path: '' }, cause);
  }
}

/** Compile every spec a caller has, so one broken spec cannot hide the others behind it. */
export function compileFlowSpecs(specs) {
  if (!Array.isArray(specs)) throw new TypeError('compileFlowSpecs takes a list of specs');
  return specs.map(compileFlowSpec);
}

/**
 * The engine's own runner, over specs instead of definitions: everything else is `createFlowRunner`
 * — the same scheduler, the same store, the same frames.
 */
export function createFlowSpecRunner({ specs, ...options } = {}) {
  return createFlowRunner({ ...options, flows: compileFlowSpecs(specs ?? []) });
}

// --- arguments ---------------------------------------------------------------------------------

/** Check one argument against its kind: a state reference is checked by its root instead. */
function checkArgument(value, at, kind, capability) {
  if (typeof value === 'string' && value.startsWith('$')) {
    const segments = parseSegments(value);
    if (segments === null) {
      throw fail('spec_args_invalid', at, `${at}: ${JSON.stringify(value)} is not a state reference; a string that starts with "$" is read as one (write it as "$field" or "$field.path")`);
    }
    return value;
  }
  if (ARGUMENT_KINDS[kind].check(value) !== null) {
    throw fail('spec_args_invalid', at, `${at}: the ${capability} capability takes ${ARGUMENT_KINDS[kind].describe}; got ${JSON.stringify(value ?? null)}`);
  }
  return value;
}

/** Resolve a step's arguments against the state: a "$field.path" string is replaced by its value. */
function resolveArguments(step, state) {
  const resolve = (value, at) => {
    if (typeof value === 'string' && value.startsWith('$')) {
      const segments = parseSegments(value);
      const found = walkPath(state, segments);
      if (!found.found) {
        throw new StepError('spec_reference_unresolved', `step ${step.id}: args.${at} ${JSON.stringify(value)}: there is nothing at state.${formatSegments(segments)}`);
      }
      return found.value;
    }
    if (Array.isArray(value)) return value.map((entry, index) => resolve(entry, `${at}[${index}]`));
    if (isPlainObject(value)) {
      const object = {};
      for (const [key, entry] of Object.entries(value)) object[key] = resolve(entry, `${at}.${key}`);
      return object;
    }
    return value;
  };
  const resolved = {};
  for (const [key, value] of Object.entries(step.arguments)) resolved[key] = resolve(value, key);
  return resolved;
}

// --- steps and routers -------------------------------------------------------------------------

/** One capability run, and the record of it. The step's own outcome is what the router reads. */
function createStepFunction(step) {
  return async function flowSpecStep(state, context) {
    // Resolved OUTSIDE the try: a reference with nothing to read is the step's own failure, and it
    // keeps its own code so the report says which argument and which path, not "the capability failed".
    const args = resolveArguments(step, state);
    let outcome;
    try {
      const raw = await invokeCapability(step.capability, args, { ...context, state });
      // An `ask` step pauses here and writes nothing: the engine commits the boundary and records
      // what it awaits, and the step is re-run on resume with the answer.
      if (raw.interrupt !== undefined) return { interrupt: raw.interrupt };
      outcome = { ok: raw.ok === true, result: raw.result ?? {}, error: raw.error ?? null, cause: undefined };
    } catch (cause) {
      outcome = {
        ok: false,
        result: {},
        error: { code: codeFor(cause, 'capability_error'), message: `${step.capability}: ${cause?.message ?? String(cause)}` },
        cause,
      };
    }
    const writes = {
      [SPEC_LAST_FIELD]: { step: step.id, ok: outcome.ok, result: outcome.result, error: outcome.error },
    };
    for (const [field, segments] of step.save) {
      const found = walkPath(outcome.result, segments);
      if (!found.found) {
        throw new StepError('spec_save_unresolved', `step ${step.id}: the ${step.capability} result has no ${formatSegments(segments)} to save into ${field} (the result was ${JSON.stringify(outcome.result)})`);
      }
      writes[field] = found.value;
    }
    // No failure branch declared: the failure IS the run's failure, and the engine reports it with
    // this step's name and this message rather than a generic one.
    if (!outcome.ok && step.onFailure === null) {
      throw new StepError('spec_step_failed', `step ${step.id}: ${outcome.error?.message ?? 'the step failed'}`, outcome.cause);
    }
    return writes;
  };
}

/** Success goes to `next`, a bad outcome goes to `onFailure` (which is why "if it worked" is easy). */
function createRouter(step) {
  return (state, context) => {
    const record = context?.writes?.[SPEC_LAST_FIELD];
    const failed = isPlainObject(record) && record.ok === false;
    const chosen = failed ? step.onFailure : step.next;
    if (chosen === null) return undefined;
    return chosen.evaluate(state);
  };
}

const codeFor = (error, fallback) => (typeof error?.code === 'string' && error.code !== '' ? error.code : fallback);

// --- transitions and conditions ------------------------------------------------------------------

/** A transition: a step, a list of steps, or `{ if, then, else }`. Names are checked once, here. */
function compileTransition(value, at, fields, steps) {
  if (typeof value === 'string' || Array.isArray(value)) {
    const names = compileTargets(value, at, steps);
    return { conditional: false, names, evaluate: () => names };
  }
  if (isPlainObject(value)) {
    if (!('if' in value)) {
      throw fail('spec_transition_invalid', at, `${at}: expected a step id, a list of step ids, or { "if": <condition>, "then": <step>, "else": <step> }; got an object without "if"`);
    }
    for (const key of Object.keys(value)) {
      if (!['if', 'then', 'else'].includes(key)) throw fail('spec_transition_invalid', `${at}.${key}`, `${at}.${key}: unknown key "${key}" in a conditional transition (allowed: if, then, else)`);
    }
    if (!('then' in value)) throw fail('spec_transition_invalid', at, `${at}: a conditional transition needs "then" (and may have "else")`);
    const condition = compileCondition(value.if, `${at}.if`, fields);
    const thenNames = compileTargets(value.then, `${at}.then`, steps);
    const elseNames = 'else' in value ? compileTargets(value.else, `${at}.else`, steps) : [];
    return { conditional: true, names: null, evaluate: state => (condition(state) === true ? thenNames : elseNames) };
  }
  throw fail('spec_transition_invalid', at, `${at}: expected a step id, a list of step ids, or { "if": <condition>, "then": <step>, "else": <step> }; got ${describeType(value)}`);
}

function compileTargets(value, at, steps) {
  const list = Array.isArray(value) ? value : [value];
  if (list.length === 0) throw fail('spec_transition_invalid', at, `${at}: a transition needs at least one step to go to`);
  for (const target of list) {
    if (typeof target !== 'string' || !Object.prototype.hasOwnProperty.call(steps, target)) {
      throw fail('spec_unknown_step', at, `${at}: ${JSON.stringify(target)} is not a step of this flow (steps: ${Object.keys(steps).join(', ')})`);
    }
  }
  return [...list];
}

const CONDITION_LIST = CONDITION_OPERATORS.join(', ');

/** Compile a condition into a predicate over the state. Comparisons only; nothing is ever evaluated. */
function compileCondition(node, at, fields) {
  if (!isPlainObject(node)) {
    throw fail('spec_condition_invalid', at, `${at}: expected a condition (a comparison, or all/any/not); got ${describeType(node)}`);
  }
  const combinator = CONDITION_COMBINATORS.find(key => key in node);
  if (combinator !== undefined) {
    for (const key of Object.keys(node)) {
      if (key !== combinator) throw fail('spec_condition_invalid', `${at}.${key}`, `${at}: "${combinator}" is combined with "${key}"; a condition carries one operator`);
    }
    const value = node[combinator];
    if (combinator === 'not') {
      // Compiled now, not on every evaluation: a nested mistake is a compile-time error.
      const child = compileCondition(value, `${at}.not`, fields);
      return state => child(state) !== true;
    }
    if (!Array.isArray(value) || value.length === 0) {
      throw fail('spec_condition_invalid', `${at}.${combinator}`, `${at}.${combinator}: "${combinator}" needs at least one condition to combine`);
    }
    const parts = value.map((child, index) => compileCondition(child, `${at}.${combinator}[${index}]`, fields));
    return combinator === 'all'
      ? state => parts.every(part => part(state) === true)
      : state => parts.some(part => part(state) === true);
  }

  const keys = Object.keys(node);
  const operators = keys.filter(key => key !== 'field');
  if (operators.length === 0) {
    throw fail('spec_condition_invalid', at, `${at}: a condition needs one operator (${CONDITION_LIST}); none was given`);
  }
  const unknown = operators.find(key => !CONDITION_COMPARISONS.includes(key));
  if (unknown !== undefined) {
    throw fail('spec_condition_operator_unknown', at, `${at}: "${unknown}" is not a condition operator (available: ${CONDITION_LIST})`);
  }
  if (operators.length > 1) {
    throw fail('spec_condition_invalid', at, `${at}: a condition carries exactly one operator, and "${operators[0]}" and "${operators[1]}" were both given`);
  }
  if (!('field' in node)) {
    throw fail('spec_condition_invalid', at, `${at}: a condition needs "field", the state field it reads`);
  }
  const operator = operators[0];
  const field = node.field;
  if (typeof field !== 'string' || field.trim() === '') {
    throw fail('spec_condition_invalid', `${at}.field`, `${at}.field: expected the name of a state field; got ${describeType(field)}`);
  }
  const segments = parseSegments(field);
  if (segments === null) {
    throw fail('spec_condition_invalid', `${at}.field`, `${at}.field: ${JSON.stringify(field)} is not a field path (write it as "field" or "field.path")`);
  }
  if (!fields.includes(segments[0])) {
    throw fail('spec_state_field_unknown', `${at}.field`, `${at}.field: ${JSON.stringify(field)} reads ${segments[0]}, which is not a state field; this flow declares ${fields.join(', ')}`);
  }

  const literal = node[operator];
  return compileComparison(operator, segments, literal, `${at}.${operator}`);
}

function compileComparison(operator, segments, literal, at) {
  const read = state => walkPath(state, segments);
  switch (operator) {
    case 'eq':
    case 'ne': {
      assertJsonValue(literal, at, 'spec_condition_value_invalid');
      const wanted = deepCopy(literal);
      return operator === 'eq'
        ? state => { const found = read(state); return found.found && deepEqual(found.value, wanted); }
        // A field that is not there is not equal to anything, so `ne` is true for it: absence is an
        // answer, and a spec should not have to write `exists: false or ne: …` to say so.
        : state => { const found = read(state); return !found.found || !deepEqual(found.value, wanted); };
    }
    case 'gt':
    case 'gte':
    case 'lt':
    case 'lte': {
      if (typeof literal !== 'number' || !Number.isFinite(literal)) {
        throw fail('spec_condition_value_invalid', at, `${at}: "${operator}" compares against a number; got ${JSON.stringify(literal ?? null)}`);
      }
      return state => {
        const found = read(state);
        // Only numbers compare: a string where a number was expected is simply not greater than it.
        if (!found.found || typeof found.value !== 'number' || !Number.isFinite(found.value)) return false;
        if (operator === 'gt') return found.value > literal;
        if (operator === 'gte') return found.value >= literal;
        if (operator === 'lt') return found.value < literal;
        return found.value <= literal;
      };
    }
    case 'exists': {
      if (typeof literal !== 'boolean') {
        throw fail('spec_condition_value_invalid', at, `${at}: "exists" is true or false; got ${JSON.stringify(literal ?? null)}`);
      }
      // Absent and null are the same answer: a field a step has not filled yet is not there.
      return state => {
        const found = read(state);
        const present = found.found && found.value !== null;
        return literal === true ? present : !present;
      };
    }
    case 'contains':
    case 'startsWith': {
      if (typeof literal !== 'string') {
        throw fail('spec_condition_value_invalid', at, `${at}: "${operator}" looks inside a string; got ${JSON.stringify(literal ?? null)}`);
      }
      return operator === 'contains'
        ? state => { const found = read(state); return found.found && typeof found.value === 'string' && found.value.includes(literal); }
        : state => { const found = read(state); return found.found && typeof found.value === 'string' && found.value.startsWith(literal); };
    }
    case 'in': {
      if (!Array.isArray(literal) || literal.length === 0) {
        throw fail('spec_condition_value_invalid', at, `${at}: "in" takes a list of values to be one of; got ${describeType(literal)}`);
      }
      literal.forEach((entry, index) => assertJsonValue(entry, `${at}[${index}]`, 'spec_condition_value_invalid'));
      const wanted = deepCopy(literal);
      return state => { const found = read(state); return found.found && wanted.some(entry => deepEqual(found.value, entry)); };
    }
    default:
      throw fail('spec_condition_operator_unknown', at, `${at}: "${operator}" is not a condition operator (available: ${CONDITION_LIST})`);
  }
}

/** A cycle among edges nothing can divert, or null. The first one found, in declaration order. */
function findCycle(edges) {
  const colour = new Map();
  const path = [];
  const visit = node => {
    colour.set(node, 'open');
    path.push(node);
    for (const next of edges.get(node) ?? []) {
      if (colour.get(next) === 'open') return [...path.slice(path.indexOf(next)), next];
      if (colour.has(next)) continue;
      const found = visit(next);
      if (found !== null) return found;
    }
    path.pop();
    colour.set(node, 'closed');
    return null;
  };
  for (const node of edges.keys()) {
    if (colour.has(node)) continue;
    const found = visit(node);
    if (found !== null) return found;
  }
  return null;
}

// --- the example, and the vocabulary a tool description is written from -------------------------

/**
 * One complete spec: fetch a URL, branch on the step's own outcome, run a command through the
 * interpreter, and write down what happened if it did not work. Every construct the format has is
 * here, and the test suite compiles AND runs this value, so it cannot drift from the compiler.
 */
export const FLOW_SPEC_EXAMPLE = Object.freeze({
  version: SPEC_VERSION,
  name: 'check-endpoint',
  state: {
    url: { initial: 'https://example.com/health' },
    status: { initial: null },
    body: { initial: null },
  },
  entry: 'fetch',
  steps: {
    fetch: {
      capability: 'fetch_url',
      args: { url: '$url' },
      save: { status: 'status', body: 'body' },
      next: { if: { field: 'last.ok', eq: true }, then: 'run', else: 'fail' },
    },
    run: {
      capability: 'shell',
      args: { command: ['/system/bin/sh', '-c', "printf 'checked'"] },
      onFailure: 'fail',
    },
    fail: {
      capability: 'write_file',
      args: { path: 'check-failed.txt', text: 'the endpoint did not answer' },
    },
  },
  limits: { maxSteps: 12 },
});

/** What a capability takes, as a tool description prints it: one line each, in the spec's words. */
export function describeCapabilities() {
  return CAPABILITY_NAMES.map(name => ({
    name,
    does: CAPABILITIES[name].describe,
    canFail: CAPABILITIES[name].fails === true,
    arguments: Object.entries(CAPABILITY_ARGUMENTS[name]).map(([argument, rule]) => ({ argument, takes: ARGUMENT_KINDS[rule.kind].describe, required: rule.required === true })),
  }));
}

/** The condition vocabulary, as a list of `{ operator, over }`. */
export function describeConditions() {
  return [
    { operator: 'eq / ne', over: 'any value: equal to, or not equal to (a field that is not there is not equal to anything)' },
    { operator: 'gt / gte / lt / lte', over: 'numbers' },
    { operator: 'exists', over: 'true or false: the field is there (null counts as not there)' },
    { operator: 'contains / startsWith', over: 'strings' },
    { operator: 'in', over: 'a list: the field is one of these values' },
    { operator: 'all / any', over: 'a list of conditions' },
    { operator: 'not', over: 'one condition' },
  ];
}

/** The shape of a spec, as a tool description prints it. */
export function describeFormat() {
  return [
    'A flow spec is one JSON file. { "version": 1, "name": "<flow>", "state": { "<field>": { "initial": <json>, "reducer": "replace|append|merge" } }, "entry": "<step>", "steps": { "<step>": <step> }, "limits": { "maxSteps": <n> } }',
    'A step is { "capability": "<name>", "args": { … }, "save": { "<state field>": "<part of the step result>" }, "next": <transition>, "onFailure": <transition> }.',
    `Capabilities: ${CAPABILITY_NAMES.join(', ')}.`,
    'A transition is "<step>", ["<step>", …], or { "if": <condition>, "then": <transition>, "else": <transition> }.',
    'A condition is { "field": "<state field or last.result.…>", "eq|ne|gt|gte|lt|lte|exists|contains|startsWith|in": <literal> }, or { "all"|"any": [<condition>, …] }, or { "not": <condition> }.',
    'After every step the engine writes the step\'s own outcome to the state field `last`: { "step", "ok", "result", "error" }. `next` is taken when ok is true, `onFailure` when it is false.',
    'An argument value that is the string "$field.path" is read from the state before the step runs.',
  ];
}
