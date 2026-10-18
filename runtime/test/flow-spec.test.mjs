// The agent-authorable flow spec: plain JSON a model writes into its workspace, compiled into the
// EXISTING engine (`flow.mjs`) over the EXISTING checkpoint store (`flow-store.mjs`).
//
// What these tests are pinning, in order of how much they matter:
//   * a valid spec compiles and RUNS to completion through the real engine and the memory store;
//   * conditions route, and they are DATA — nothing in a spec is ever evaluated as code;
//   * a failing step takes its failure branch, or fails the run naming the step and the cause;
//   * every rejection names the offending location and what was expected, because a model's first
//     spec is wrong and the error text is the only teacher it has;
//   * a spec cannot ask for unbounded work: cycles of unconditional edges are refused, the step
//     count and the run budget are capped, and the engine's own budget is the backstop;
//   * an interrupted run resumes through the checkpoint store, not by re-running from the top.

import test from 'node:test';
import assert from 'node:assert/strict';

const specModule = () => import('../src/flow-spec.mjs');
const engine = () => import('../src/flow.mjs');
const stores = () => import('../src/flow-store.mjs');

/** The bridge ops the capabilities are allowed to reach, with the values a device would return. */
function fakeBridge(options = {}) {
  const requests = [];
  const files = new Map(Object.entries(options.files ?? { 'data.txt': 'hello world' }));
  // A fake interpreter: `printf x` / `printf 'x'` prints x, anything else prints a marker naming it.
  const printedOf = script => {
    const match = /printf\s+'([^']*)'|printf\s+(\S+)/.exec(script);
    return match === null ? null : (match[1] ?? match[2]);
  };
  let directShells = 0;
  const call = async (op, args = {}) => {
    requests.push({ op, args });
    if (op === 'shell.exec') {
      const argv = Array.isArray(args.command);
      // A direct command is what the shell capability sends; the interpreter route always sends
      // an argv, so the two are counted apart.
      if (!argv) directShells += 1;
      if (options.shell) return options.shell(args, directShells);
      if (!argv && args.command === 'fail') return { stdout: 'partial', stderr: 'it broke', exitCode: 3, timedOut: false, cancelled: false };
      if (!argv) return { stdout: printedOf(args.command) ?? `ran:${args.command}`, stderr: '', exitCode: 0, timedOut: false, cancelled: false };
      // `sh -c <script>` runs the script text, `sh <file> [args]` runs the file.
      const rest = args.command.slice(1);
      const script = rest[0] === '-c' ? String(rest[1] ?? '') : String(files.get(rest[0]) ?? '');
      return { stdout: printedOf(script) ?? 'script-ok', stderr: '', exitCode: 0, timedOut: false, cancelled: false };
    }
    if (op === 'file.write') { files.set(args.path, args.text ?? ''); return null; }
    if (op === 'file.read') {
      if (!files.has(args.path)) throw Object.assign(new Error(`No such file: ${args.path}`), { code: 'not_found' });
      return files.get(args.path);
    }
    if (op === 'file.stat') return { exists: files.has(args.path), kind: 'file', size: String(files.get(args.path) ?? '').length, mtimeMs: 0 };
    throw new Error(`Unexpected op ${op}`);
  };
  // The native transport, injected exactly as the host injects it: never an ambient fetch.
  const fetch = async (url, init = {}) => {
    requests.push({ op: 'fetch', url, init });
    if (options.fetch) return options.fetch(url, init);
    return { status: 200, statusText: 'OK', ok: true, headers: { 'content-type': 'text/plain' }, async text() { return 'remote body'; } };
  };
  return { call, fetch, requests, files, shellCalls: () => directShells };
}

const has = (text, ...fragments) => {
  const missing = fragments.filter(fragment => !String(text).includes(fragment));
  return missing.length === 0 ? true : `missing ${JSON.stringify(missing)} in: ${text}`;
};

