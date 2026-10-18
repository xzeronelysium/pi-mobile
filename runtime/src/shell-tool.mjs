// `shell`: the agent's handle on Android's native shell.
//
// This replaces Pi Durable's `bash` tool.  The device does not ship bash: `/system/bin/sh` is mksh,
// so a tool that calls itself "bash" invites bashisms that cannot work (arrays, process
// substitution, brace expansion) and hides the fact that the process is app-privileged.
//
// The execution side is the portable `AndroidExecutionEnv`; this module only owns the model-facing
// contract: the name, the honest description, the timeout in seconds, the output window/spill, and
// turning the host's outcome flags into a result the model can read.
//
// The whole module runs in the WebView: no Node API, only globals it has plus the ExecutionEnv
// handed to the call.

import { defineExtension, defineTool } from '@earendil-works/pi-durable';
import {
  SHELL_EXECUTABLE,
  SHELL_KIND,
  createShellLimitsReader,
  formatSeconds,
  requestedTimeoutMillis,
} from './shell-info.mjs';

/** The model-facing name. `bash` was a lie on this device; `shell` is what it actually is. */
export const SHELL_TOOL_NAME = 'shell';
/** The extension that carries it; installed alongside the file tools and selected by default. */
export const SHELL_EXTENSION_NAME = 'android-shell';

/** The harness's own tail-retention thresholds, matched so the spill file is the full output. */
export const DEFAULT_MAX_BYTES = 50 * 1024;
export const DEFAULT_MAX_LINES = 2000;

/** A failure the model should read; `code` mirrors the host's, which is never secret material. */
export class ShellToolError extends Error {
  constructor(code, message, cause) {
    super(message);
    this.name = 'ShellToolError';
    this.code = code;
    if (cause !== undefined) this.cause = cause;
  }
}

const abortError = message => (typeof DOMException === 'function'
  ? new DOMException(message, 'AbortError')
  : Object.assign(new Error(message), { name: 'AbortError' }));

/**
 * The tool.
 *
 * `api.env.exec` is the one execution route; the shell's limits come from the Kotlin host through
 * `shell.capabilities` (`backend/ShellLimits.kt` is the owner), so this module does not keep a
 * second copy of the default or the ceiling.
 */
