// The flow engine: typed state with reducers, named steps executed in deterministic supersteps,
// conditional transitions, interrupts with resume, and one checkpoint per superstep boundary.
//
// Every test drives the real engine over a real store. Only the bridge `call` is faked where a test
// needs a capability (the example flow's tests), never the scheduler.

import test from 'node:test';
import assert from 'node:assert/strict';

const engine = () => import('../src/flow.mjs');
const stores = () => import('../src/flow-store.mjs');

/** A two-party barrier: a step cannot pass it unless another step is in flight at the same time. */
function createBarrier(parties) {
  let arrived = 0;
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  return async () => {
    arrived += 1;
    if (arrived >= parties) release();
    await gate;
  };
}

/** A barrier that never gets its parties must fail the test rather than hang the suite. */
const timed = (promise, label) => Promise.race([
  promise,
  new Promise((_, reject) => { setTimeout(() => reject(new Error(label)), 2000); }),
]);

test('one superstep runs its ready steps concurrently and no step observes another write', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const barrier = createBarrier(2);
  let inFlight = 0;
  let peak = 0;
  const observed = [];
  const flow = defineFlow({
    name: 'fan-out',
    state: { log: { initial: [], reducer: 'append' } },
    entry: ['left', 'right'],
    steps: {
      async left(state) {
        inFlight += 1; peak = Math.max(peak, inFlight); observed.push(['left', state.log.length]);
        await timed(barrier(), 'the two ready steps never overlapped');
        inFlight -= 1;
        return { log: ['left'] };
      },
      async right(state) {
        inFlight += 1; peak = Math.max(peak, inFlight); observed.push(['right', state.log.length]);
        await timed(barrier(), 'the two ready steps never overlapped');
        inFlight -= 1;
        return { log: ['right'] };
      },
    },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  const result = await runner.run('fan-out', { threadId: 'thread-1' });
  assert.equal(peak, 2, 'both ready steps were in flight at once');
  assert.deepEqual(observed, [['left', 0], ['right', 0]], 'neither step saw the other sibling write');
  assert.deepEqual(result.state.log, ['left', 'right']);
  assert.equal(result.status, 'done');
  assert.equal(result.seq, 1, 'one superstep ran, so one boundary was committed');
  assert.equal(result.supersteps, 1);
});

test('a step cannot write through the state object it was handed', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'meddle',
    state: { log: { initial: [], reducer: 'append' } },
    entry: 'meddle',
    steps: { meddle: async state => { state.log.push('through the snapshot'); return { log: ['legitimate'] }; } },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  await assert.rejects(runner.run('meddle', { threadId: 'snapshot' }), error => {
    assert.equal(error.code, 'flow_step_failed');
    assert.equal(error.step, 'meddle');
    assert.equal(error.cause instanceof TypeError, true, String(error.cause));
    return true;
  });
  const thread = await runner.status('snapshot');
  assert.deepEqual(thread.state.log, [], 'the failed superstep applied nothing');
});

test('replace, append and merge reducers apply every write at the boundary', async () => {
  const { defineFlow, createFlowRunner, END } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'reducers',
    state: {
      counter: { initial: 0 },
      log: { initial: [], reducer: 'append' },
      facts: { initial: { seeded: true }, reducer: 'merge' },
    },
    entry: 'first',
    steps: {
      first: async () => ({ counter: 1, log: ['a'], facts: { first: 1 } }),
      second: async () => ({ counter: 2, log: ['b', 'c'], facts: { second: 2 } }),
      third: async () => ({ counter: 3, log: 'd', facts: { third: 3 } }),
    },
    transitions: { first: 'second', second: 'third', third: END },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  const result = await runner.run('reducers', { threadId: 'r1' });
  assert.deepEqual(result.state, {
    counter: 3,
    log: ['a', 'b', 'c', 'd'],
    facts: { seeded: true, first: 1, second: 2, third: 3 },
  });
  // The declared initial values are per run: a second run starts from a fresh array and object.
  const again = await runner.run('reducers', { threadId: 'r2' });
  assert.deepEqual(again.state.log, ['a', 'b', 'c', 'd']);
  assert.deepEqual(again.state.facts, { seeded: true, first: 1, second: 2, third: 3 });
});

