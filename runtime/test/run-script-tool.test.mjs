// The agent-facing `run_script` durable tool.
//
// WHY: on Android the app cannot execute a file it wrote. A direct `ProcessBuilder` of a script in
// the app files dir fails with `error=13, Permission denied` (SELinux W^X: since Android 10 an app
// process may not execute `app_data_file`), and shared storage (`/storage/emulated`) is mounted
// `noexec`, so a script in the workspace cannot be executed directly either. `/system/bin/sh <path>`
// succeeds in BOTH places, because a script is *data* to the interpreter and no exec of the script
// happens. The agent's own shell tool takes its command as a string, so its `./tool.sh` is executed
// by mksh inside our `sh -c` and the app never sees the path — the one thing it cannot fix.
//
// These tests pin the whole contract: the schema, the ARGV shape (interpreter first, resolved path
// second, then the passthrough arguments unparsed), an honest exit code, a timeout and a
// cancellation surfaced as such instead of as an empty success, output that reached the shell's own
// per-stream cap reported as cut, a drain that never reached EOF reported too, the workspace
// confinement of the path, refusing a path that is not a file, and — the part that makes it a
// *tool* — registration and visibility in the real harness.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fauxAssistantMessage, fauxToolCall } from '@earendil-works/pi-ai/providers/faux';
import { createRegistry } from '@earendil-works/pi-durable';
import {
  RUN_SCRIPT_EXTENSION_NAME,
  RUN_SCRIPT_TOOL_NAME,
  SHELL_EXECUTABLE,
  createRunScriptTool,
  createRunScriptTools,
} from '../src/run-script-tool.mjs';
import { withFauxHarness } from './support/durable.mjs';

/**
 * What the reference host advertises for the shell (`shell.capabilities`).
 *
 * The tool must run under the *host's* numbers — Kotlin owns them (`backend/ShellLimits.kt`) and the
 * tool reads them through the bridge — so the tests below take them from this advertisement instead
 * of importing a second copy out of the module.
 */
const LIMITS = { maxOutputBytes: 256 * 1024, defaultTimeoutMs: 30_000, maxTimeoutMs: 120_000 };
/** A deliberately different advertisement: the tool must follow the host, never a copy of its own. */
const OTHER_LIMITS = { maxOutputBytes: 4 * 1024, defaultTimeoutMs: 5_000, maxTimeoutMs: 60_000 };

const tick = async (turns = 2) => { for (let index = 0; index < turns; index += 1) await new Promise(resolve => setTimeout(resolve, 0)); };

/**
 * The AndroidNative contract for `shell.exec`/`shell.cancel` plus the `file.stat` the tool uses to
 * refuse a path that is not a file.
 *
 * `script` decides what the host answers for `shell.exec`: an object, or a function of the request.
 * `{ hold: true }` answers nothing until `shell.cancel` arrives for that request, which is exactly
 * what the Kotlin host does for a cancelled run — so a test can abort mid-run.
 * `files` is the workspace's stat table: path -> kind.
 */
function createBridge({ script = {}, root = '/work', files, limits } = {}) {
  const calls = [];
  const cancelled = [];
  const pending = new Map();
  const table = files ?? new Map([[`${root}/tool.sh`, 'file']]);
  const plan = args => (typeof script === 'function' ? script(args) : script);
  const bridge = {
    async call(op, args = {}) {
      calls.push({ op, args });
      if (op === 'shell.capabilities') {
        if (limits === null) throw new Error('Unexpected op shell.capabilities');
        if (typeof limits === 'function') return limits();
        return limits ?? LIMITS;
      }
      if (op === 'file.stat') {
        const kind = table.get(args.path);
        return kind === undefined ? { exists: false } : { exists: true, kind, size: 32, mtimeMs: 0 };
      }
      if (op === 'shell.exec') {
        const spec = plan(args);
        if (spec.hold === true) {
          return new Promise(resolve => pending.set(args.requestId, () => resolve({
            stdout: spec.stdout ?? '', stderr: spec.stderr ?? '', exitCode: null,
            timedOut: false, cancelled: true, drainIncomplete: false,
          })));
        }
        return spec;
      }
      if (op === 'shell.cancel') {
        cancelled.push(args.requestId);
        pending.get(args.requestId)?.();
        pending.delete(args.requestId);
        return { cancelled: true };
      }
      throw new Error(`Unexpected op ${op}`);
    },
  };
  return {
    bridge,
    calls,
    cancelled,
    execs: () => calls.filter(call => call.op === 'shell.exec'),
    stats: () => calls.filter(call => call.op === 'file.stat'),
    advertisements: () => calls.filter(call => call.op === 'shell.capabilities'),
  };
}

