// The flow layer as the app reaches it: through `window.PiNative`, on the real sqlite-backed host.
// These are the methods Kotlin's DurableRuntimeScripts evaluates and the frames its reducers read,
// so the payload shapes are asserted exactly.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createNativeHost } from './support/host-native.mjs';
import { defineFlow } from '../src/flow.mjs';
import { WORKSPACE_PROBE_FLOW_NAME } from '../src/flow-example.mjs';

async function openHost(options = {}) {
  const { createPiNative } = await import('../src/host.mjs');
  const native = createNativeHost();
  const host = createPiNative(native.bridge, options);
  await host.init({ workspace: '/workspace' });
  return { native, host };
}

const shapeOf = frame => Object.keys(frame).sort();
/**
 * The frames the bridge captured: `createBridge.emit` builds `{ event: type, ...fields }`, so the
 * envelope's key is `event`. A payload that carried its own `event` would overwrite the type, which
 * the exact sequence assertions below would catch.
 */
const keys = (...names) => names.concat('event').sort();
const flowFrames = native => native.events.filter(event => String(event.event).startsWith('flow') || event.event === 'flows');

test('the host exposes the flow operations and names every frame it emits', async () => {
  const { native, host } = await openHost();
  try {
    for (const name of ['runFlow', 'resumeFlow', 'flowStatus', 'flowThreads', 'flows']) {
      assert.equal(typeof host[name], 'function', name);
    }
    const listed = await host.flows();
    assert.deepEqual(Object.keys(listed), ['flows']);
    assert.deepEqual(listed.flows.map(entry => entry.name), [WORKSPACE_PROBE_FLOW_NAME]);
    const probe = listed.flows[0];
    assert.deepEqual(Object.keys(probe).sort(), ['entry', 'limits', 'name', 'state', 'steps']);
    assert.deepEqual(probe.entry, ['probe']);
    assert.deepEqual(probe.steps, ['probe', 'write', 'confirm', 'run', 'skip']);
    assert.deepEqual(probe.limits, { maxSteps: 16, maxStateBytes: 262144 });
    assert.deepEqual(probe.state.map(field => field.field).sort(), ['output', 'probe', 'report', 'script']);
    assert.deepEqual(probe.state.find(field => field.field === 'report'), { field: 'report', reducer: 'merge', initial: {} });
    const frame = native.events.filter(event => event.event === 'flows').at(-1);
    assert.deepEqual(frame.value, listed);
    assert.deepEqual(Object.keys(frame).sort(), ['event', 'value']);
  } finally { await host.close(); native.db.close(); }
});