/** Compile a spec and run it on the real engine over a real (in-memory) checkpoint store. */
async function open(specValue, options = {}) {
  const { compileFlowSpec } = await specModule();
  const { createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const bridge = fakeBridge(options.bridge);
  const store = options.store ?? createMemoryFlowStore();
  const definition = compileFlowSpec(specValue);
  const runner = createFlowRunner({
    store,
    flows: [definition],
    context: { call: bridge.call, fetch: bridge.fetch, workspace: '/workspace', ...options.context },
  });
  return { bridge, store, runner, definition, name: definition.name };
}

/** The SpecError a compile must raise; asserts the type so a crash cannot pass for a rejection. */
async function refusal(specValue) {
  const { compileFlowSpec, SpecError } = await specModule();
  try {
    compileFlowSpec(specValue);
  } catch (error) {
    assert.ok(error instanceof SpecError, `expected a SpecError, got ${error?.name}: ${error?.message}`);
    assert.equal(typeof error.code, 'string');
    assert.notEqual(error.code, '');
    return error;
  }
  assert.fail('the spec was accepted, but it should have been refused');
}

/** The smallest spec that compiles; each table case overrides the part it is about. */
const valid = (patch = {}) => ({
  version: 1,
  name: 'probe',
  state: { n: { initial: 0 } },
  entry: 'a',
  steps: { a: { capability: 'shell', args: { command: 'true' } } },
  ...patch,
});

// ---------------------------------------------------------------------------------------------
// the format: one complete, runnable spec
// ---------------------------------------------------------------------------------------------

test('the exported example spec is plain JSON, compiles, and runs to completion on the engine', async () => {
  const { FLOW_SPEC_EXAMPLE, CAPABILITY_NAMES, CONDITION_OPERATORS } = await specModule();
  // Plain JSON: serializable, diffable, checkpointable — no functions, and round-tripping is lossless.
  assert.deepEqual(JSON.parse(JSON.stringify(FLOW_SPEC_EXAMPLE)), JSON.parse(JSON.stringify(FLOW_SPEC_EXAMPLE)));
  for (const [id, step] of Object.entries(FLOW_SPEC_EXAMPLE.steps)) {
    assert.ok(CAPABILITY_NAMES.includes(step.capability), `${id} names a capability that exists`);
  }
  assert.ok(CAPABILITY_NAMES.includes('shell'));
  assert.ok(CAPABILITY_NAMES.includes('fetch_url'));
  assert.ok(CONDITION_OPERATORS.includes('all'));
  const { runner, name, bridge } = await open(FLOW_SPEC_EXAMPLE);
  const result = await runner.run(name, { threadId: 'example-1' });
  assert.equal(result.status, 'done');
  // The example fetches a URL, saves what it found, and branches on the step's own outcome.
  assert.equal(result.state.body, 'remote body');
  assert.equal(result.state.status, 200);
  assert.deepEqual(result.steps, ['fetch', 'run']);
  assert.equal(bridge.requests.filter(request => request.op === 'fetch').length, 1);
  assert.deepEqual(bridge.requests.filter(request => request.op === 'shell.exec').map(request => request.args.command), [['/system/bin/sh', '-c', "printf 'checked'"]]);
});

// ---------------------------------------------------------------------------------------------
// it runs: end to end, on the engine, over the store
// ---------------------------------------------------------------------------------------------

test('a valid spec compiles into the engine and runs end to end to completion', async () => {
  const SPEC = {
    version: 1,
    name: 'note',
    state: { note: { initial: '' }, runs: { initial: [], reducer: 'append' } },
    entry: 'write',
    steps: {
      write: { capability: 'write_file', args: { path: 'note.txt', text: 'hello from a spec' }, next: 'read' },
      read: { capability: 'read_file', args: { path: 'note.txt' }, save: { note: 'text' }, next: 'run' },
      run: { capability: 'shell', args: { command: ['/system/bin/sh', '-c', 'printf done'] }, save: { runs: 'stdout' } },
    },
    limits: { maxSteps: 12 },
  };
  const { runner, name, bridge, definition } = await open(SPEC);

  // The compiler produced a definition the EXISTING engine accepts; the engine owns the run.
  const { describeFlow } = await engine();
  const shape = describeFlow(definition);
  assert.deepEqual(shape.entry, ['write']);
  assert.deepEqual(shape.steps, ['write', 'read', 'run']);
  assert.deepEqual(shape.limits, { maxSteps: 12, maxStateBytes: 262144 });
  // The engine writes each step's own outcome into the reserved field `last`; the spec declared no
  // such field and could not have.
  assert.deepEqual(shape.state.map(field => field.field).sort(), ['last', 'note', 'runs']);

  const result = await runner.run(name, { threadId: 'note-1' });
  assert.equal(result.status, 'done');
  assert.deepEqual(result.steps, ['write', 'read', 'run']);
  assert.equal(result.state.note, 'hello from a spec');
  assert.deepEqual(result.state.runs, ['done']);
  assert.deepEqual(bridge.files.get('/workspace/note.txt'), 'hello from a spec');
  assert.deepEqual(bridge.requests.map(request => request.op), ['file.write', 'file.read', 'shell.exec']);
  // Every step records what it did: the durable, inspectable trace a person reads back.
  assert.deepEqual(result.state.last, {
    step: 'run',
    ok: true,
    result: { exitCode: 0, stdout: 'done', stderr: '', timedOut: false, cancelled: false },
    error: null,
  });
});

test('the same spec compiled twice is identical, and the same run is reproducible', async () => {
  const { compileFlowSpec, parseFlowSpec } = await specModule();
  const { describeFlow } = await engine();
  const SPEC = {
    version: 1,
    name: 'twice',
    state: { out: { initial: null } },
    entry: 'a',
    steps: {
      a: { capability: 'shell', args: { command: 'printf one' }, save: { out: 'stdout' }, next: { if: { field: 'out', startsWith: 'on' }, then: 'b', else: 'b' } },
      b: { capability: 'shell', args: { command: 'printf two' }, save: { out: 'stdout' } },
    },
  };
  // A spec read as text and a spec handed in as a value compile to the same thing.
  assert.deepEqual(describeFlow(compileFlowSpec(SPEC)), describeFlow(compileFlowSpec(parseFlowSpec(JSON.stringify(SPEC)))));
  assert.ok(Object.isFrozen(compileFlowSpec(SPEC)));
  const first = await open(SPEC);
  const second = await open(SPEC);
  const a = await first.runner.run(first.name, { threadId: 'same' });
  const b = await second.runner.run(second.name, { threadId: 'same' });
  assert.deepEqual(a.state, b.state);
  assert.deepEqual(a.steps, b.steps);
});

test('conditions route two different ways from two different states', async () => {
  const SPEC = {
    version: 1,
    name: 'router',
    state: { mode: { initial: 'a' }, log: { initial: [], reducer: 'append' } },
    entry: 'check',
    steps: {
      check: {
        capability: 'shell',
        args: { command: 'printf checked' },
        next: { if: { field: 'mode', eq: 'a' }, then: 'yes', else: 'no' },
      },
      yes: { capability: 'shell', args: { command: 'printf yes' }, save: { log: 'stdout' } },
      no: { capability: 'shell', args: { command: 'printf no' }, save: { log: 'stdout' } },
    },
  };
  const { runner, name } = await open(SPEC);
  const left = await runner.run(name, { threadId: 'left', input: { mode: 'a' } });
  const right = await runner.run(name, { threadId: 'right', input: { mode: 'b' } });
  assert.deepEqual(left.steps, ['check', 'yes']);
  assert.deepEqual(right.steps, ['check', 'no']);
  assert.deepEqual(left.state.log, ['yes']);
  assert.deepEqual(right.state.log, ['no']);
});

test('the whole condition vocabulary is data and every operator decides', async () => {
  const SPEC = {
    version: 1,
    name: 'vocabulary',
    state: { n: { initial: 3 }, s: { initial: 'alpha' }, missing: { initial: null }, log: { initial: [], reducer: 'append' } },
    entry: 'start',
    steps: {
      start: {
        capability: 'shell',
        args: { command: 'printf started' },
        next: {
          if: {
            all: [
              { field: 'n', eq: 3 },
              { field: 'n', ne: 4 },
              { field: 'n', gt: 2 },
              { field: 'n', gte: 3 },
              { field: 'n', lt: 9 },
              { field: 'n', lte: 3 },
              { field: 's', contains: 'lph' },
              { field: 's', startsWith: 'al' },
              { field: 's', in: ['alpha', 'beta'] },
              { field: 'missing', exists: false },
              { not: { field: 'n', eq: 99 } },
              { any: [{ field: 'n', eq: 99 }, { field: 'n', eq: 3 }] },
            ],
          },
          then: 'hit',
          else: 'miss',
        },
      },
      hit: { capability: 'shell', args: { command: 'printf hit' }, save: { log: 'stdout' } },
      miss: { capability: 'shell', args: { command: 'printf miss' }, save: { log: 'stdout' } },
    },
  };
  const { runner, name } = await open(SPEC);
  const result = await runner.run(name, { threadId: 'vocab' });
  assert.deepEqual(result.state.log, ['hit']);
  assert.deepEqual(result.steps, ['start', 'hit']);
});

// ---------------------------------------------------------------------------------------------
// failure: the step's own outcome, and the step's own name
// ---------------------------------------------------------------------------------------------

test('a failing step takes its failure branch and reports the step and the cause', async () => {
  const SPEC = {
    version: 1,
    name: 'branching',
    state: { why: { initial: null }, saw: { initial: null } },
    entry: 'try',
    steps: {
      // The failure branch is chosen by a condition on the failing step's OWN outcome, which the
      // router reads at that step's boundary (`last` is that step's record there).
      try: {
        capability: 'shell',
        args: { command: 'fail' },
        save: { saw: 'stderr' },
        next: 'ok',
        onFailure: { if: { field: 'last.result.exitCode', eq: 3 }, then: 'record', else: 'ok' },
      },
      ok: { capability: 'shell', args: { command: 'printf ok' }, save: { why: 'stdout' } },
      record: { capability: 'write_file', args: { path: 'failed.txt', text: 'it broke' }, save: { why: 'path' } },
    },
  };
  const { runner, name } = await open(SPEC);
  const result = await runner.run(name, { threadId: 'branching-1' });
  assert.equal(result.status, 'done');
  assert.deepEqual(result.steps, ['try', 'record']);
  assert.equal(result.state.saw, 'it broke', 'the failing step still records what it saw, for the branch that takes over');
  assert.equal(result.state.why, '/workspace/failed.txt');
  assert.deepEqual(result.state.last, {
    step: 'record',
    ok: true,
    result: { path: '/workspace/failed.txt', bytes: 8 },
    error: null,
  });

  // A failure branch that is simply a step, with no condition on the outcome at all.
  const plain = {
    ...SPEC,
    name: 'branching-plain',
    steps: { ...SPEC.steps, try: { ...SPEC.steps.try, onFailure: 'record' } },
  };
  const other = await open(plain);
  const taken = await other.runner.run(other.name, { threadId: 'branching-2' });
  assert.deepEqual(taken.steps, ['try', 'record']);
  assert.equal(taken.state.saw, 'it broke');
});

test('a failing step with no failure branch fails the run with its step id and its cause', async () => {
  const SPEC = {
    version: 1,
    name: 'unhandled',
    state: { n: { initial: 0 } },
    entry: 'try',
    steps: { try: { capability: 'read_file', args: { path: 'nowhere.txt' } } },
  };
  const { runner, name, store } = await open(SPEC);
  await assert.rejects(runner.run(name, { threadId: 'unhandled-1' }), error => {
    assert.equal(error.code, 'flow_step_failed');
    assert.equal(error.step, 'try');
    assert.ok(has(error.message, 'unhandled', 'try', 'read_file', 'No such file'), error.message);
    // The underlying cause travels with the failure, never a generic "step failed".
    assert.equal(error.details.failure.step, 'try');
    assert.ok(has(error.details.failure.code, 'spec_step_failed'), error.details.failure.code);
    assert.ok(has(error.details.failure.message, 'No such file'), error.details.failure.message);
    return true;
  });
  const row = await store.latest('unhandled-1');
  assert.equal(row.status, 'failed');
  assert.ok(has(row.error, 'read_file', 'No such file'), row.error);
});

test('a state path that is not there fails the step naming the step and the reference', async () => {
  const SPEC = {
    version: 1,
    name: 'dangling',
    state: { body: { initial: null } },
    entry: 'a',
    steps: { a: { capability: 'shell', args: { command: '$body.text' } } },
  };
  const { runner, name } = await open(SPEC);
  await assert.rejects(runner.run(name, { threadId: 'dangling-1' }), error => {
    assert.equal(error.code, 'flow_step_failed');
    assert.equal(error.step, 'a');
    assert.ok(has(error.message, 'a', 'command', '$body.text'), error.message);
    assert.equal(error.details.failure.code, 'spec_reference_unresolved');
    return true;
  });
});

// ---------------------------------------------------------------------------------------------
// bounded work: no spec may ask for forever
// ---------------------------------------------------------------------------------------------

test('a spec that could loop forever is refused, and the cycle is named', async () => {
  const error = await refusal(valid({
    name: 'spin',
    entry: 'a',
    steps: {
      a: { capability: 'shell', args: { command: 'true' }, next: 'b' },
      b: { capability: 'shell', args: { command: 'true' }, next: 'a' },
    },
  }));
  assert.equal(error.code, 'spec_unbounded_cycle');
  assert.ok(has(error.message, 'a', 'b', 'no condition'), error.message);
  assert.deepEqual(error.details.cycle, ['a', 'b', 'a']);

  const self = await refusal(valid({
    name: 'self',
    entry: 'a',
    steps: { a: { capability: 'shell', args: { command: 'true' }, next: 'a' } },
  }));
  assert.equal(self.code, 'spec_unbounded_cycle');

  const fan = await refusal(valid({
    name: 'fan',
    entry: 'a',
    steps: {
      a: { capability: 'shell', args: { command: 'true' }, next: ['b', 'c'] },
      b: { capability: 'shell', args: { command: 'true' }, next: 'c' },
      c: { capability: 'shell', args: { command: 'true' }, next: 'b' },
    },
  }));
  assert.equal(fan.code, 'spec_unbounded_cycle');
});

test('a loop that can leave is allowed, and the engine budget is the hard backstop', async () => {
  const SPEC = {
    version: 1,
    name: 'retry',
    state: { seen: { initial: null }, calls: { initial: 0 } },
    entry: 'try',
    steps: {
      try: {
        capability: 'shell',
        args: { command: 'printf attempt' },
        save: { seen: 'stdout' },
        next: { if: { field: 'seen', eq: 'attempt-3' }, then: 'done', else: 'try' },
      },
      done: { capability: 'shell', args: { command: 'printf finished' }, save: { seen: 'stdout' } },
    },
    limits: { maxSteps: 12 },
  };
  const counter = { n: 0 };
  const attempt = args => {
    if (args.command !== 'printf attempt') return { stdout: 'finished', stderr: '', exitCode: 0, timedOut: false, cancelled: false };
    counter.n += 1;
    return { stdout: `attempt-${counter.n}`, stderr: '', exitCode: 0, timedOut: false, cancelled: false };
  };
  const open1 = await open(SPEC, { bridge: { shell: attempt } });
  const result = await open1.runner.run(open1.name, { threadId: 'retry-1' });
  assert.equal(result.status, 'done');
  assert.deepEqual(result.steps, ['try', 'try', 'try', 'done']);
  assert.equal(result.state.seen, 'finished');

  // The same shape without an exit: the engine's own budget stops it, with a coded error.
  const stuck = await open(SPEC, {
    bridge: { shell: args => ({ stdout: args.command === 'printf attempt' ? 'attempt-1' : 'finished', stderr: '', exitCode: 0, timedOut: false, cancelled: false }) },
  });
  await assert.rejects(stuck.runner.run(stuck.name, { threadId: 'retry-2' }), error => {
    assert.equal(error.code, 'flow_max_steps');
    assert.ok(has(error.message, 'retry', '12'), error.message);
    return true;
  });
});

test('limits: too many steps, oversized arguments and out-of-range budgets are coded errors', async () => {
  const { SPEC_MAX_STEPS, SPEC_MAX_RUN_STEPS, SPEC_MAX_ARG_BYTES } = await specModule();

  const many = Object.fromEntries(Array.from({ length: SPEC_MAX_STEPS + 1 }, (_value, index) => [`s${index}`, { capability: 'shell', args: { command: 'true' } }]));
  const tooMany = await refusal(valid({ name: 'many', entry: 's0', steps: many }));
  assert.equal(tooMany.code, 'spec_too_many_steps');
  assert.ok(has(tooMany.message, String(SPEC_MAX_STEPS + 1), String(SPEC_MAX_STEPS)), tooMany.message);

  const big = await refusal(valid({ name: 'big', steps: { a: { capability: 'write_file', args: { path: 'x.txt', text: 'x'.repeat(SPEC_MAX_ARG_BYTES + 1) } } } }));
  assert.equal(big.code, 'spec_args_too_large');
  assert.ok(has(big.message, 'steps.a.args', String(SPEC_MAX_ARG_BYTES)), big.message);

  const over = await refusal(valid({ name: 'over', limits: { maxSteps: SPEC_MAX_RUN_STEPS + 1 } }));
  assert.equal(over.code, 'spec_limit_invalid');
  assert.ok(has(over.message, 'limits.maxSteps', String(SPEC_MAX_RUN_STEPS)), over.message);

  const zero = await refusal(valid({ name: 'zero', limits: { maxSteps: 0 } }));
  assert.equal(zero.code, 'spec_limit_invalid');

  // A spec is bounded too, before it is ever compiled.
  const huge = await refusal(valid({ name: 'huge', state: { bloat: { initial: 'x'.repeat(300 * 1024) } } }));
  assert.equal(huge.code, 'spec_too_large');
});

// ---------------------------------------------------------------------------------------------
// resume: an interrupted run continues from the checkpoint, not from the top
// ---------------------------------------------------------------------------------------------

test('an interrupted run pauses, and a fresh runner resumes it through the checkpoint store', async () => {
  const SPEC = {
    version: 1,
    name: 'ask-then-run',
    state: { answer: { initial: null } },
    entry: 'prep',
    steps: {
      prep: { capability: 'shell', args: { command: 'printf prepped' }, next: 'ask' },
      ask: {
        capability: 'ask',
        args: { prompt: 'Run the job?' },
        next: { if: { field: 'last.result.answer', eq: true }, then: 'run', else: 'stop' },
      },
      run: { capability: 'run_script', args: { path: 'job.sh', scriptArgs: ['--now'] }, save: { answer: 'stdout' } },
      stop: { capability: 'shell', args: { command: 'printf stopped' }, save: { answer: 'stdout' } },
    },
  };
  const { compileFlowSpec } = await specModule();
  const { createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const store = createMemoryFlowStore();
  const bridge = fakeBridge({ files: { '/workspace/job.sh': 'printf ran-job' } });
  const run = () => {
    const definition = compileFlowSpec(SPEC);
    return createFlowRunner({ store, flows: [definition], context: { call: bridge.call, fetch: bridge.fetch, workspace: '/workspace' } });
  };

  const first = await run().run('ask-then-run', { threadId: 'thread-ask' });
  assert.equal(first.status, 'paused');
  assert.deepEqual(first.awaiting.steps, ['ask']);
  assert.deepEqual(first.awaiting.values[0], { prompt: 'Run the job?' });
  assert.deepEqual(first.steps, ['prep', 'ask']);
  // The pause is a committed boundary in the store, and it holds the state the run reached.
  const paused = await store.latest('thread-ask');
  assert.equal(paused.status, 'paused');
  assert.deepEqual(JSON.parse(paused.state).answer, null);
  assert.equal(bridge.shellCalls(), 1, 'the step before the question ran exactly once');

  // A DIFFERENT runner, built from scratch, continues the thread: nothing before the pause re-runs.
  const done = await run().resume('thread-ask', true);
  assert.equal(done.status, 'done');
  assert.deepEqual(done.steps, ['ask', 'run']);
  assert.equal(done.state.answer, 'ran-job');
  assert.equal(bridge.shellCalls(), 1, 'resuming did not re-run the prep step');
  const argv = bridge.requests.filter(request => request.op === 'shell.exec' && Array.isArray(request.args.command)).map(request => request.args.command);
  assert.deepEqual(argv, [['/system/bin/sh', '/workspace/job.sh', '--now']]);
  // seq 0 is the start boundary, 1 the step before the question, 2 the pause itself, 3 the resumed
  // answer step and 4 the step it routed to: every boundary is a row a reader can walk back through.
  assert.deepEqual((await store.history('thread-ask')).map(row => [row.seq, row.status]), [[4, 'done'], [3, 'running'], [2, 'paused'], [1, 'running'], [0, 'running']]);
  assert.deepEqual(JSON.parse((await store.history('thread-ask'))[2].awaiting).steps, ['ask']);

  // And the declined branch, from the same shape: the answer is a literal in the state.
  const declined = await run().run('ask-then-run', { threadId: 'thread-no' });
  assert.equal(declined.status, 'paused');
  const stopped = await run().resume('thread-no', false);
  assert.equal(stopped.state.answer, 'stopped');
  assert.deepEqual(stopped.steps, ['ask', 'stop']);
});

// ---------------------------------------------------------------------------------------------
// conditions and arguments are data: nothing in a spec is ever evaluated
// ---------------------------------------------------------------------------------------------

test('no condition can execute code: an expression-looking string is a literal', async () => {
  const SPEC = {
    version: 1,
    name: 'literal',
    state: { code: { initial: 'process.exitCode === 0' }, log: { initial: [], reducer: 'append' } },
    entry: 'check',
    steps: {
      check: {
        capability: 'shell',
        args: { command: 'printf checked' },
        next: { if: { field: 'code', eq: 'process.exitCode === 0' }, then: 'hit', else: 'miss' },
      },
      hit: { capability: 'shell', args: { command: 'printf hit' }, save: { log: 'stdout' } },
      miss: { capability: 'shell', args: { command: 'printf miss' }, save: { log: 'stdout' } },
    },
  };
  const { runner, name } = await open(SPEC);
  // The string is compared to the state value as a string; nothing about it is code.
  const equal = await runner.run(name, { threadId: 'literal-equal' });
  assert.deepEqual(equal.state.log, ['hit']);
  // A number is not the same value as the text that spells an expression.
  const numeric = await runner.run(name, { threadId: 'literal-number', input: { code: 0 } });
  assert.deepEqual(numeric.state.log, ['miss']);

  // A condition whose literal is a whole program is still a literal.
  const { compileFlowSpec } = await specModule();
  const sneaky = valid({
    name: 'sneaky',
    state: { payload: { initial: '"; require("node:child_process").execSync("touch /tmp/pwned"); "' } },
    entry: 'a',
    steps: {
      a: {
        capability: 'shell',
        args: { command: 'true' },
        next: { if: { all: [{ field: 'payload', contains: 'execSync' }, { field: 'payload', in: ['"; require("node:child_process").execSync("touch /tmp/pwned"); "'] }] }, then: 'b', else: 'c' },
      },
      b: { capability: 'shell', args: { command: 'printf b' } },
      c: { capability: 'shell', args: { command: 'printf c' } },
    },
  });
  assert.equal(compileFlowSpec(sneaky).name, 'sneaky');

  // The compiler itself has no way to turn spec text into code.
  const { readFile } = await import('node:fs/promises');
  const sources = await Promise.all(['../src/flow-spec.mjs', '../src/flow-capabilities.mjs'].map(file => readFile(new URL(file, import.meta.url), 'utf8')));
  for (const source of sources) {
    assert.doesNotMatch(source, /\beval\s*\(/);
    assert.doesNotMatch(source, /\bnew\s+Function\b/);
    assert.doesNotMatch(source, /\bFunction\s*\(/);
  }
});

test('arguments read state by a "$field.path" reference, and nothing else', async () => {
  const SPEC = {
    version: 1,
    name: 'reference',
    state: { url: { initial: 'https://example.com/data.json' }, target: { initial: { name: 'x.txt' } }, body: { initial: null } },
    entry: 'fetch',
    steps: {
      fetch: { capability: 'fetch_url', args: { url: '$url' }, save: { body: 'body' }, next: 'write' },
      write: { capability: 'write_file', args: { path: '$target.name', text: '$body' } },
    },
  };
  const { runner, name, bridge } = await open(SPEC, { bridge: { fetch: async () => ({ status: 200, ok: true, statusText: 'OK', headers: {}, async text() { return 'payload'; } }) } });
  const result = await runner.run(name, { threadId: 'reference-1' });
  assert.equal(result.state.body, 'payload');
  assert.deepEqual(bridge.requests.find(request => request.op === 'fetch').url, 'https://example.com/data.json');
  assert.deepEqual(bridge.files.get('/workspace/x.txt'), 'payload');
  // A reference to a field that does not exist is refused before anything runs.
  const error = await refusal(valid({ name: 'bad-ref', steps: { a: { capability: 'shell', args: { command: '$nope.path' } } } }));
  assert.equal(error.code, 'spec_state_field_unknown');
  assert.ok(has(error.message, 'steps.a.args.command', '$nope.path', 'nope'), error.message);
});

// ---------------------------------------------------------------------------------------------
// capabilities: the ones the runtime already has, reached the way it already reaches them
// ---------------------------------------------------------------------------------------------

test('every capability goes through the bridge the runtime already uses, and never a direct exec', async () => {
  const SPEC = {
    version: 1,
    name: 'all-capabilities',
    state: { out: { initial: [] , reducer: 'append' }, file: { initial: null } },
    entry: 'write',
    steps: {
      write: { capability: 'write_file', args: { path: 'job.sh', text: 'printf script-ran' }, save: { file: 'path' }, next: 'shell' },
      // A shell command: a string through the same op the agent's own shell tool uses.
      shell: { capability: 'shell', args: { command: 'printf shell-ran', timeoutMs: 5000 }, save: { out: 'stdout' }, next: 'script' },
      // A script: handed to the interpreter as ARGV, never executed.
      script: { capability: 'run_script', args: { path: 'job.sh', scriptArgs: ['a'] }, save: { out: 'stdout' }, next: 'read' },
      read: { capability: 'read_file', args: { path: 'job.sh' }, next: 'net' },
      net: { capability: 'fetch_url', args: { url: 'https://example.com', method: 'GET', headers: { accept: 'text/plain' } }, save: { out: 'body' } },
    },
  };
  const { runner, name, bridge } = await open(SPEC);
  const result = await runner.run(name, { threadId: 'all-1' });
  assert.equal(result.status, 'done');
  assert.deepEqual(result.state.out, ['shell-ran', 'script-ran', 'remote body']);
  assert.deepEqual(result.state.file, '/workspace/job.sh');
  // The ops, in order: the interpreter route checks the file first, exactly like run_script does.
  assert.deepEqual(bridge.requests.map(request => request.op), ['file.write', 'shell.exec', 'file.stat', 'shell.exec', 'file.read', 'fetch']);
  const argv = bridge.requests.filter(request => request.op === 'shell.exec' && Array.isArray(request.args.command)).map(request => request.args.command);
  assert.deepEqual(argv, [['/system/bin/sh', '/workspace/job.sh', 'a']]);
  for (const request of bridge.requests) {
    if (request.op !== 'shell.exec' || !Array.isArray(request.args.command)) continue;
    assert.equal(request.args.command[0], '/system/bin/sh', 'the interpreter is argv[0], never the file');
  }
  // The native transport is the one that was handed in; an ambient fetch is never used.
  assert.equal(bridge.requests.some(request => request.op === 'fetch'), true);
});

test('a fetch that answers badly takes the failure branch on its HTTP status', async () => {
  const SPEC = {
    version: 1,
    name: 'http-status',
    state: { log: { initial: [], reducer: 'append' } },
    entry: 'net',
    steps: {
      net: { capability: 'fetch_url', args: { url: 'https://example.com/gone' }, save: { log: 'status' }, next: 'fine', onFailure: 'sad' },
      fine: { capability: 'shell', args: { command: 'printf fine' }, save: { log: 'stdout' } },
      sad: { capability: 'shell', args: { command: 'printf sad' }, save: { log: 'stdout' } },
    },
  };
  const { runner, name } = await open(SPEC, { bridge: { fetch: async () => ({ status: 503, statusText: 'Service Unavailable', ok: false, headers: {}, async text() { return 'down'; } }) } });
  const result = await runner.run(name, { threadId: 'http-1' });
  assert.equal(result.status, 'done');
  assert.deepEqual(result.steps, ['net', 'sad']);
  assert.deepEqual(result.state.log, [503, 'sad']);
});

test('a step that needs a capability the flow was not given says exactly what is missing', async () => {
  const SPEC = { version: 1, name: 'no-transport', state: { out: { initial: null } }, entry: 'net', steps: { net: { capability: 'fetch_url', args: { url: 'https://example.com' } } } };
  const { runner, name } = await open(SPEC, { context: { fetch: undefined } });
  await assert.rejects(runner.run(name, { threadId: 'no-transport-1' }), error => {
    assert.equal(error.code, 'flow_step_failed');
    assert.equal(error.step, 'net');
    assert.ok(has(error.message, 'fetch_url', 'transport'), error.message);
    return true;
  });
});

// ---------------------------------------------------------------------------------------------
// validation: every rejection names the location and what was expected
// ---------------------------------------------------------------------------------------------

test('an unknown transition target names the offender, the location, and every valid step', async () => {
  const error = await refusal(valid({
    name: 'typo',
    entry: 'start',
    steps: {
      start: { capability: 'shell', args: { command: 'true' }, next: 'midle' },
      middle: { capability: 'shell', args: { command: 'true' }, next: 'endd' },
      end: { capability: 'shell', args: { command: 'true' } },
    },
  }));
  assert.equal(error.code, 'spec_unknown_step');
  assert.equal(error.details.path, 'steps.start.next');
  // Enough to fix it without seeing anything else: where, what, and what was allowed.
  assert.ok(has(error.message, 'steps.start.next', '"midle"'), error.message);
  for (const step of ['start', 'middle', 'end']) assert.ok(has(error.message, step), error.message);
});

test('every way a spec can be wrong is refused with a code, a location and an expectation', async () => {
  const cases = [
    {
      what: 'not an object',
      spec: ['not', 'a', 'spec'],
      code: 'spec_not_object',
      match: ['spec', 'object'],
      // There is nothing above the spec itself, so there is no location to name.
      top: true,
    },
    {
      what: 'an unsupported version',
      spec: valid({ version: 2 }),
      code: 'spec_version_unsupported',
      match: ['version', '2', '1'],
    },
    {
      what: 'no name',
      spec: valid({ name: '  ' }),
      code: 'spec_name_invalid',
      match: ['name'],
    },
    {
      what: 'a state field with no initial value',
      spec: valid({ state: { attempts: { reducer: 'replace' } } }),
      code: 'spec_state_field_invalid',
      match: ['state.attempts', 'initial'],
    },
    {
      what: 'an unknown reducer',
      spec: valid({ state: { log: { initial: [], reducer: 'push' } } }),
      code: 'spec_state_reducer_unknown',
      match: ['state.log', 'push', 'append', 'merge'],
    },
    {
      what: 'a state value JSON cannot carry',
      spec: valid({ state: { n: { initial: Number.NaN } } }),
      code: 'spec_state_not_json',
      match: ['state.n', 'JSON'],
    },
    {
      what: 'a state value that is not a plain value',
      spec: valid({ state: { n: { initial: () => 1 } } }),
      code: 'spec_state_not_json',
      match: ['state.n'],
    },
    {
      what: 'a reserved state field',
      spec: valid({ state: { last: { initial: 1 } } }),
      code: 'spec_state_reserved',
      match: ['state.last', 'last'],
    },
    {
      what: 'an entry that is not a step',
      spec: valid({ entry: 'nope' }),
      code: 'spec_unknown_step',
      match: ['entry', '"nope"', 'a'],
    },
    {
      what: 'a steps list that is empty',
      spec: valid({ entry: 'a', steps: {} }),
      code: 'spec_step_invalid',
      match: ['steps'],
    },
    {
      what: 'a step that is not an object',
      spec: valid({ steps: { a: 'run something' } }),
      code: 'spec_step_invalid',
      match: ['steps.a', 'capability'],
    },
    {
      what: 'a step with an unknown key',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, when: 'always' } } }),
      code: 'spec_step_invalid',
      match: ['steps.a.when', 'next', 'onFailure'],
    },
    {
      what: 'a step with no capability',
      spec: valid({ steps: { a: { args: { command: 'true' } } } }),
      code: 'spec_step_invalid',
      match: ['steps.a.capability'],
    },
    {
      what: 'an unknown capability',
      spec: valid({ steps: { a: { capability: 'shellx', args: {} } } }),
      code: 'spec_unknown_capability',
      match: ['steps.a.capability', '"shellx"', 'run_script', 'fetch_url'],
    },
    {
      what: 'args that are not an object',
      spec: valid({ steps: { a: { capability: 'shell', args: 'true' } } }),
      code: 'spec_args_invalid',
      match: ['steps.a.args', 'object'],
    },
    {
      what: 'a missing required argument',
      spec: valid({ steps: { a: { capability: 'shell', args: { cwd: '/workspace' } } } }),
      code: 'spec_args_invalid',
      match: ['steps.a.args', 'command', 'required'],
    },
    {
      what: 'a misspelled argument',
      spec: valid({ steps: { a: { capability: 'shell', args: { commmand: 'true' } } } }),
      code: 'spec_args_invalid',
      match: ['steps.a.args.commmand', 'command', 'cwd'],
    },
    {
      what: 'an argument of the wrong type',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true', timeoutMs: 'soon' } } } }),
      code: 'spec_args_invalid',
      match: ['steps.a.args.timeoutMs', 'whole number', 'soon'],
    },
    {
      what: 'a save target that is not a state field',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, save: { nope: 'stdout' } } } }),
      code: 'spec_state_field_unknown',
      match: ['steps.a.save.nope', 'nope', 'n'],
    },
    {
      what: 'a save that is not an object',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, save: 'stdout' } } }),
      code: 'spec_save_invalid',
      match: ['steps.a.save', 'object'],
    },
    {
      what: 'a transition into nothing',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: 'ghost' } } }),
      code: 'spec_unknown_step',
      match: ['steps.a.next', '"ghost"'],
    },
    {
      what: 'a transition that is not a step or a condition',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: 7 } } }),
      code: 'spec_transition_invalid',
      match: ['steps.a.next', 'condition'],
    },
    {
      what: 'a conditional with no else that names a step that does not exist',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: { field: 'n', eq: 0 }, then: 'ghost' } } } }),
      code: 'spec_unknown_step',
      match: ['steps.a.next.then', '"ghost"'],
    },
    {
      what: 'a condition that is not an object',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: 'n > 0', then: 'a' } } } }),
      code: 'spec_condition_invalid',
      match: ['steps.a.next.if', 'condition'],
    },
    {
      what: 'a condition with no field',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: { eq: 0 }, then: 'a' } } } }),
      code: 'spec_condition_invalid',
      match: ['steps.a.next.if', 'field'],
    },
    {
      what: 'a condition operator that does not exist',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: { field: 'n', equals: 0 }, then: 'a' } } } }),
      code: 'spec_condition_operator_unknown',
      match: ['steps.a.next.if', 'equals', 'eq', 'contains'],
    },
    {
      what: 'a condition with two operators at once',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: { field: 'n', eq: 0, gt: 1 }, then: 'a' } } } }),
      code: 'spec_condition_invalid',
      match: ['steps.a.next.if', 'exactly one'],
    },
    {
      what: 'a condition on a field that does not exist',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: { field: 'nope', eq: 0 }, then: 'a' } } } }),
      code: 'spec_state_field_unknown',
      match: ['steps.a.next.if.field', 'nope', 'n'],
    },
    {
      what: 'a numeric comparison against a string',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: { field: 'n', gt: 'many' }, then: 'a' } } } }),
      code: 'spec_condition_value_invalid',
      match: ['steps.a.next.if.gt', 'number', 'many'],
    },
    {
      what: 'a set membership that is not a list',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: { field: 'n', in: 'nope' }, then: 'a' } } } }),
      code: 'spec_condition_value_invalid',
      match: ['steps.a.next.if.in', 'list'],
    },
    {
      what: 'an empty combinator',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: { all: [] }, then: 'a' } } } }),
      code: 'spec_condition_invalid',
      match: ['steps.a.next.if.all', 'at least one'],
    },
    {
      what: 'a not with a nested unknown operator',
      spec: valid({ steps: { a: { capability: 'shell', args: { command: 'true' }, next: { if: { not: { field: 'n', nope: 1 } }, then: 'a' } } } }),
      code: 'spec_condition_operator_unknown',
      match: ['steps.a.next.if.not', 'nope'],
    },
    {
      what: 'a failure branch on a step that cannot fail',
      spec: valid({ steps: { a: { capability: 'ask', args: { prompt: 'ok?' }, onFailure: 'b' }, b: { capability: 'shell', args: { command: 'true' } } } }),
      code: 'spec_step_invalid',
      match: ['steps.a.onFailure', 'ask'],
    },
  ];
  for (const entry of cases) {
    const error = await refusal(entry.spec);
    assert.equal(error.code, entry.code, `${entry.what}: ${error.message}`);
    assert.ok(has(error.message, ...entry.match), `${entry.what}: ${error.message}`);
    assert.equal(typeof error.details.path, 'string', entry.what);
    if (entry.top !== true) assert.notEqual(error.details.path, '', entry.what);
  }
});