function createTool(options = {}) {
  const host = createBridge(options);
  const tool = createRunScriptTool({
    call: host.bridge.call,
    workspaceRoot: () => (typeof options.root === 'string' ? options.root : '/work'),
    // Exactly how `host.mjs` wires it: the limits come from the host, through the bridge.
    shellLimits: () => host.bridge.call('shell.capabilities'),
  });
  return { host, tool };
}

const contextOf = signal => (signal === undefined ? {} : { abortSignal: signal });
const textOf = result => (result.content ?? []).filter(part => part.type === 'text').map(part => part.text).join('');
const ok = (stdout, extra = {}) => ({ stdout, stderr: '', exitCode: 0, timedOut: false, cancelled: false, drainIncomplete: false, ...extra });

test('run_script is one durable extension holding one tool with a precise schema', () => {
  const extension = createRunScriptTools({ call: () => {} });
  assert.equal(extension.name, RUN_SCRIPT_EXTENSION_NAME);
  assert.equal(extension.tools.length, 1);
  const [tool] = extension.tools;
  assert.equal(tool.name, RUN_SCRIPT_TOOL_NAME);
  assert.equal(tool.parameters.type, 'object');
  assert.deepEqual(tool.parameters.required, ['path']);
  assert.equal(tool.parameters.additionalProperties, false);
  for (const name of ['path', 'args', 'timeoutMs']) {
    assert.equal(name in tool.parameters.properties, true, name);
  }
  // The description has to say WHY, or the model will keep trying a direct exec and hitting a wall
  // it cannot see the edges of.
  assert.match(tool.description, /SELinux|noexec/i);
  assert.match(tool.description, /interpreter|\/system\/bin\/sh/i);
  assert.match(tool.description, /Permission denied|execute/i);
});

test('runs the script through the shell interpreter as argv, never as a direct exec', async () => {
  const { host, tool } = createTool({ script: ok('hello\n') });
  const result = await tool.execute({ path: 'tool.sh' }, {}, contextOf());

  const exec = host.execs()[0].args;
  assert.deepEqual(exec.command, [SHELL_EXECUTABLE, '/work/tool.sh'],
    'the interpreter runs the script: argv[0] is the shell, argv[1] the resolved path');
  assert.equal(Array.isArray(exec.command), true, 'an argv command, never a script string');
  assert.equal(exec.command[0], SHELL_EXECUTABLE, 'the file is never the thing that is exec-ed');
  assert.equal(exec.cwd, undefined, 'no cwd is sent: the host runs it in the workspace');
  assert.equal(typeof exec.requestId, 'string');
  assert.equal(exec.requestId.length > 0, true);

  assert.equal(result.isError, undefined);
  const text = textOf(result);
  assert.match(text, /\/system\/bin\/sh \/work\/tool\.sh/);
  assert.match(text, /exit code: 0/);
  assert.ok(text.includes('hello'), text);
  assert.deepEqual(result.details, {
    path: '/work/tool.sh',
    argv: [SHELL_EXECUTABLE, '/work/tool.sh'],
    exitCode: 0,
    timeoutMs: LIMITS.defaultTimeoutMs,
    stdoutBytes: 6,
    stderrBytes: 0,
    timedOut: false,
    cancelled: false,
    drainIncomplete: false,
    streamTruncated: false,
    stdoutTruncated: false,
    stderrTruncated: false,
    truncated: false,
    truncatedStreams: [],
  });
});

