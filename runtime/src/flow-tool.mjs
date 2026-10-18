// The agent's handle on flows: discovery, execution and resume, as durable tools.
//
// WHY THIS EXISTS
// A flow is data the agent writes into its own workspace (`.pi-flows/<name>.json`, compiled by
// `flow-spec.mjs`). Authoring therefore needs no new capability — the agent already writes files —
// but *finding* and *running* one does: without these tools the agent can write a spec and then has
// no way to list it, run it, answer a paused step or read a thread back. That is the whole gap this
// module closes, and it closes it through the SAME runner the host already built, so a spec run
// here is resumable through the host exactly like a shipped flow.
//
// WHAT THE DESCRIPTIONS HAVE TO DO
// A model's first spec is wrong, and its only teacher is the text it is given. So every description
// states the format compactly — the example shape, the operator list, the reserved `last` field and
// the `$field` substitution rule — and says plainly that an `ask` step pauses the flow. And when a
// spec does not compile, the compiler's own message is handed back unchanged: fixing the spec from
// that message is the entire authoring loop, so nothing here paraphrases or swallows it.
//
// The whole module runs in the WebView: no Node API, only `@earendil-works/pi-durable` and the
// vocabularies `flow-spec.mjs` already exports.

import { defineExtension, defineTool } from '@earendil-works/pi-durable';
import { CAPABILITY_NAMES, describeCapabilities, describeConditions, describeFormat, FLOW_SPEC_EXAMPLE } from './flow-spec.mjs';
import { FLOW_SPEC_DIR } from './flow-specs.mjs';

/** The extension that carries the flow tools; installed alongside the coding tools and selected by default. */
export const FLOW_EXTENSION_NAME = 'flows';

/** The tool names. */
export const LIST_FLOWS_TOOL_NAME = 'list_flows';
export const RUN_FLOW_TOOL_NAME = 'run_flow';
export const RESUME_FLOW_TOOL_NAME = 'resume_flow';
export const FLOW_STATUS_TOOL_NAME = 'flow_status';

/** Every tool this extension offers, in the order it is described. */
export const FLOW_TOOL_NAMES = Object.freeze([LIST_FLOWS_TOOL_NAME, RUN_FLOW_TOOL_NAME, RESUME_FLOW_TOOL_NAME, FLOW_STATUS_TOOL_NAME]);

/** A refusal the model should read: `code` is the module's, and the message says what to do instead. */
export class FlowToolError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'FlowToolError';
    this.code = code;
  }
}

// The format, assembled from the vocabularies the compiler itself enforces — so the description a
// model reads and the rules it is judged by cannot drift apart.
const OPERATORS = describeConditions().map(entry => `${entry.operator} over ${entry.over}`).join('; ');
const CAPABILITIES = describeCapabilities()
  .map(entry => `${entry.name} (${entry.does}${entry.canFail ? ', can fail' : ', cannot fail'})`)
  .join('; ');

const FORMAT_HELP = 'A flow is one JSON spec the agent writes into its workspace at '
  + `.${FLOW_SPEC_DIR}/<name>.json and then runs by the name the spec declares. `
  + `Spec shape: ${describeFormat().join(' ')} `
  + `Capabilities: ${CAPABILITIES}. `
  + `Condition operators: ${OPERATORS}. `
  + 'After every step the reserved state field "last" holds that step\'s own outcome '
  + '{ step, ok, result, error }; "next" is taken when ok is true and "onFailure" when it is false. '
  + 'An argument value that is the string "$field.path" is read from the flow state before the step runs. '
  + 'An "ask" step PAUSES the whole run and waits for a person: a flow that reaches one does not finish '
  + 'on its own, it comes back paused, and it is continued with resume_flow. '
  + `A complete example spec: ${JSON.stringify(FLOW_SPEC_EXAMPLE)}`;

/** The same format, in one line, for the two tools whose job is not authoring. */
const FORMAT_POINTER = `Flows are the agent-authored JSON specs under <workspace>/.${FLOW_SPEC_DIR}/<name>.json; `
  + `the format, the capability list (${CAPABILITY_NAMES.join(', ')}) and the reserved "last" field are described by ${LIST_FLOWS_TOOL_NAME} and ${RUN_FLOW_TOOL_NAME}.`;

const textOf = parts => parts.filter(Boolean).join('\n');

const textResult = (text, details) => ({ content: [{ type: 'text', text }], ...(details === undefined ? {} : { details }) });
const errorResult = (text, details) => ({ content: [{ type: 'text', text }], isError: true, ...(details === undefined ? {} : { details }) });

/** The spec errors of a discovery, verbatim, one per line. Nothing is paraphrased or dropped. */
const errorLines = errors => (errors ?? []).map(error => `- ${error.message}`);