export function createAndroidShellTool({ shellLimits, limitsReader } = {}) {
  const reader = limitsReader ?? createShellLimitsReader(shellLimits);

  return defineTool({
    name: SHELL_TOOL_NAME,
    description: `Run a command with Android's native shell (${SHELL_EXECUTABLE}, ${SHELL_KIND}), `
      + `not bash. This is a POSIX shell: bash-only features such as arrays, process substitution `
      + `and brace expansion are unavailable. Returns combined stdout and stderr. Output is kept as `
      + `the last ${DEFAULT_MAX_LINES} lines or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit `
      + `first); if it is cut, the complete output is saved to a temp file. Timeout is in seconds: `
      + `when omitted the host's default deadline applies, and a larger request is clamped to the `
      + `host's ceiling. A timeout or cancellation is reported with the deadline that was actually `
      + `used, never as an empty success. Commands run with this app's own UID; the tool is not a `
      + `sandbox. Use run_script to run a script file — an app-written file cannot be exec'd directly `
      + `on Android, but the interpreter can read it as data.`,
    parameters: {
      type: 'object',
      properties: {
        command: {
          type: 'string',
          description: `The command to run. It is parsed by ${SHELL_KIND}; quote shell metacharacters as usual.`,
        },
        timeout: {
          type: 'number',
          description: "Kill the command after this many seconds. Defaults to the host's own default and is clamped to the ceiling the host enforces; the value used is reported when it times out.",
        },
      },
      required: ['command'],
      additionalProperties: false,
    },
    outputLimits: { retain: 'tail' },
    async execute(args, api, context) {
      const command = args?.command;
      if (typeof command !== 'string' || command.trim() === '') {
        throw new ShellToolError('invalid_command', 'command is required and must be a non-empty string');
      }

      // The host's limits, before anything runs: a host that cannot say what it enforces must not be
      // run under a guess.
      const limits = await reader.get();
      const requested = args.timeout === undefined ? limits.defaultTimeoutMs : requestedTimeoutMillis(args.timeout);
      if (requested === null) {
        throw new ShellToolError('invalid_arguments', 'timeout must be a finite number of seconds greater than zero');
      }
      const timeoutMs = Math.min(requested, limits.maxTimeoutMs);

      const env = api?.env;
      if (env === undefined || typeof env.exec !== 'function') throw new Error('No execution environment is configured');
      if (context?.abortSignal?.aborted === true) throw abortError('The command was aborted before it started');

      let result;
      try {
        result = await env.exec(command, {
          cwd: env.cwd,
          env: {},
          // The Android host replaces the environment with PATH/HOME/TMPDIR derived from the
          // execution roots; it does not inherit the WebView's process environment.
          inheritEnv: false,
          // `timeoutMs` is this adapter's internal, already-clamped form. The public ExecutionEnv
          // contract takes seconds (`timeout`); passing the clamped value directly avoids a second
          // conversion and a second rounding.
          timeoutMs,
          onOutput: (text, _context, info) => api.output(text, info?.skipped),
          spill: { afterBytes: DEFAULT_MAX_BYTES, afterLines: DEFAULT_MAX_LINES, maxBytes: limits.maxStreamBytes },
          ...(api.outputWindow === undefined ? {} : { window: api.outputWindow }),
        }, context);
      } catch (error) {
        if (context?.abortSignal?.aborted === true) throw abortError('The command was aborted');
        if (error?.code === 'aborted') throw new Error('Command aborted');
        if (error?.code === 'timeout') {
          const used = Number.isInteger(error?.timeoutMs) ? error.timeoutMs : timeoutMs;
          throw new Error(`Command timed out after ${formatSeconds(used)} seconds`);
        }
        throw error;
      }

      if (!result.ok) {
        const error = result.error;
        if (error?.spillPath !== undefined) {
          api.diagnostic({ severity: 'info', code: 'full_output', message: `Full output: ${error.spillPath}` });
        }
        if (context?.abortSignal?.aborted === true || error?.code === 'aborted') throw new Error('Command aborted');
        if (error?.code === 'timeout') {
          const used = Number.isInteger(error?.timeoutMs) ? error.timeoutMs : timeoutMs;
          throw new Error(`Command timed out after ${formatSeconds(used)} seconds`);
        }
        throw error;
      }

      const value = result.value ?? {};
      const usedTimeoutMs = Number.isInteger(value.timeoutMs) ? value.timeoutMs : timeoutMs;
      if (value.spillPath !== undefined) {
        api.diagnostic({ severity: 'info', code: 'full_output', message: `Full output: ${value.spillPath}` });
      }
      if (value.spillTruncated === true) {
        api.diagnostic({
          severity: 'warn',
          code: 'full_output_capped',
          message: `The full-output file was capped at ${limits.maxStreamBytes} bytes; the tail of the command's output is not available`,
        });
      }
      if (value.streamTruncated === true) {
        api.diagnostic({
          severity: 'warn',
          code: 'stream_capped',
          message: `The streamed output was capped at ${limits.maxStreamBytes} bytes; anything after that was not delivered`,
        });
      }
      if (value.drainIncomplete === true) {
        api.diagnostic({
          severity: 'warn',
          code: 'drain_incomplete',
          message: "The command's process exited, but its output could not be read to the end, so more output may have been produced than is shown",
        });
      }
      if (value.exitCode !== 0) throw new Error(`Command exited with code ${value.exitCode}`);

      return {
        details: {
          exitCode: Number.isInteger(value.exitCode) ? value.exitCode : null,
          timeoutMs: usedTimeoutMs,
          spillPath: value.spillPath ?? null,
          spillTruncated: value.spillTruncated === true,
          streamTruncated: value.streamTruncated === true,
          drainIncomplete: value.drainIncomplete === true,
        },
      };
    },
  });
}

/** The extension the host installs, so the tool is offered to the model like any other. */
export function createAndroidShellTools(options) {
  // One reader shared by the tool and the prompt section, so the model is told the same default
  // and ceiling the tool enforces without a second `shell.capabilities` round trip.
  const limitsReader = createShellLimitsReader(options?.shellLimits);
  return defineExtension({
    name: SHELL_EXTENSION_NAME,
    tools: [createAndroidShellTool({ ...options, limitsReader })],
    sections: [{
      key: 'android-shell',
      render: async () => {
        const base = `The \`${SHELL_TOOL_NAME}\` tool runs commands with Android's native shell `
          + `(${SHELL_EXECUTABLE}, ${SHELL_KIND}), not bash. It is a POSIX shell, so bash-only `
          + `features such as arrays, process substitution and brace expansion are unavailable. `
          + `Commands run with this app's own UID and are not sandboxed.`;
        try {
          const limits = await limitsReader.get();
          return `${base} Its timeout is in seconds: the host's default is `
            + `${formatSeconds(limits.defaultTimeoutMs)} seconds and its ceiling is `
            + `${formatSeconds(limits.maxTimeoutMs)} seconds; a larger request is clamped and the value used `
            + `is reported.`;
        } catch {
          return `${base} The host advertises the current timeout default and ceiling at call time.`;
        }
      },
    }],
  });
}