test("passes the script's own arguments through unparsed", async () => {
  const { host, tool } = createTool({ script: ok('ok') });
  await tool.execute({ path: 'tool.sh', args: ['one', 'two words', '--flag=x', ''] }, {}, contextOf());
  assert.deepEqual(host.execs()[0].args.command, [SHELL_EXECUTABLE, '/work/tool.sh', 'one', 'two words', '--flag=x', '']);
});

test('an absolute script path inside the workspace is honoured', async () => {
  const files = new Map([['/work/deep/build.sh', 'file']]);
  const { host, tool } = createTool({ files, script: ok('built') });
  const result = await tool.execute({ path: '/work/deep/build.sh' }, {}, contextOf());
  assert.deepEqual(host.execs()[0].args.command, [SHELL_EXECUTABLE, '/work/deep/build.sh']);
  assert.equal(result.details.path, '/work/deep/build.sh');
});

test('a non-zero exit code is surfaced with its output, never hidden', async () => {
  const { tool } = createTool({ script: { stdout: 'partial\ndone\n', stderr: 'boom: bad thing\n', exitCode: 3, timedOut: false, cancelled: false, drainIncomplete: false } });
  const result = await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.equal(result.isError, undefined, 'the script ran: its own exit code is data, not a tool failure');
  assert.equal(result.details.exitCode, 3);
  const text = textOf(result);
  assert.match(text, /exit code: 3/);
  assert.ok(text.includes('boom: bad thing'), text);
  // The failure is made salient without pretending the run itself failed to happen.
  assert.deepEqual(result.diagnostics, [{ severity: 'warn', code: 'exit_code', message: 'the script exited with code 3' }]);
});

test('a timeout is surfaced as a timeout, carrying the output produced before it was killed', async () => {
  const { host, tool } = createTool({ script: { stdout: 'half a job\n', stderr: '', exitCode: null, timedOut: true, cancelled: false, drainIncomplete: false } });
  const result = await tool.execute({ path: 'tool.sh', timeoutMs: 1_500 }, {}, contextOf());
  assert.equal(host.execs()[0].args.timeoutMs, 1_500);
  assert.equal(result.isError, true, 'a timeout is not an empty success');
  assert.equal(result.details.timedOut, true);
  assert.equal(result.details.exitCode, null);
  assert.equal(result.details.stdoutBytes, 11);
  const text = textOf(result);
  assert.match(text, /timed out/i);
  assert.match(text, /1500/);
  assert.ok(text.includes('half a job'), 'the output produced before the kill is still shown');
  assert.equal(result.diagnostics[0].code, 'timeout');
  assert.equal(result.diagnostics[0].severity, 'error');
});

test('a run the host cancelled is surfaced as cancelled', async () => {
  const { tool } = createTool({ script: { stdout: 'stopped here\n', stderr: '', exitCode: null, timedOut: false, cancelled: true, drainIncomplete: false } });
  const result = await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.equal(result.isError, true);
  assert.equal(result.details.cancelled, true);
  assert.equal(result.details.timedOut, false);
  assert.match(textOf(result), /cancel/i);
  assert.ok(textOf(result).includes('stopped here'), textOf(result));
  assert.equal(result.diagnostics[0].code, 'cancelled');
});

test('an aborted run cancels the host command and rejects with an AbortError', async () => {
  const { host, tool } = createTool({ script: { hold: true } });
  const controller = new AbortController();
  const pending = tool.execute({ path: 'tool.sh' }, {}, contextOf(controller.signal));
  await tick(3);
  assert.equal(host.execs().length, 1);
  const { requestId } = host.execs()[0].args;
  controller.abort();
  await assert.rejects(pending, error => {
    assert.equal(error.name, 'AbortError');
    return true;
  });
  await tick(2);
  assert.deepEqual(host.cancelled, [requestId], 'the host is told to stop, not left running');
});

test('a run already aborted before it starts never reaches the host', async () => {
  const { host, tool } = createTool({ script: ok('x') });
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(tool.execute({ path: 'tool.sh' }, {}, contextOf(controller.signal)), error => {
    assert.equal(error.name, 'AbortError');
    return true;
  });
  assert.equal(host.execs().length, 0);
});