test('a conditional router takes different branches from different state', async () => {
  const { defineFlow, createFlowRunner, END } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'route',
    state: { value: { initial: 0 }, visited: { initial: [], reducer: 'append' } },
    entry: 'check',
    steps: {
      check: async () => ({ visited: ['check'] }),
      high: async () => ({ visited: ['high'] }),
      low: async () => ({ visited: ['low'] }),
    },
    transitions: {
      // The router runs at the boundary, against the state the superstep's writes produced.
      check: (state, info) => {
        assert.deepEqual(state.visited, ['check'], 'the router saw the applied write');
        assert.equal(info.step, 'check');
        assert.deepEqual(info.writes, { visited: ['check'] });
        return state.value > 10 ? 'high' : 'low';
      },
      high: END,
      low: END,
    },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  const big = await runner.run('route', { threadId: 'big', input: { value: 50 } });
  const small = await runner.run('route', { threadId: 'small', input: { value: 1 } });
  assert.deepEqual(big.state.visited, ['check', 'high']);
  assert.deepEqual(small.state.visited, ['check', 'low']);
  assert.deepEqual(big.steps, ['check', 'high']);
  assert.deepEqual(small.steps, ['check', 'low']);
});

test('a static chain runs to completion in the documented order with one checkpoint per boundary', async () => {
  const { defineFlow, createFlowRunner, END } = await engine();
  const { createMemoryFlowStore } = await stores();
  const store = createMemoryFlowStore();
  const flow = defineFlow({
    name: 'chain',
    state: { trail: { initial: [], reducer: 'append' } },
    entry: 'a',
    steps: {
      a: async () => ({ trail: ['a'] }),
      b: async () => ({ trail: ['b'] }),
      c: async () => ({ trail: ['c'] }),
    },
    transitions: { a: 'b', b: 'c', c: END },
  });
  const runner = createFlowRunner({ store, flows: [flow] });
  const result = await runner.run('chain', { threadId: 'c1' });
  assert.deepEqual(result.steps, ['a', 'b', 'c']);
  assert.deepEqual(result.state.trail, ['a', 'b', 'c']);
  assert.equal(result.supersteps, 3);
  assert.equal(result.seq, 3);
  assert.equal(result.status, 'done');
  const history = await store.history('c1', { limit: 10 });
  assert.deepEqual(
    history.map(row => [row.seq, row.status, row.steps]),
    [[3, 'done', ['c']], [2, 'running', ['b']], [1, 'running', ['a']], [0, 'running', []]],
    'the start boundary and one boundary per superstep, newest first',
  );
  const status = await runner.status('c1');
  assert.deepEqual(status.state.trail, ['a', 'b', 'c']);
  assert.deepEqual(status.checkpoints.map(entry => entry.seq), [3, 2, 1, 0]);
});

test("a step's own goto outranks its declared transition, and a router may fan out or end", async () => {
  const { defineFlow, createFlowRunner, END } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'routing',
    state: { trail: { initial: [], reducer: 'append' } },
    entry: 'start',
    steps: {
      // The hint wins over the declared transition, and it may name several steps: both of them become
      // ready in the same superstep.
      start: async () => ({ trail: ['start'], goto: ['left', 'right'] }),
      left: async () => ({ trail: ['left'] }),
      right: async () => ({ trail: ['right'] }),
      // The explicit `update` envelope says the same thing as bare keys.
      both: async () => ({ update: { trail: ['both'] } }),
      end: async () => ({ trail: ['end'], goto: END }),
    },
    transitions: {
      // Declared for `start`, but the step's own hint outranks it. `end` declares a transition back to
      // `start` as well, so only a router honouring END can stop the cycle this definition describes:
      // if END were ignored, this run would exhaust its step budget instead of finishing.
      start: 'end',
      left: 'both',
      right: () => ['both'],
      both: 'end',
      end: 'start',
    },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  const result = await runner.run('routing', { threadId: 'routing' });
  assert.equal(result.status, 'done');
  assert.deepEqual(result.steps, ['start', 'left', 'right', 'both', 'end']);
  assert.deepEqual(result.state.trail, ['start', 'left', 'right', 'both', 'end']);
  assert.equal(result.supersteps, 4, 'the fan-out is one superstep, and the shared target runs once');
});

