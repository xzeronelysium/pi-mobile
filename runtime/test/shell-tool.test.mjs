// The agent-facing `shell` tool: Android's native `/system/bin/sh` (mksh), not bash.
//
// These tests pin the model-facing contract that the old upstream `bash` tool got wrong: the name,
// the honest description, the host-owned timeout (default, clamp and the value reported on
// timeout), bounded live output, spill diagnostics, honest exit/abort failures, and the fact that
// an already-aborted context never starts a process.

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  SHELL_EXTENSION_NAME,
  SHELL_TOOL_NAME,
  createAndroidShellTool,
  createAndroidShellTools,
} from '../src/shell-tool.mjs';

const LIMITS = {
  maxOutputBytes: 256 * 1024,
  maxStreamBytes: 8 * 1024 * 1024,
  defaultTimeoutMs: 30_000,
  maxTimeoutMs: 120_000,
};

const ok = (value = {}) => ({ ok: true, value: { exitCode: 0, timeoutMs: LIMITS.defaultTimeoutMs, ...value } });
const failure = (code, message = code, extra = {}) => ({ ok: false, error: Object.assign(new Error(message), { code, ...extra }) });

function createHarness({ exec } = {}) {
  const calls = [];
  const outputs = [];
  const diagnostics = [];
  const env = {
    cwd: '/workspace',
    async exec(command, options, context) {
      calls.push({ command, options, context });
      return exec ? exec(command, options, context) : ok();
    },
  };
  const api = {
    env,
    outputWindow: undefined,
    output: (text, skipped) => outputs.push({ text, skipped }),
    diagnostic: diagnostic => diagnostics.push(diagnostic),
  };
  const tool = createAndroidShellTool({ shellLimits: async () => LIMITS });
  return { tool, api, calls, outputs, diagnostics };
}

const contextOf = signal => (signal === undefined ? {} : { abortSignal: signal });

