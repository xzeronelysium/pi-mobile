// The one built-in example flow: a real multi-step flow over the capabilities this runtime already
// has (the shell, the file ops), and honest about the platform: the script it writes is run as
// `/system/bin/sh <path>`, never by executing the file.

import test from 'node:test';
import assert from 'node:assert/strict';

const example = () => import('../src/flow-example.mjs');
const engine = () => import('../src/flow.mjs');
const stores = () => import('../src/flow-store.mjs');

/** The three ops the example is allowed to use, with the values a device would return. */
function fakeCall() {
  const requests = [];
  const files = new Map();
  const call = async (op, args = {}) => {
    requests.push({ op, args });
    if (op === 'shell.exec') {
      const argv = Array.isArray(args.command);
      return { stdout: argv ? 'flow-script-ok' : 'pi-flow-ok', stderr: '', exitCode: 0, timedOut: false, cancelled: false, drainIncomplete: false };
    }
    if (op === 'file.write') { files.set(args.path, args.text ?? ''); return null; }
    if (op === 'file.stat') return { exists: files.has(args.path), kind: 'file', size: files.get(args.path)?.length ?? 0, mtimeMs: 0 };
    throw new Error(`Unexpected op ${op}`);
  };
  return { call, requests, files };
}

const open = async (options = {}) => {
  const { WORKSPACE_PROBE_FLOW, WORKSPACE_PROBE_FLOW_NAME } = await example();
  const { createFlowRunner } = await engine();
  const { createMemoryFlowStore } = await stores();
  const fake = fakeCall();
  const runner = createFlowRunner({
    store: createMemoryFlowStore(),
    flows: [WORKSPACE_PROBE_FLOW],
    context: { call: fake.call, workspace: '/workspace', ...options.context },
  });
  return { ...fake, runner, name: WORKSPACE_PROBE_FLOW_NAME };
};

test('the example flow writes its script, asks before running it, and runs it through the interpreter', async () => {
  const { PROBE_SCRIPT_TEXT, SHELL_EXECUTABLE } = await example();
  const { runner, name, requests, files } = await open();

  const paused = await runner.run(name, { threadId: 'probe-1' });
  assert.equal(paused.status, 'paused', 'the flow stops for the human decision');
  assert.deepEqual(paused.steps, ['probe', 'write', 'confirm']);
  assert.deepEqual(paused.awaiting.steps, ['confirm']);
  assert.equal(paused.awaiting.values[0].path, '/workspace/pi-flow-probe.sh');
  assert.equal(paused.state.script, '/workspace/pi-flow-probe.sh');
  assert.equal(paused.state.report.wrote, true);
  assert.equal(files.get('/workspace/pi-flow-probe.sh'), PROBE_SCRIPT_TEXT);
  // The probe is a command string through the same shell op, before any file exists.
  assert.deepEqual(requests.filter(request => request.op === 'shell.exec').map(request => request.args.command), ['printf pi-flow-ok']);
  assert.equal(paused.state.probe.shell, 'pi-flow-ok');

  const done = await runner.resume('probe-1', { approved: true });
  assert.equal(done.status, 'done');
  assert.equal(done.state.report.approved, true);
  const argv = requests.filter(request => Array.isArray(request.args.command));
  assert.deepEqual(argv.map(request => request.args.command), [[SHELL_EXECUTABLE, '/workspace/pi-flow-probe.sh']]);
  assert.deepEqual(done.state.output, {
    script: '/workspace/pi-flow-probe.sh', exitCode: 0, stdout: 'flow-script-ok', stderr: '', timedOut: false, cancelled: false,
  });
  // Nothing ever asked the platform to execute the app-written file itself: the interpreter is
  // always argv[0], because a direct exec of it is what Android refuses.
  for (const request of requests) {
    if (request.op !== 'shell.exec' || !Array.isArray(request.args.command)) continue;
    assert.notEqual(request.args.command[0], '/workspace/pi-flow-probe.sh');
  }
});

test('declining the interrupt takes the skip branch and never runs the script', async () => {
  const { runner, name, requests, files } = await open();
  await runner.run(name, { threadId: 'probe-2' });
  const done = await runner.resume('probe-2', false);
  assert.equal(done.status, 'done');
  assert.equal(done.state.report.approved, false);
  assert.equal(done.state.output.skipped, true);
  assert.deepEqual(done.steps, ['confirm', 'skip']);
  assert.equal(requests.some(request => Array.isArray(request.args.command)), false);
  assert.equal(files.has('/workspace/pi-flow-probe.sh'), true, 'the script was written, just not run');
});

test('the script text is a shell script, which is what makes it runnable without an executable bit', async () => {
  const { PROBE_SCRIPT_TEXT, SHELL_EXECUTABLE } = await example();
  assert.match(PROBE_SCRIPT_TEXT, /^#!\/system\/bin\/sh\n/);
  assert.match(PROBE_SCRIPT_TEXT, /flow-script-ok/);
  assert.equal(SHELL_EXECUTABLE, '/system/bin/sh');
  // The reason is in the module, not only in a commit message.
  const source = await import('node:fs/promises').then(fs => fs.readFile(new URL('../src/flow-example.mjs', import.meta.url), 'utf8'));
  assert.match(source, /noexec/);
  assert.match(source, /SELinux|W\^X/);
});

test('the example flow is honest when it has no workspace to work in', async () => {
  const { runner, name } = await open({ context: { workspace: undefined } });
  await assert.rejects(runner.run(name, { threadId: 'probe-3' }), error => {
    assert.equal(error.code, 'flow_step_failed');
    assert.equal(error.step, 'probe');
    assert.match(error.message, /workspace/);
    return true;
  });
});