test('an interrupt pauses the run, records what it awaits, and resume continues where it stopped', async () => {
  const { defineFlow, createFlowRunner, END } = await engine();
  const { createMemoryFlowStore } = await stores();
  const store = createMemoryFlowStore();
  const ran = [];
  const flow = defineFlow({
    name: 'approve',
    state: { trail: { initial: [], reducer: 'append' }, answer: { initial: null } },
    entry: ['prepare', 'ask'],
    steps: {
      async prepare() { ran.push('prepare'); return { trail: ['prepare'] }; },
      async ask(state, context) {
        ran.push(context.resume === undefined ? 'ask:first' : `ask:${context.resume}`);
        if (context.resume === undefined) return { interrupt: { question: 'continue?', step: 'ask' } };
        return { answer: context.resume, trail: [`answer:${context.resume}`] };
      },
      async finish() { ran.push('finish'); return { trail: ['finish'] }; },
    },
    transitions: { prepare: 'finish', ask: 'finish', finish: END },
  });
  const runner = createFlowRunner({ store, flows: [flow] });
  const paused = await runner.run('approve', { threadId: 't' });
  assert.equal(paused.status, 'paused');
  assert.equal(paused.seq, 1);
  assert.deepEqual(paused.awaiting.steps, ['ask']);
  assert.deepEqual(paused.awaiting.values, [{ question: 'continue?', step: 'ask' }]);
  assert.deepEqual(paused.awaiting.next, ['finish'], 'the completed sibling routed before the pause');
  assert.deepEqual(paused.state.trail, ['prepare'], 'the interrupting step contributed nothing');
  assert.equal(paused.state.answer, null, 'and no writes were half-applied');
  assert.deepEqual(ran, ['prepare', 'ask:first']);

  // A second runner over the same store: the pause is durable, not a variable in memory.
  const restarted = createFlowRunner({ store, flows: [flow] });
  const resumed = await restarted.resume('t', 'yes');
  assert.equal(resumed.status, 'done');
  assert.equal(resumed.state.answer, 'yes');
  assert.deepEqual(resumed.state.trail, ['prepare', 'answer:yes', 'finish']);
  assert.deepEqual(ran, ['prepare', 'ask:first', 'ask:yes', 'finish'], 'the completed step never re-ran');
  assert.deepEqual(resumed.steps, ['ask', 'finish']);
});

test('the awaiting step re-runs and can interrupt again, bounded by the step budget', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const store = createMemoryFlowStore();
  const flow = defineFlow({
    name: 'twice',
    state: { answers: { initial: [], reducer: 'append' } },
    entry: 'ask',
    steps: { ask: async (_state, context) => (context.resume === 'done' ? { answers: ['done'] } : { interrupt: { round: context.resume === undefined ? 1 : 2 } }) },
    limits: { maxSteps: 4 },
  });
  const runner = createFlowRunner({ store, flows: [flow] });
  const first = await runner.run('twice', { threadId: 'twice' });
  assert.deepEqual(first.awaiting.values, [{ round: 1 }]);
  const second = await runner.resume('twice', 'more');
  assert.equal(second.status, 'paused');
  assert.deepEqual(second.awaiting.values, [{ round: 2 }]);
  const third = await runner.resume('twice', 'done');
  assert.equal(third.status, 'done');
  assert.deepEqual(third.state.answers, ['done']);
});