test('a JSON text that is not a spec is refused as text, not as a spec', async () => {
  const { parseFlowSpec, compileFlowSpec } = await specModule();
  assert.throws(() => parseFlowSpec('{ "name": '), error => {
    assert.equal(error.code, 'spec_invalid_json');
    return true;
  });
  assert.equal(parseFlowSpec('{"version":1}').version, 1);
  assert.equal(compileFlowSpec('{"version":1,"name":"n","state":{"a":{"initial":1}},"entry":"a","steps":{"a":{"capability":"shell","args":{"command":"true"}}}}').name, 'n');
});

test('a spec with more than one entry step or a fan-out is still refused when it cannot end', async () => {
  // Arrays are legal transitions; an array that leads back is still an unconditional cycle.
  const error = await refusal(valid({
    name: 'fan-back',
    entry: 'start',
    steps: {
      start: { capability: 'shell', args: { command: 'true' }, next: ['left', 'right'] },
      left: { capability: 'shell', args: { command: 'true' }, next: 'start' },
      right: { capability: 'shell', args: { command: 'true' }, next: 'start' },
    },
  }));
  assert.equal(error.code, 'spec_unbounded_cycle');
  assert.ok(has(error.message, 'start'), error.message);
});

test('the vocabulary a tool description is written from is the vocabulary the compiler enforces', async () => {
  const { describeCapabilities, describeConditions, describeFormat, CAPABILITY_NAMES, CONDITION_OPERATORS, FLOW_SPEC_EXAMPLE } = await specModule();
  const capabilities = describeCapabilities();
  assert.deepEqual(capabilities.map(entry => entry.name), [...CAPABILITY_NAMES]);
  for (const entry of capabilities) {
    assert.equal(typeof entry.does, 'string');
    assert.equal(typeof entry.canFail, 'boolean');
    for (const argument of entry.arguments) assert.equal(typeof argument.takes, 'string');
  }
  // Every operator the vocabulary lists is described, and nothing is described that is not listed.
  const described = describeConditions().flatMap(entry => entry.operator.split(' / '));
  assert.deepEqual(described.sort(), [...CONDITION_OPERATORS].sort());
  // The format text names every capability, and the example is the shape the text describes.
  const format = describeFormat().join('\n');
  for (const name of CAPABILITY_NAMES) assert.ok(format.includes(name), name);
  assert.deepEqual(Object.keys(FLOW_SPEC_EXAMPLE).sort(), ['entry', 'limits', 'name', 'state', 'steps', 'version']);
});