test('a run is driven, checkpointed and observable through the host frames of one run', async () => {
  const { native, host } = await openHost();
  try {
    const paused = await host.runFlow({ flow: WORKSPACE_PROBE_FLOW_NAME, threadId: 'thread-1' });
    assert.deepEqual(Object.keys(paused).sort(), ['awaiting', 'flow', 'seq', 'state', 'status', 'steps', 'supersteps', 'threadId']);
    assert.equal(paused.status, 'paused');
    assert.equal(paused.seq, 3);
    assert.deepEqual(paused.steps, ['probe', 'write', 'confirm']);
    assert.deepEqual(paused.state.script, '/workspace/pi-flow-probe.sh');
    assert.deepEqual(paused.awaiting.steps, ['confirm']);

    const frames = flowFrames(native);
    assert.deepEqual(frames.map(frame => frame.event), [
      'flow.started',
      // The start is a boundary like any other: its checkpoint is the state seq 0 names.
      'flow.checkpoint',
      'flow.step.started', 'flow.step.finished', 'flow.checkpoint',
      'flow.step.started', 'flow.step.finished', 'flow.checkpoint',
      'flow.step.started', 'flow.step.finished',
      'flow.checkpoint', 'flow.interrupt', 'flow.finished',
    ]);
    const expected = {
      'flow.started': keys('threadId', 'flow', 'seq', 'ready', 'limits'),
      'flow.step.started': keys('threadId', 'flow', 'step', 'superstep', 'seq'),
      'flow.step.finished': keys('threadId', 'flow', 'step', 'superstep', 'seq', 'ok', 'durationMs', 'wrote'),
      'flow.checkpoint': keys('threadId', 'flow', 'seq', 'status', 'steps', 'next', 'bytes'),
      'flow.interrupt': keys('threadId', 'flow', 'step', 'superstep', 'seq', 'value'),
      'flow.finished': keys('threadId', 'flow', 'status', 'seq', 'supersteps', 'steps', 'state', 'awaiting'),
    };
    for (const frame of frames) assert.deepEqual(shapeOf(frame), expected[frame.event], frame.event);
    const checkpoint = frames.find(frame => frame.event === 'flow.checkpoint' && frame.seq === 1);
    assert.deepEqual(checkpoint.steps, ['probe']);
    assert.deepEqual(checkpoint.next, ['write']);
    assert.equal(checkpoint.status, 'running');
    assert.equal(checkpoint.bytes > 0, true);
    const finished = frames.at(-1);
    assert.equal(finished.status, 'paused');
    assert.equal(finished.supersteps, 3);
    assert.deepEqual(finished.awaiting.next, []);
    const started = frames[0];
    assert.deepEqual(started.ready, ['probe']);
    assert.deepEqual(started.limits, { maxSteps: 16, maxStateBytes: 262144 });

    const resumedFrames = [];
    const done = await host.resumeFlow({ threadId: 'thread-1', value: { approved: true } });
    assert.equal(done.status, 'done');
    assert.equal(done.awaiting, null);
    assert.deepEqual(done.state.report, { wrote: true, approved: true });
    resumedFrames.push(...native.events.slice(native.events.indexOf(frames.at(-1)) + 1));
    assert.deepEqual(resumedFrames.map(frame => frame.event), [
      'flow.resumed',
      'flow.step.started', 'flow.step.finished', 'flow.checkpoint',
      'flow.step.started', 'flow.step.finished', 'flow.checkpoint',
      'flow.finished',
    ]);
    assert.deepEqual(shapeOf(resumedFrames[0]), keys('threadId', 'flow', 'seq', 'steps', 'value'));
    assert.equal(resumedFrames.at(-1).status, 'done');
    assert.equal(resumedFrames.at(-1).supersteps, 2);

    const status = await host.flowStatus({ threadId: 'thread-1' });
    assert.deepEqual(Object.keys(status).sort(), ['awaiting', 'bytes', 'checkpoints', 'createdAt', 'error', 'flow', 'seq', 'state', 'status', 'steps', 'threadId']);
    assert.equal(status.status, 'done');
    assert.equal(status.seq, 5);
    assert.equal(status.error, null);
    // The fake host answers every non-probe command identically, so this pins the wiring — the
    // flow's output is the shell op's output — while the example's own test pins the interpreter's.
    assert.deepEqual(status.state.output.stdout, 'pi-native-probe');
    assert.equal(status.state.output.exitCode, 0);
    assert.deepEqual(status.checkpoints.map(entry => entry.seq), [5, 4, 3, 2, 1, 0]);
    assert.deepEqual(Object.keys(status.checkpoints[0]).sort(), ['bytes', 'createdAt', 'error', 'seq', 'status', 'steps']);
    assert.deepEqual(status.checkpoints[0].steps, ['run'], 'seq 5 is the step the resume ran');
    assert.deepEqual(status.checkpoints[0].status, 'done');
    assert.deepEqual(status.checkpoints[2].steps, ['confirm'], 'seq 3 is the boundary that paused');
    assert.equal(status.checkpoints[2].status, 'paused');
    const statusFrame = native.events.filter(event => event.event === 'flow.status').at(-1);
    assert.deepEqual(statusFrame.value, status);

    const threads = await host.flowThreads();
    assert.deepEqual(Object.keys(threads), ['threads']);
    assert.deepEqual(threads.threads.map(row => row.threadId), ['thread-1']);
    assert.deepEqual(Object.keys(threads.threads[0]).sort(), ['createdAt', 'error', 'flow', 'seq', 'status', 'steps', 'threadId', 'updatedAt']);
    assert.equal(threads.threads[0].status, 'done');
  } finally { await host.close(); native.db.close(); }
});

test('the checkpoints are rows in the app database the durable engine already uses', async () => {
  const { native, host } = await openHost();
  try {
    await host.runFlow({ flow: WORKSPACE_PROBE_FLOW_NAME, threadId: 'thread-db' });
    const rows = native.db.prepare('SELECT thread_id, seq, status, steps, awaiting FROM flow_checkpoints WHERE thread_id = ? ORDER BY seq').all('thread-db');
    assert.deepEqual(rows.map(row => [row.seq, row.status]), [[0, 'running'], [1, 'running'], [2, 'running'], [3, 'paused']]);
    assert.deepEqual(JSON.parse(rows[1].steps), ['probe']);
    assert.deepEqual(JSON.parse(rows[3].awaiting).steps, ['confirm']);
    // One database: the durable engine's own conversations live in the same file.
    const conversations = native.db.prepare("SELECT COUNT(*) AS n FROM sqlite_master WHERE type = 'table' AND name = 'conversations'").get().n;
    assert.equal(conversations, 1);
  } finally { await host.close(); native.db.close(); }
});