test('a failing step fails the run with its name and cause and applies nothing from its superstep', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const boom = new Error('the harness refused');
  const flow = defineFlow({
    name: 'failing',
    state: { trail: { initial: [], reducer: 'append' } },
    entry: ['good', 'bad'],
    steps: {
      good: async () => ({ trail: ['good'] }),
      bad: async () => { throw boom; },
    },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  await assert.rejects(runner.run('failing', { threadId: 'f' }), error => {
    assert.equal(error.code, 'flow_step_failed');
    assert.equal(error.step, 'bad');
    assert.equal(error.cause, boom, 'the cause is preserved, not stringified away');
    assert.equal(error.message.includes('bad'), true);
    return true;
  });
  const thread = await runner.status('f');
  assert.equal(thread.status, 'failed');
  assert.deepEqual(thread.state.trail, [], 'the sibling write is not half-applied');
  assert.equal(thread.error.step, 'bad');
  assert.equal(thread.error.code, 'flow_step_failed');
  await assert.rejects(runner.resume('f', true), error => error.code === 'flow_not_paused');
});

test('the max-steps guard stops a cycling flow with a coded error instead of hanging', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'loop',
    state: { hops: { initial: [], reducer: 'append' } },
    entry: 'spin',
    steps: { spin: async state => ({ hops: [state.hops.length] }) },
    transitions: { spin: 'spin' },
    limits: { maxSteps: 5 },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  await assert.rejects(runner.run('loop', { threadId: 'l' }), error => {
    assert.equal(error.code, 'flow_max_steps');
    assert.equal(error.details.limit, 5);
    assert.equal(error.details.executed, 5, 'exactly the budget ran, never one more');
    return true;
  });
  const thread = await runner.status('l');
  assert.equal(thread.state.hops.length, 5);
  assert.equal(thread.status, 'failed');
});

test('an oversized state fails the boundary with a coded error rather than truncating', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'bulk',
    state: { blob: { initial: '' }, trail: { initial: [], reducer: 'append' } },
    entry: 'small',
    steps: {
      small: async () => ({ trail: ['small'] }),
      bulk: async () => ({ blob: 'x'.repeat(2048) }),
    },
    transitions: { small: 'bulk' },
    limits: { maxStateBytes: 1024 },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  await assert.rejects(runner.run('bulk', { threadId: 'b' }), error => {
    assert.equal(error.code, 'flow_state_too_large');
    assert.equal(error.details.limit, 1024);
    assert.equal(error.details.bytes > 1024, true);
    return true;
  });
  const thread = await runner.status('b');
  assert.equal(thread.seq, 1, 'the last committed boundary is the last superstep that fit');
  assert.deepEqual(thread.state.trail, ['small']);
  assert.equal(thread.state.blob, '', 'the oversized write was not stored, not silently cut');
  assert.equal(thread.status, 'failed');
});

test('a state value that JSON cannot carry is refused instead of quietly dropped', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'unserializable',
    state: { counter: { initial: 0 } },
    entry: 'step',
    steps: { step: async () => ({ counter: undefined }) },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  await assert.rejects(runner.run('unserializable', { threadId: 'u' }), error => {
    assert.equal(error.code, 'flow_unserializable_state');
    assert.equal(error.details.field, 'counter');
    return true;
  });
});

test('a write to an undeclared field fails the superstep and leaves the state untouched', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'undeclared',
    state: { trail: { initial: [], reducer: 'append' } },
    entry: ['writer', 'typo'],
    steps: {
      writer: async () => ({ trail: ['writer'] }),
      typo: async () => ({ trali: ['typo'] }),
    },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  await assert.rejects(runner.run('undeclared', { threadId: 'd' }), error => {
    assert.equal(error.code, 'flow_unknown_field');
    assert.equal(error.details.field, 'trali');
    assert.equal(error.details.step, 'typo');
    return true;
  });
  const thread = await runner.status('d');
  assert.deepEqual(thread.state.trail, []);
});