/** How a run's summary reads back: enough to continue it, and its state, which a spec keeps small. */
function renderRun(summary) {
  const awaiting = summary?.awaiting;
  const paused = summary?.status === 'paused' && awaiting !== undefined && awaiting !== null;
  const lines = [
    `flow "${summary.flow}" ${summary.status} (thread ${summary.threadId}, seq ${summary.seq})`,
    `steps: ${(summary.steps ?? []).join(', ') || '(none)'}`,
  ];
  if (paused) {
    lines.push(`paused on: ${(awaiting.steps ?? []).join(', ')} — awaiting a person`);
    for (const [index, step] of (awaiting.steps ?? []).entries()) {
      const value = awaiting.values?.[index];
      if (value !== undefined) lines.push(`  ${step} is waiting for: ${JSON.stringify(value)}`);
    }
    lines.push(`continue it with ${RESUME_FLOW_TOOL_NAME} and threadId ${summary.threadId}`);
  }
  lines.push(`state: ${JSON.stringify(summary.state ?? {})}`);
  return lines.join('\n');
}

/** What the model needs to name a flow again, and nothing it does not. */
const runDetails = summary => ({
  flow: summary.flow,
  threadId: summary.threadId,
  status: summary.status,
  seq: summary.seq,
  steps: [...(summary.steps ?? [])],
  awaiting: summary.awaiting ?? null,
  state: summary.state ?? {},
});

/**
 * The tools.
 *
 * `runner` is read per call (the host may rebuild its runner when the workspace's spec set changes,
 * and `resume` resolves a thread's flow BY NAME from that registry — so a re-read is what makes a
 * spec the agent just wrote runnable AND resumable). `refresh` re-reads `<workspace>/.pi-flows` and
 * returns what it found, so a freshly authored spec is picked up without restarting the runtime.
 * Both are functions because the host installs this extension before a workspace exists.
 */