test("output the host flagged as cut is reported as cut, not as complete", async () => {
  const stdout = 'x'.repeat(LIMITS.maxOutputBytes);
  const { tool } = createTool({ script: ok(stdout, { stdoutTruncated: true }) });
  const result = await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.equal(result.details.truncated, true);
  assert.deepEqual(result.details.truncatedStreams, ['stdout']);
  assert.equal(result.details.stdoutBytes, LIMITS.maxOutputBytes);
  const text = textOf(result);
  assert.match(text, /cap/i);
  assert.ok(text.includes(String(LIMITS.maxOutputBytes)), text.slice(-400));
  assert.ok(text.includes('stdout'), text.slice(-400));
  assert.equal(result.diagnostics[0].code, 'shell_cap');
  assert.equal(result.diagnostics[0].severity, 'warn');
  // The output is still returned in full: the tool never silently drops what the shell did capture.
  assert.ok(text.includes(stdout), 'the captured output is still there');

  const stderr = createTool({ script: ok('', { stderr: 'e'.repeat(LIMITS.maxOutputBytes), stderrTruncated: true }) });
  const stderrResult = await stderr.tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.deepEqual(stderrResult.details.truncatedStreams, ['stderr']);
});

test('an exactly-full stream the host did not cut is not misreported as truncated', async () => {
  // The old heuristic compared the decoded byte length with the cap. A stream that is exactly at
  // the cap was never cut, and a cut inside a multibyte character can decode just below it, so only
  // the host's own flags are authoritative.
  const stdout = 'x'.repeat(LIMITS.maxOutputBytes);
  const { tool } = createTool({ script: ok(stdout) });
  const result = await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.equal(result.details.truncated, false);
  assert.deepEqual(result.details.truncatedStreams, []);
  assert.equal(result.details.stdoutBytes, LIMITS.maxOutputBytes);
});

test('a drain that never reached EOF is reported honestly', async () => {
  const { tool } = createTool({ script: ok('some output\n', { drainIncomplete: true }) });
  const result = await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.equal(result.details.drainIncomplete, true);
  assert.equal(result.isError, undefined, 'the script exited on its own: this is a caveat, not a failure');
  const text = textOf(result);
  assert.match(text, /more output may/i);
  assert.ok(text.includes('some output'), text);
  assert.equal(result.diagnostics[0].code, 'drain_incomplete');
  assert.equal(result.diagnostics[0].severity, 'warn');
});

test('a path outside the workspace is refused and nothing runs', async () => {
  const { host, tool } = createTool({ script: ok('x') });
  for (const path of ['../escape.sh', '../../etc/passwd', '/etc/passwd', '/work/../escape.sh', '/work/../../root/x.sh']) {
    await assert.rejects(tool.execute({ path }, {}, contextOf()), error => {
      assert.equal(error.code, 'workspace_escape', `${path}: ${error.message}`);
      return true;
    }, path);
  }
  assert.equal(host.execs().length, 0);
  assert.equal(host.stats().length, 0, 'a refused path is never even stat-ed');
});

test('a missing path and a path that is not a file are refused before anything runs', async () => {
  const { host, tool } = createTool({ script: ok('x') });
  await assert.rejects(tool.execute({ path: 'missing.sh' }, {}, contextOf()), error => {
    assert.equal(error.code, 'not_found');
    assert.match(error.message, /missing\.sh/);
    return true;
  });
  // A directory is not a script, even though the interpreter would read it and fail confusingly.
  const directory = createTool({ files: new Map([['/work/sub', 'directory']]), script: ok('x') });
  await assert.rejects(directory.tool.execute({ path: 'sub' }, {}, contextOf()), error => {
    assert.equal(error.code, 'not_a_file');
    return true;
  });
  assert.equal(directory.host.execs().length, 0);
  assert.equal(host.execs().length, 0);
});

test('any file the interpreter can read is accepted, not only a .sh script', async () => {
  // Deliberately not over-restricted: mksh reads any text file, and the executable bit is
  // irrelevant because the interpreter is what is executed.
  const files = new Map([['/work/notes.txt', 'file'], ['/work/no-extension', 'file']]);
  const { host, tool } = createTool({ files, script: ok('ran') });
  for (const path of ['notes.txt', 'no-extension']) {
    await tool.execute({ path }, {}, contextOf());
  }
  assert.deepEqual(host.execs().map(call => call.args.command[1]), ['/work/notes.txt', '/work/no-extension']);
});