test('a reducer that cannot take the write is reported with the field and the reducer', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'bad-reducer',
    state: { log: { initial: [], reducer: 'append' } },
    entry: 'step',
    steps: { step: async () => ({ log: { not: 'an array' } }) },
  });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  // `append` accepts a single element, so an object is appended as one entry rather than refused.
  const result = await runner.run('bad-reducer', { threadId: 'ok' });
  assert.deepEqual(result.state.log, [{ not: 'an array' }]);
  const broken = defineFlow({
    name: 'merge-into-array',
    state: { log: { initial: [], reducer: 'merge' } },
    entry: 'step',
    steps: { step: async () => ({ log: { not: 'mergeable' } }) },
  });
  const other = createFlowRunner({ store: createMemoryFlowStore(), flows: [broken] });
  await assert.rejects(other.run('merge-into-array', { threadId: 'nope' }), error => {
    assert.equal(error.code, 'flow_reducer_error');
    assert.equal(error.details.field, 'log');
    assert.equal(error.details.reducer, 'merge');
    return true;
  });
});

test('a malformed definition is refused when it is declared, not when it runs', async () => {
  const { defineFlow } = await engine();
  const base = { name: 'x', state: { n: { initial: 0 } }, entry: 'a', steps: { a: async () => ({}) } };
  const rejects = (definition, expected) => assert.throws(() => defineFlow(definition), error => {
    assert.equal(error.code, 'flow_invalid_definition');
    assert.match(error.message, expected);
    return true;
  });
  rejects({ state: base.state, steps: base.steps }, /name/);
  rejects({ ...base, steps: {} }, /at least one step/);
  rejects({ ...base, steps: { a: 'not a function' } }, /not a function/);
  rejects({ ...base, entry: 'missing' }, /entry/);
  rejects({ ...base, transitions: { a: 'missing' } }, /unknown step/);
  rejects({ ...base, transitions: { missing: 'a' } }, /unknown step/);
  rejects({ ...base, state: { goto: { initial: 1 } } }, /reserved/);
  rejects({ ...base, state: { n: { initial: 0, reducer: 'sum' } } }, /reducer/);
  rejects({ ...base, state: { n: {} } }, /initial/);
  rejects({ ...base, transitions: { a: 7 } }, /transition/);
});

test('run and resume refuse a thread that already exists, is not paused, or names no flow', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({ name: 'once', state: { n: { initial: 0 } }, entry: 'a', steps: { a: async () => ({ n: 1 }) } });
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow] });
  await assert.rejects(runner.run('missing', { threadId: 'z' }), error => error.code === 'flow_unknown_flow');
  await runner.run('once', { threadId: 'once' });
  await assert.rejects(runner.run('once', { threadId: 'once' }), error => {
    assert.equal(error.code, 'flow_thread_exists');
    assert.equal(error.details.status, 'done');
    return true;
  });
  await assert.rejects(runner.resume('once', 1), error => error.code === 'flow_not_paused');
  await assert.rejects(runner.resume('never-seen', 1), error => error.code === 'flow_unknown_thread');
  assert.deepEqual(runner.flows().map(entry => entry.name), ['once']);
  assert.deepEqual(runner.flows()[0].state, [{ field: 'n', reducer: 'replace', initial: 0 }]);
});