test('two threads running the same flow do not share a shell request id', async () => {
  const { invokeCapability } = await import('../src/flow-capabilities.mjs');
  const ids = [];
  const contextFor = threadId => ({
    workspace: '/workspace',
    flow: 'same-flow',
    step: 'run',
    superstep: 0,
    threadId,
    async call(op, args) {
      assert.equal(op, 'shell.exec');
      ids.push(args.requestId);
      return { stdout: '', stderr: '', exitCode: 0, timedOut: false, cancelled: false };
    },
  });
  await invokeCapability('shell', { command: 'true' }, contextFor('thread-a'));
  await invokeCapability('shell', { command: 'true' }, contextFor('thread-b'));
  assert.notEqual(ids[0], ids[1], 'the same flow/step/superstep on two threads must not collide');
  assert.ok(ids[0].includes('thread-a') && ids[1].includes('thread-b'), JSON.stringify(ids));
});

test('a flow shell step records the host truncation flags instead of silently dropping them', async () => {
  const { invokeCapability } = await import('../src/flow-capabilities.mjs');
  const outcome = await invokeCapability('shell', { command: 'yes' }, {
    workspace: '/workspace',
    flow: 'truncated',
    step: 'run',
    superstep: 0,
    threadId: 'thread-c',
    async call() {
      return {
        stdout: 'x', stderr: '', exitCode: 0, timedOut: false, cancelled: false,
        drainIncomplete: true, streamTruncated: true, stdoutTruncated: true,
      };
    },
  });
  assert.equal(outcome.ok, true);
  assert.equal(outcome.result.drainIncomplete, true);
  assert.equal(outcome.result.streamTruncated, true);
  assert.equal(outcome.result.stdoutTruncated, true);
});

test('a flow shell step with an already-aborted context never calls the shell', async () => {
  const { invokeCapability } = await import('../src/flow-capabilities.mjs');
  let called = false;
  await assert.rejects(invokeCapability('shell', { command: 'true' }, {
    workspace: '/workspace',
    flow: 'aborted',
    step: 'run',
    superstep: 0,
    threadId: 'thread-d',
    abortSignal: { aborted: true, addEventListener() {}, removeEventListener() {} },
    async call() { called = true; return { stdout: '', stderr: '', exitCode: 0, timedOut: false, cancelled: false }; },
  }), error => {
    assert.equal(error.code, 'cancelled');
    return true;
  });
  assert.equal(called, false);
});