test("the timeout defaults to the host's own default and is clamped to its ceiling", async () => {
  const { host, tool } = createTool({ script: ok('x') });
  await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.equal(host.execs()[0].args.timeoutMs, LIMITS.defaultTimeoutMs);
  await tool.execute({ path: 'tool.sh', timeoutMs: LIMITS.maxTimeoutMs * 10 }, {}, contextOf());
  assert.equal(host.execs()[1].args.timeoutMs, LIMITS.maxTimeoutMs, 'the host would clamp it anyway; say so here');
});

test("the caps the tool runs under are the host's advertised ones, not a second copy of them", async () => {
  // The numbers used to live here as well as in the host that enforces them, and the two disagreed:
  // this module described the ceiling as a refusal while the host silently clamped to it. The host
  // owns them (`backend/ShellLimits.kt`), advertises them through `shell.capabilities`, and the tool
  // reports what the host will really do.
  const { host, tool } = createTool({ script: ok('y'.repeat(OTHER_LIMITS.maxOutputBytes), { stdoutTruncated: true }), limits: OTHER_LIMITS });
  await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.equal(host.execs()[0].args.timeoutMs, OTHER_LIMITS.defaultTimeoutMs);

  const result = await tool.execute({ path: 'tool.sh', timeoutMs: OTHER_LIMITS.maxTimeoutMs * 100 }, {}, contextOf());
  assert.equal(host.execs()[1].args.timeoutMs, OTHER_LIMITS.maxTimeoutMs, 'clamped to the advertised ceiling');
  assert.equal(result.details.truncated, true, 'the host advertises a 4 KiB per-stream cap');
  assert.deepEqual(result.details.truncatedStreams, ['stdout']);
  assert.ok(textOf(result).includes(String(OTHER_LIMITS.maxOutputBytes)), textOf(result).slice(-400));
  assert.equal(result.diagnostics[0].code, 'shell_cap');
});

test('the description keeps no copy of the numbers the host owns', async () => {
  const { tool } = createTool({ script: ok('x') });
  for (const number of [LIMITS.maxOutputBytes, LIMITS.defaultTimeoutMs, LIMITS.maxTimeoutMs]) {
    assert.equal(tool.description.includes(String(number)), false, `the tool text still carries ${number}`);
  }
});

test('the limits are read from the host once, not on every call', async () => {
  const { host, tool } = createTool({ script: ok('x') });
  await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.equal(host.advertisements().length, 1, 'one round trip, reused for the life of the tool');
});

test('a transient limits failure is retried, not cached for the life of the tool', async () => {
  let attempts = 0;
  const { host, tool } = createTool({
    script: ok('x'),
    limits: () => {
      attempts += 1;
      if (attempts === 1) throw new Error('TRANSIENT_BRIDGE_FAILURE');
      return LIMITS;
    },
  });
  await assert.rejects(tool.execute({ path: 'tool.sh' }, {}, contextOf()), error => {
    assert.equal(error.code, 'shell_limits');
    return true;
  });
  await tool.execute({ path: 'tool.sh' }, {}, contextOf());
  assert.equal(attempts, 2, 'the rejected read was cleared, so the next call tried again');
  assert.equal(host.advertisements().length, 2);
});

test('a host that advertises no usable limits is refused, never guessed at', async () => {
  const unusable = [
    null,
    {},
    { maxOutputBytes: 1024 },
    { maxOutputBytes: 1024, defaultTimeoutMs: 5_000 },
    { maxOutputBytes: 0, defaultTimeoutMs: 5_000, maxTimeoutMs: 10_000 },
    { maxOutputBytes: 1024, defaultTimeoutMs: 5_000, maxTimeoutMs: 1_000 },
    { maxOutputBytes: 'lots', defaultTimeoutMs: 5_000, maxTimeoutMs: 10_000 },
  ];
  for (const limits of unusable) {
    const { host, tool } = createTool({ script: ok('x'), limits });
    await assert.rejects(tool.execute({ path: 'tool.sh' }, {}, contextOf()), error => {
      assert.equal(error.code, 'shell_limits', JSON.stringify(limits));
      return true;
    });
    assert.equal(host.execs().length, 0, `nothing may run without the host's limits: ${JSON.stringify(limits)}`);
  }
});