test('the platform-honest route survives the host: the script runs as /system/bin/sh <path>', async () => {
  const { native, host } = await openHost();
  try {
    await host.runFlow({ flow: WORKSPACE_PROBE_FLOW_NAME, threadId: 'thread-argv' });
    await host.resumeFlow({ threadId: 'thread-argv', value: true });
    const argv = native.requests.filter(request => request.op === 'shell.exec' && Array.isArray(request.args.command)).map(request => request.args.command);
    assert.deepEqual(argv, [['/system/bin/sh', '/workspace/pi-flow-probe.sh']]);
    assert.equal(native.requests.some(request => request.op === 'shell.exec' && request.args.command === '/workspace/pi-flow-probe.sh'), false);
    const written = native.requests.find(request => request.op === 'file.write');
    assert.equal(written.args.path, '/workspace/pi-flow-probe.sh');
    assert.match(written.args.text, /^#!\/system\/bin\/sh/);
  } finally { await host.close(); native.db.close(); }
});

test('a failing step is reported as a failed run, with the step and the reason, and stays readable', async () => {
  const failing = defineFlow({
    name: 'always-fails',
    state: { n: { initial: 0 } },
    entry: 'boom',
    steps: { boom: async () => { throw new Error('the shell refused the command'); } },
  });
  const { native, host } = await openHost({ flows: [failing] });
  try {
    await assert.rejects(host.runFlow({ flow: 'always-fails', threadId: 'failed-1' }), error => {
      assert.equal(error.code, 'flow_step_failed');
      assert.equal(error.step, 'boom');
      assert.match(error.message, /the shell refused the command/);
      return true;
    });
    const frames = flowFrames(native);
    assert.deepEqual(frames.map(frame => frame.event), [
      'flow.started',
      'flow.checkpoint',
      'flow.step.started', 'flow.step.finished', 'flow.finished',
    ]);
    const step = frames.find(frame => frame.event === 'flow.step.finished');
    assert.equal(step.ok, false);
    assert.equal(step.code, 'flow_step_failed');
    assert.equal(step.message, 'the shell refused the command');
    assert.deepEqual(shapeOf(step), keys('threadId', 'flow', 'step', 'superstep', 'seq', 'ok', 'durationMs', 'wrote', 'code', 'message'));
    const finished = frames.at(-1);
    assert.equal(finished.status, 'failed');
    assert.deepEqual(finished.error, { step: 'boom', code: 'flow_step_failed', message: 'the shell refused the command' });
    assert.deepEqual(finished.state, { n: 0 });

    const status = await host.flowStatus({ threadId: 'failed-1' });
    assert.equal(status.status, 'failed');
    assert.equal(status.seq, 0);
    assert.deepEqual(status.error, { step: 'boom', code: 'flow_step_failed', message: 'the shell refused the command' });
    assert.deepEqual(status.awaiting, null);
    await assert.rejects(host.resumeFlow({ threadId: 'failed-1', value: 1 }), /is failed, not paused/);
  } finally { await host.close(); native.db.close(); }
});

test('the flow operations refuse what they cannot do, in the file\'s own error style', async () => {
  const { native, host } = await openHost();
  try {
    await assert.rejects(host.runFlow({}), /flow required/);
    await assert.rejects(host.runFlow({ flow: 'not-a-flow', threadId: 'x' }), /Unknown flow: not-a-flow/);
    await assert.rejects(host.flowStatus({}), /threadId required/);
    await assert.rejects(host.flowStatus({ threadId: 'never' }), /Unknown flow thread: never/);
    await assert.rejects(host.resumeFlow({ threadId: 'never', value: 1 }), /Unknown flow thread: never/);
    await host.runFlow({ flow: WORKSPACE_PROBE_FLOW_NAME, threadId: 'again' });
    await assert.rejects(host.runFlow({ flow: WORKSPACE_PROBE_FLOW_NAME, threadId: 'again' }), /already exists/);
    await assert.rejects(host.resumeFlow({}), /threadId required/);
    await assert.rejects(host.flowThreads({ limit: -1 }), /limit/);
  } finally { await host.close(); native.db.close(); }
});

test('the flow operations need the durable host to be open first', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const host = createPiNative({ call: async () => null, emit: () => {} });
  await assert.rejects(host.runFlow({ flow: WORKSPACE_PROBE_FLOW_NAME, threadId: 'early' }), /not initialized/);
  await assert.rejects(host.flowStatus({ threadId: 'early' }), /not initialized/);
  await assert.rejects(host.flowThreads(), /not initialized/);
  // The catalogue itself is static and readable before init.
  assert.deepEqual((await host.flows()).flows.map(entry => entry.name), [WORKSPACE_PROBE_FLOW_NAME]);
});