test('the tool is called shell, is one extension, and says it is mksh rather than bash', () => {
  const extension = createAndroidShellTools({ shellLimits: async () => LIMITS });
  assert.equal(extension.name, SHELL_EXTENSION_NAME);
  assert.equal(extension.tools.length, 1);
  const [tool] = extension.tools;
  assert.equal(tool.name, SHELL_TOOL_NAME);
  assert.notEqual(tool.name, 'bash');
  assert.match(tool.description, /mksh/i);
  assert.match(tool.description, /\/system\/bin\/sh/);
  assert.match(tool.description, /not bash/i);
  assert.match(tool.description, /app's own UID|not a sandbox/i);
  assert.equal(tool.parameters.type, 'object');
  assert.deepEqual(tool.parameters.required, ['command']);
  assert.equal(tool.parameters.additionalProperties, false);
  assert.deepEqual(Object.keys(tool.parameters.properties).sort(), ['command', 'timeout']);
});

test("the prompt section states the host's actual timeout numbers, not a copied pair", async () => {
  const extension = createAndroidShellTools({ shellLimits: async () => LIMITS });
  assert.equal(extension.sections.length, 1);
  const text = await extension.sections[0].render({}, {});
  assert.match(text, /mksh/i);
  assert.match(text, /30 seconds/);
  assert.match(text, /120 seconds/);
  assert.match(text, /not sandboxed/i);
});

test("the host's default and ceiling own the timeout, and the clamped value is what reaches the shell", async () => {
  const { tool, api, calls } = createHarness();
  await tool.execute({ command: 'printf hi' }, api, contextOf());
  assert.equal(calls[0].options.timeoutMs, LIMITS.defaultTimeoutMs);
  await tool.execute({ command: 'printf hi', timeout: 12 }, api, contextOf());
  assert.equal(calls[1].options.timeoutMs, 12_000);
  await tool.execute({ command: 'printf hi', timeout: 500 }, api, contextOf());
  assert.equal(calls[2].options.timeoutMs, LIMITS.maxTimeoutMs, 'clamped to the host ceiling before it is sent');
});

test('a timeout is reported with the deadline actually used, never "undefined seconds"', async () => {
  const { tool, api } = createHarness({
    exec: () => failure('timeout', 'Command timed out', { timeoutMs: LIMITS.defaultTimeoutMs }),
  });
  await assert.rejects(tool.execute({ command: 'sleep 99' }, api, contextOf()), error => {
    assert.match(error.message, /30 seconds/);
    assert.equal(/undefined/.test(error.message), false);
    return true;
  });

  const clamped = createHarness({
    exec: () => failure('timeout', 'Command timed out', { timeoutMs: LIMITS.maxTimeoutMs }),
  });
  await assert.rejects(clamped.tool.execute({ command: 'sleep 999', timeout: 500 }, clamped.api, contextOf()), error => {
    assert.match(error.message, /120 seconds/);
    return true;
  });
});

test('an already-aborted context never starts a command', async () => {
  const { tool, api, calls } = createHarness();
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(tool.execute({ command: 'printf bad' }, api, contextOf(controller.signal)), error => {
    assert.equal(error.name, 'AbortError');
    return true;
  });
  assert.equal(calls.length, 0, 'the shell was never called');
});

test('output streams to the harness, skipped counts survive, and the full-output file is named', async () => {
  const { tool, api, outputs, diagnostics } = createHarness({
    exec: async (_command, options) => {
      options.onOutput('kept\n', {}, { stream: 'stdout', skipped: { bytes: 7, newlines: 1, endsWithNewline: true } });
      return ok({ spillPath: '/cache/native-tmp/pi-spill-1.txt' });
    },
  });
  const result = await tool.execute({ command: 'yes' }, api, contextOf());
  assert.equal(outputs.length, 1);
  assert.equal(outputs[0].text, 'kept\n');
  assert.deepEqual(outputs[0].skipped, { bytes: 7, newlines: 1, endsWithNewline: true });
  assert.deepEqual(diagnostics.map(diagnostic => diagnostic.code), ['full_output']);
  assert.equal(result.details.spillPath, '/cache/native-tmp/pi-spill-1.txt');
  assert.equal(result.details.exitCode, 0);
});

test('bounded stream and spill caps are reported, not hidden', async () => {
  const { tool, api, diagnostics } = createHarness({
    exec: () => ok({
      spillPath: '/cache/native-tmp/pi-spill-2.txt',
      spillTruncated: true,
      streamTruncated: true,
      drainIncomplete: true,
    }),
  });
  const result = await tool.execute({ command: 'cat /dev/zero' }, api, contextOf());
  assert.deepEqual(diagnostics.map(diagnostic => diagnostic.code), [
    'full_output', 'full_output_capped', 'stream_capped', 'drain_incomplete',
  ]);
  assert.equal(result.details.spillTruncated, true);
  assert.equal(result.details.streamTruncated, true);
  assert.equal(result.details.drainIncomplete, true);
});

test('a non-zero exit is an error result, not an empty success', async () => {
  const { tool, api } = createHarness({ exec: () => ok({ exitCode: 7 }) });
  await assert.rejects(tool.execute({ command: 'exit 7' }, api, contextOf()), error => {
    assert.match(error.message, /code 7/);
    return true;
  });
});

test('the full-output file is named even when the command times out', async () => {
  const { tool, api, diagnostics } = createHarness({
    exec: () => failure('timeout', 'Command timed out', {
      timeoutMs: 15_000,
      spillPath: '/cache/native-tmp/pi-spill-3.txt',
    }),
  });
  await assert.rejects(tool.execute({ command: 'sleep 99', timeout: 15 }, api, contextOf()), error => {
    assert.match(error.message, /15 seconds/);
    return true;
  });
  assert.deepEqual(diagnostics.map(diagnostic => diagnostic.code), ['full_output']);
});

test('the spill threshold is bounded by the host stream cap, and the output window travels with the call', async () => {
  const { tool, calls, api } = createHarness();
  api.outputWindow = { maxBytes: 50 * 1024, maxLines: 2000, minIntervalMs: 10, bytesPerSecond: 1_000 };
  await tool.execute({ command: 'printf hi' }, api, contextOf());
  assert.equal(calls[0].options.spill.afterBytes, 50 * 1024);
  assert.equal(calls[0].options.spill.afterLines, 2000);
  assert.equal(calls[0].options.spill.maxBytes, LIMITS.maxStreamBytes);
  assert.deepEqual(calls[0].options.window, api.outputWindow);
});

test('invalid arguments and unusable host limits are refused before anything runs', async () => {
  const { tool, calls } = createHarness();
  for (const command of [undefined, '', '   ', 7, {}]) {
    await assert.rejects(tool.execute({ command }, {}, contextOf()), error => {
      assert.equal(error.code, 'invalid_command', JSON.stringify(command));
      return true;
    });
  }
  for (const timeout of [0, -1, Number.NaN, Number.POSITIVE_INFINITY, '12']) {
    await assert.rejects(tool.execute({ command: 'printf hi', timeout }, {}, contextOf()), error => {
      assert.equal(error.code, 'invalid_arguments', JSON.stringify(timeout));
      return true;
    });
  }
  assert.equal(calls.length, 0, 'nothing started');

  const unusable = createAndroidShellTool({ shellLimits: async () => ({ maxOutputBytes: 1024 }) });
  await assert.rejects(unusable.execute({ command: 'printf hi' }, {}, contextOf()), error => {
    assert.equal(error.code, 'shell_limits');
    return true;
  });
});