test('a thread whose stored state is not a JSON object is reported as corrupt', async () => {
  const { defineFlow, createFlowRunner } = await engine();
  const { createSqliteFlowStore } = await stores();
  const { createNativeHost } = await import('./support/host-native.mjs');
  const native = createNativeHost();
  const store = createSqliteFlowStore({ call: native.bridge.call });
  const flow = defineFlow({ name: 'corrupt', state: { n: { initial: 0 } }, entry: 'a', steps: { a: async () => ({ n: 1 }) } });
  const runner = createFlowRunner({ store, flows: [flow] });
  await runner.run('corrupt', { threadId: 'c' });
  // A state that is valid JSON but not an object is what the store cannot judge and the engine must.
  native.db.prepare("UPDATE flow_checkpoints SET state = '[]' WHERE thread_id = 'c' AND seq = 1").run();
  await assert.rejects(runner.status('c'), error => {
    assert.equal(error.code, 'flow_corrupt_checkpoint');
    assert.equal(error.details.field, 'state');
    return true;
  });
  native.db.close();
});

test('the engine reports step and checkpoint events in one deterministic order', async () => {
  const { defineFlow, createFlowRunner, END } = await engine();
  const { createMemoryFlowStore } = await stores();
  const flow = defineFlow({
    name: 'events',
    state: { trail: { initial: [], reducer: 'append' } },
    entry: ['a', 'b'],
    steps: { a: async () => ({ trail: ['a'] }), b: async () => ({ trail: ['b'] }), c: async () => ({ trail: ['c'] }) },
    transitions: { a: 'c', b: 'c', c: END },
  });
  const events = [];
  const runner = createFlowRunner({ store: createMemoryFlowStore(), flows: [flow], emit: event => events.push(event) });
  await runner.run('events', { threadId: 'e' });
  assert.deepEqual(events.map(event => event.type), [
    'flow.started',
    'flow.checkpoint',
    'flow.step.started', 'flow.step.started',
    'flow.step.finished', 'flow.step.finished',
    'flow.checkpoint',
    'flow.step.started',
    'flow.step.finished',
    'flow.checkpoint',
    'flow.finished',
  ]);
  assert.deepEqual(events.filter(event => event.type === 'flow.step.started').map(event => event.step), ['a', 'b', 'c']);
  const checkpoint = events.find(event => event.type === 'flow.checkpoint' && event.seq === 1);
  assert.deepEqual(checkpoint.steps, ['a', 'b']);
  assert.deepEqual(checkpoint.next, ['c']);
  assert.equal(checkpoint.status, 'running');
  assert.equal(checkpoint.bytes > 0, true);
  assert.equal(events.at(-1).status, 'done');
  for (const event of events) assert.equal('event' in event, false, 'the envelope owns the `event` key');
});

test('a paused thread is resumed from the store by a runner built after a crash', async () => {
  const { defineFlow, createFlowRunner, END } = await engine();
  const { createSqliteFlowStore } = await stores();
  const { createNativeHost } = await import('./support/host-native.mjs');
  const native = createNativeHost();
  const flow = defineFlow({
    name: 'durable',
    state: { trail: { initial: [], reducer: 'append' } },
    entry: ['prepare', 'ask'],
    steps: {
      prepare: async () => ({ trail: ['prepare'] }),
      ask: async (_state, context) => (context.resume === undefined ? { interrupt: { question: 'go?' } } : { trail: [`answer:${context.resume}`] }),
      finish: async () => ({ trail: ['finish'] }),
    },
    transitions: { prepare: 'finish', ask: 'finish', finish: END },
  });
  const first = createFlowRunner({ store: createSqliteFlowStore({ call: native.bridge.call }), flows: [flow] });
  const paused = await first.run('durable', { threadId: 'crash' });
  assert.equal(paused.status, 'paused');
  // A new store over the same database, as a restarted WebView would build it.
  const second = createFlowRunner({ store: createSqliteFlowStore({ call: native.bridge.call }), flows: [flow] });
  const status = await second.status('crash');
  assert.equal(status.status, 'paused');
  assert.deepEqual(status.state.trail, ['prepare']);
  assert.deepEqual(status.awaiting.steps, ['ask']);
  const done = await second.resume('crash', 'go');
  assert.equal(done.status, 'done');
  assert.deepEqual(done.state.trail, ['prepare', 'answer:go', 'finish']);
  native.db.close();
});