export function createFlowTools({ runner, refresh } = {}) {
  if (typeof runner !== 'function') throw new TypeError('createFlowTools requires a runner accessor');
  if (typeof refresh !== 'function') throw new TypeError('createFlowTools requires a spec refresh function');

  /** Reload the workspace's specs, then hand back the live runner — or refuse if there is none. */
  const live = async () => {
    const discovery = (await refresh()) ?? { dir: '', specs: [], errors: [] };
    const active = runner();
    if (!active) throw new FlowToolError('not_initialized', 'The runtime is not initialized yet, so its flows cannot be run; initialize it first.');
    return { discovery, active };
  };

  /** A name the runner cannot resolve: say so, name what it can run, and show every spec error verbatim. */
  const unknown = (name, discovery, active) => {
    const known = active.flows().map(flow => flow.name);
    const lines = [`No flow named "${name}" is available.`,
      `flows this runtime can run: ${known.length === 0 ? '(none)' : known.join(', ')}`];
    const errors = errorLines(discovery.errors);
    if (errors.length > 0) lines.push(`specs that could not be loaded (fix the file, then try again):`, ...errors);
    else lines.push(`no spec files were found under ${discovery.dir}; write one there and try again`);
    return errorResult(textOf(lines), { flow: name, known, errors: (discovery.errors ?? []).map(error => ({ path: error.path, code: error.code, message: error.message })) });
  };

  /** Run whatever the engine threw as itself: its message already names the flow, the step and the cause. */
  const executeRun = async (work) => {
    try {
      return await work();
    } catch (error) {
      if (error instanceof FlowToolError) throw error;
      throw error;
    }
  };

  return defineExtension({
    name: FLOW_EXTENSION_NAME,
    tools: [
      defineTool({
        name: LIST_FLOWS_TOOL_NAME,
        description: `List the flows this runtime can run, and the agent-authored specs under the workspace. ${FORMAT_HELP} `
          + `Each spec file that could not be compiled is reported with the compiler's own message, unchanged, so it can be fixed.`,
        parameters: {
          type: 'object',
          properties: {},
          additionalProperties: false,
        },
        async execute() {
          const { discovery, active } = await live();
          const flows = active.flows();
          const lines = ['flows this runtime can run:'];
          if (flows.length === 0) lines.push('  (none)');
          for (const flow of flows) {
            const spec = (discovery.specs ?? []).find(entry => entry.name === flow.name);
            lines.push(`  ${flow.name} — steps: ${flow.steps.join(', ')}${spec ? ` (from ${spec.path})` : ''}`);
          }
          lines.push(`spec directory: ${discovery.dir}`);
          const specs = discovery.specs ?? [];
          lines.push('spec files:');
          if (specs.length === 0) lines.push('  (none)');
          for (const spec of specs) lines.push(`  ${spec.path} → ${spec.name}`);
          const errors = errorLines(discovery.errors);
          if (errors.length > 0) {
            lines.push('specs that could not be loaded (fix the file, then try again):', ...errors);
          }
          return textResult(textOf(lines), {
            flows: flows.map(flow => flow.name),
            specs: specs.map(spec => ({ name: spec.name, path: spec.path })),
            errors: (discovery.errors ?? []).map(error => ({ path: error.path, code: error.code, message: error.message })),
          });
        },
      }),

      defineTool({
        name: RUN_FLOW_TOOL_NAME,
        description: `Run a flow and return its result. ${FORMAT_HELP} `
          + `Run a spec the agent just wrote by writing it to the workspace first, then calling this with the name the spec declares. `
          + `A flow that reaches an "ask" step comes back with status "paused" and must be continued with ${RESUME_FLOW_TOOL_NAME}. `
          + `If the spec does not compile, its error is returned unchanged so it can be corrected.`,
        parameters: {
          type: 'object',
          properties: {
            flow: { type: 'string', description: 'The name of the flow to run: the "name" its spec declares, or a shipped flow like workspace-script-probe.' },
            threadId: { type: 'string', description: 'A name for this run, so it can be read back or resumed later. Omit to let the runtime choose one; the chosen id is in the result.' },
            input: { type: 'object', description: 'Initial values for the flow\'s declared state fields; each key must be a field the spec declares.', additionalProperties: true },
          },
          required: ['flow'],
          additionalProperties: false,
        },
        async execute(args) {
          const name = typeof args?.flow === 'string' ? args.flow.trim() : '';
          if (name === '') throw new FlowToolError('invalid_flow', 'flow is required and must be the name the spec declares');
          const { discovery, active } = await live();
          if (!active.flows().some(flow => flow.name === name)) return unknown(name, discovery, active);
          const threadId = typeof args?.threadId === 'string' && args.threadId.trim() !== '' ? args.threadId.trim() : undefined;
          const input = args?.input !== undefined && args.input !== null && typeof args.input === 'object' ? args.input : undefined;
          const summary = await executeRun(() => active.run(name, { threadId, input }));
          return textResult(renderRun(summary), runDetails(summary));
        },
      }),

      defineTool({
        name: RESUME_FLOW_TOOL_NAME,
        description: `Continue a flow that is paused waiting for an answer. ${FORMAT_POINTER} `
          + `An "ask" step pauses the run and records what it awaits; this hands that step its answer and continues from where it stopped — the steps before the pause do not run again. `
          + `The value is the answer: a string for a prompt, or the shape the paused step recorded.`,
        parameters: {
          type: 'object',
          properties: {
            threadId: { type: 'string', description: 'The paused thread to continue, as reported by run_flow or flow_status.' },
            value: { description: 'The answer to give the paused step (a string for a prompt, or the value the paused step awaits).' },
          },
          required: ['threadId'],
          additionalProperties: false,
        },
        async execute(args) {
          const threadId = typeof args?.threadId === 'string' ? args.threadId.trim() : '';
          if (threadId === '') throw new FlowToolError('invalid_thread', 'threadId is required and must be the paused thread to continue');
          const { active } = await live();
          const summary = await executeRun(() => active.resume(threadId, args?.value));
          return textResult(renderRun(summary), runDetails(summary));
        },
      }),

      defineTool({
        name: FLOW_STATUS_TOOL_NAME,
        description: `Read one flow thread back: whether it is running, paused, done or failed, what it awaits, its state, and its boundary history. ${FORMAT_POINTER} `
          + `Use it after run_flow returns paused (to see what the "ask" step is waiting for) or failed (to see the step and the reason).`,
        parameters: {
          type: 'object',
          properties: {
            threadId: { type: 'string', description: 'The thread to read back, as reported by run_flow.' },
            limit: { type: 'integer', description: 'How many checkpoints of history to include (default 20).' },
          },
          required: ['threadId'],
          additionalProperties: false,
        },
        async execute(args) {
          const threadId = typeof args?.threadId === 'string' ? args.threadId.trim() : '';
          if (threadId === '') throw new FlowToolError('invalid_thread', 'threadId is required and must be the thread to read');
          const { active } = await live();
          const limit = Number.isInteger(args?.limit) ? args.limit : undefined;
          const status = await executeRun(() => active.status(threadId, limit === undefined ? {} : { limit }));
          const lines = [
            `flow "${status.flow}" is ${status.status} (thread ${status.threadId}, seq ${status.seq})`,
            `steps: ${(status.steps ?? []).join(', ') || '(none)'}`,
          ];
          if (status.awaiting) {
            lines.push(`paused on: ${(status.awaiting.steps ?? []).join(', ')} — awaiting a person`);
            lines.push(`continue it with ${RESUME_FLOW_TOOL_NAME} and threadId ${status.threadId}`);
          }
          if (status.error) lines.push(`last failure: ${JSON.stringify(status.error)}`);
          lines.push(`state: ${JSON.stringify(status.state ?? {})}`);
          lines.push(`checkpoints (newest first): ${(status.checkpoints ?? []).map(entry => `${entry.seq}:${entry.status}`).join(', ')}`);
          return textResult(textOf(lines), {
            flow: status.flow,
            threadId: status.threadId,
            status: status.status,
            seq: status.seq,
            steps: [...(status.steps ?? [])],
            awaiting: status.awaiting ?? null,
            error: status.error ?? null,
            state: status.state ?? {},
          });
        },
      }),
    ],
  });
}