test('invalid arguments are refused rather than guessed at', async () => {
  const { host, tool } = createTool({ script: ok('x') });
  for (const path of [undefined, '', '   ', 7, {}]) {
    await assert.rejects(tool.execute({ path }, {}, contextOf()), error => {
      assert.equal(error.code, 'invalid_path', JSON.stringify(path));
      return true;
    });
  }
  for (const args of ['one', { 0: 'one' }, [{}], [null]]) {
    await assert.rejects(tool.execute({ path: 'tool.sh', args }, {}, contextOf()), error => {
      assert.equal(error.code, 'invalid_arguments', JSON.stringify(args));
      return true;
    });
  }
  for (const timeoutMs of [0, -1, 1.5, 'soon', Number.POSITIVE_INFINITY]) {
    await assert.rejects(tool.execute({ path: 'tool.sh', timeoutMs }, {}, contextOf()), error => {
      assert.equal(error.code, 'invalid_arguments', String(timeoutMs));
      return true;
    });
  }
  assert.equal(host.execs().length, 0);
});

test('a bridge failure from the shell is surfaced as itself, not swallowed', async () => {
  const { tool } = createTool({
    script: () => { throw Object.assign(new Error('Failed to start /system/bin/sh'), { code: 'spawn_error' }); },
  });
  await assert.rejects(tool.execute({ path: 'tool.sh' }, {}, contextOf()), error => {
    assert.equal(error.code, 'spawn_error');
    assert.match(error.message, /Failed to start/);
    return true;
  });
});

test('run_script is registered in the registry and visible to the harness, and a call really runs', async () => {
  const host = createBridge({ script: ok('SCRIPT-OUTPUT-MARKER\n'), root: '/work' });
  const registry = createRegistry();
  registry.install(createRunScriptTools({
    call: host.bridge.call,
    workspaceRoot: () => '/work',
    // The limits are the host's, read through the same op the real host answers.
    shellLimits: () => host.bridge.call('shell.capabilities'),
  }));

  await withFauxHarness(async ({ context, faux, root }) => {
    const agent = await root.agent(context);
    assert.equal(agent.tools.some(tool => tool.name === RUN_SCRIPT_TOOL_NAME), true,
      `offered tools: ${agent.tools.map(tool => tool.name).join(', ')}`);
    assert.equal(agent.extensions.some(extension => extension.name === RUN_SCRIPT_EXTENSION_NAME), true);

    faux.setResponses([
      fauxAssistantMessage([fauxToolCall(RUN_SCRIPT_TOOL_NAME, { path: 'tool.sh', args: ['--fast'] })], { stopReason: 'toolUse' }),
      fauxAssistantMessage('ran'),
    ]);
    const settled = await (await root.submit({ type: 'input', content: 'run tool.sh', requestId: 'r1' }, context)).wait(context);
    assert.equal(settled.status, 'done');

    const page = await root.entries({ order: 'ascending' }, 200, undefined, context);
    const result = page.items.find(item => item.kind === 'pi.tool-result');
    assert.notEqual(result, undefined, JSON.stringify(page.items.map(item => item.kind)));
    assert.ok(JSON.stringify(result).includes('SCRIPT-OUTPUT-MARKER'), 'the script output reached the transcript');
    assert.deepEqual(host.execs()[0].args.command, [SHELL_EXECUTABLE, '/work/tool.sh', '--fast']);
  }, { registry });
});

test('the tool module reaches no Node API, so the WebView bundle stays Node-free', async () => {
  const source = await readFile(new URL('../src/run-script-tool.mjs', import.meta.url), 'utf8');
  for (const banned of ['node:', 'require(', 'process.', 'Buffer.']) {
    assert.equal(source.includes(banned), false, banned);
  }
});
