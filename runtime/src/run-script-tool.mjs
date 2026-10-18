// `run_script`: the agent's reliable way to run a script it wrote.
//
// On Android a file the app wrote cannot be executed. A direct `ProcessBuilder` of a script in the
// app files dir fails with `error=13, Permission denied`, because SELinux forbids an app process
// from executing `app_data_file` (the W^X rule since Android 10); in the workspace it fails too,
// because `/storage/emulated` is mounted `noexec`. `/system/bin/sh <script>` succeeds in BOTH
// places — a script is *data* to the interpreter, so no exec of the file ever happens. A compiled
// binary cannot be run at all unless it ships inside the app.
//
// The agent's own shell tool takes its command as a *string*, so its `./tool.sh` is handed to mksh
// inside our `sh -c` and the app never sees the path — the one case it cannot intercept. This tool
// is the explicit route: it builds the ARGV itself and sends it through the existing `shell.exec`
// bridge op, so argv[0] is the interpreter and the script is never the thing that gets exec-ed.
//
// The whole module runs in the WebView: it reaches no Node API and no bare `Buffer`, only globals
// the WebView has (TextEncoder, DOMException, AbortController) plus the injected bridge `call`.
//
// The argument schema is a plain JSON Schema object rather than a TypeBox builder on purpose: the
// harness validates `parameters` through pi-ai's `validateToolArguments`, which compiles any JSON
// Schema and has an explicit non-TypeBox coercion path, and a plain object keeps this module free of
// a dependency the runtime does not itself declare.

import { defineExtension, defineTool } from '@earendil-works/pi-durable';
import { resolve as resolvePath } from './path.mjs';
import { SHELL_EXECUTABLE, createShellLimitsReader } from './shell-info.mjs';

/** The tool's name. Not `sh` and not `exec`: the model must not confuse it with a shell invocation. */
export const RUN_SCRIPT_TOOL_NAME = 'run_script';
/** The extension that carries it; installed alongside the coding tools and selected by default. */
export const RUN_SCRIPT_EXTENSION_NAME = 'scripts';

/** Re-exported for the callers and tests that used to read it from this module. */
export { SHELL_EXECUTABLE, normalizeShellLimits } from './shell-info.mjs';

/** A failure the model should read: `code` mirrors the host's, which is never secret material. */
export class RunScriptToolError extends Error {
  constructor(code, message, cause) {
    super(message);
    this.name = 'RunScriptToolError';
    this.code = code;
    if (cause !== undefined) this.cause = cause;
  }
}

const abortError = message => (typeof DOMException === 'function'
  ? new DOMException(message, 'AbortError')
  : Object.assign(new Error(message), { name: 'AbortError' }));

const byteLength = text => new TextEncoder().encode(text).length;

const textOf = value => (typeof value === 'string' ? value : '');

const codeOf = (error, fallback) => (typeof error?.code === 'string' && error.code !== '' ? error.code : fallback);

/** The script path, resolved against the workspace root and refused if it leaves it. */
function resolveScriptPath(root, target) {
  const base = typeof root === 'string' && root !== '' ? root : '/';
  const path = resolvePath(base, String(target));
  const inside = base === '/' || path === base || path.startsWith(`${base}/`);
  if (!inside) {
    throw new RunScriptToolError('workspace_escape', `path must stay inside the workspace (${base}): ${target}`);
  }
  return path;
}

/** The script's arguments: each one becomes exactly one argv entry, passed through unparsed. */
function parseArguments(value) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw new RunScriptToolError('invalid_arguments', 'args must be an array of strings');
  return value.map(entry => {
    if (typeof entry === 'string') return entry;
    if (typeof entry === 'number' && Number.isFinite(entry)) return String(entry);
    if (typeof entry === 'boolean') return String(entry);
    throw new RunScriptToolError('invalid_arguments', `args must be strings, not ${JSON.stringify(entry)}`);
  });
}

/** The deadline: a positive whole number of milliseconds, clamped to the ceiling the host advertises. */
function parseTimeout(value, limits) {
  if (value === undefined || value === null) return limits.defaultTimeoutMs;
  if (!Number.isInteger(value) || value <= 0) {
    throw new RunScriptToolError('invalid_arguments', 'timeoutMs must be a positive whole number of milliseconds');
  }
  return Math.min(value, limits.maxTimeoutMs);
}

/**
 * The tool.
 *
 * `call` is the bridge's own `call`: the run is the `shell.exec` op with an ARGV command, and the
 * cancellation is the `shell.cancel` op. `workspaceRoot` is read per call, because the tool is
 * installed before the host knows the workspace. `shellLimits` is read once, on the first call, from
 * the host that enforces them (`shell.capabilities`) — the single owner of the numbers this tool
 * reports.
 */
export function createRunScriptTool({ call, workspaceRoot, shellLimits } = {}) {
  if (typeof call !== 'function') throw new TypeError('createRunScriptTool requires a bridge call');
  const rootOf = typeof workspaceRoot === 'function' ? workspaceRoot : () => (typeof workspaceRoot === 'string' ? workspaceRoot : '/');
  let nextRequest = 0;
  /**
   * The host's limits, read once and reused. The reader clears a rejected read, so one transient
   * `shell.capabilities` failure does not disable this tool for the life of the runtime.
   */
  const limitsReader = createShellLimitsReader(shellLimits);
  const resolveLimits = () => limitsReader.get();

  return defineTool({
    name: RUN_SCRIPT_TOOL_NAME,
    description: `Run a script file from the workspace with the device shell interpreter `
      + `(${SHELL_EXECUTABLE} <script> [args...]) and return its exit code, stdout and stderr. This is `
      + `the only way to run a file the app itself wrote: a file in app storage cannot be executed `
      + `directly (SELinux forbids an app process from executing app data, "Permission denied", the W^X `
      + `rule since Android 10) and shared storage is mounted noexec, so ./script fails in both places. A `
      + `script is just data to the interpreter, which is why this route works where a direct exec does `
      + `not. A compiled binary cannot be run at all unless it ships inside the app, so write scripts in `
      + `shell text. Any file the interpreter can read is accepted — the .sh extension and the executable `
      + `bit are irrelevant. The path is resolved against the workspace and refused if it escapes it, and `
      + `the file must exist and be a regular file. The script runs with the workspace as its working `
      + `directory and the app's own environment (PATH=/system/bin). The device shell bounds each output `
      + `stream, and a stream that reached that bound is reported as cut rather than presented as whole. A `
      + `non-zero exit code is reported as the script's own result; a timeout (at most the ceiling the `
      + `host enforces, and reported with the value that was used) or a cancellation is reported as such, `
      + `with whatever output the script produced first, never as an empty success.`,
    // A plain JSON Schema: pi-ai's `validateToolArguments` compiles any JSON Schema and has an
    // explicit non-TypeBox coercion path, so this validates (and coerces) exactly as a TypeBox
    // parameter object would, without adding an import the runtime does not declare.
    parameters: {
      type: 'object',
      properties: {
        path: {
          type: 'string',
          description: 'The script to run: a workspace-relative path, or an absolute path inside the workspace. Any file the interpreter can read.',
        },
        args: {
          type: 'array',
          items: { type: 'string' },
          description: "Arguments for the script. Each becomes one argv entry, passed through unparsed (quote nothing; a space inside an entry is kept).",
        },
        timeoutMs: {
          type: 'integer',
          description: "Kill the script after this many milliseconds. Defaults to the host's own default, and is clamped to the ceiling the host enforces; the value actually used is reported.",
        },
      },
      required: ['path'],
      additionalProperties: false,
    },
    async execute(args, api, context) {
      const raw = args?.path;
      if (typeof raw !== 'string' || raw.trim() === '') {
        throw new RunScriptToolError('invalid_path', 'path is required and must be the script file to run');
      }
      const scriptArgs = parseArguments(args?.args);
      // The host's limits, before anything runs: a host that cannot say what it enforces must not be
      // run under a guess.
      const limits = await resolveLimits();
      const timeoutMs = parseTimeout(args?.timeoutMs, limits);
      const path = resolveScriptPath(rootOf(), raw);
      const argv = [SHELL_EXECUTABLE, path, ...scriptArgs];

      const signal = context?.abortSignal;
      if (signal?.aborted === true) throw abortError('The script run was aborted');

      // The file must exist and be a regular file before anything is started. A directory, a device
      // or a missing path would otherwise reach the interpreter and fail there, less clearly.
      let stat;
      try {
        stat = await call('file.stat', { path, follow: true });
      } catch (error) {
        throw new RunScriptToolError(codeOf(error, 'shell_error'), `Could not look at ${path}: ${error?.message ?? String(error)}`, error);
      }
      if (stat?.exists !== true) throw new RunScriptToolError('not_found', `No such file in the workspace: ${path}`);
      if (stat.kind !== 'file') throw new RunScriptToolError('not_a_file', `Not a regular file: ${path} (${stat.kind ?? 'unknown kind'})`);

      const requestId = `run-script-${++nextRequest}`;
      // Cancellation is the caller's signal, relayed to the host's own cancel op so the interpreter
      // is stopped rather than left running while nobody reads its output.
      const onAbort = () => { call('shell.cancel', { requestId }).catch(() => {}); };
      if (typeof signal?.addEventListener === 'function') signal.addEventListener('abort', onAbort, { once: true });

      let value;
      try {
        value = await call('shell.exec', { command: argv, timeoutMs, requestId });
      } catch (error) {
        if (signal?.aborted === true) throw abortError('The script run was aborted');
        throw new RunScriptToolError(codeOf(error, 'shell_error'), `Could not run ${path}: ${error?.message ?? String(error)}`, error);
      } finally {
        if (typeof signal?.removeEventListener === 'function') signal.removeEventListener('abort', onAbort);
      }
      if (signal?.aborted === true) throw abortError('The script run was aborted');

      const stdout = textOf(value?.stdout);
      const stderr = textOf(value?.stderr);
      const exitCode = Number.isInteger(value?.exitCode) ? value.exitCode : null;
      const timedOut = value?.timedOut === true;
      const cancelled = value?.cancelled === true;
      const drainIncomplete = value?.drainIncomplete === true;
      const streamTruncated = value?.streamTruncated === true;
      const stdoutBytes = byteLength(stdout);
      const stderrBytes = byteLength(stderr);
      // The host sets these flags only when its per-stream cap actually cut that stream. Do not infer
      // truncation from `stdoutBytes >= maxOutputBytes`: an exactly-full stream is not cut, while a
      // cut inside a multibyte UTF-8 character can decode to slightly fewer bytes than the cap.
      const stdoutTruncated = value?.stdoutTruncated === true;
      const stderrTruncated = value?.stderrTruncated === true;
      const truncatedStreams = [];
      if (stdoutTruncated) truncatedStreams.push('stdout');
      if (stderrTruncated) truncatedStreams.push('stderr');
      const truncated = truncatedStreams.length > 0;
      const usedTimeoutMs = Number.isInteger(value?.timeoutMs) ? value.timeoutMs : timeoutMs;

      const blocks = [`$ ${argv.join(' ')}`, `exit code: ${exitCode === null ? 'none' : String(exitCode)}`];
      if (stdout !== '') blocks.push(stdout);
      if (stderr !== '') blocks.push(`[stderr]\n${stderr}`);

      const markers = [];
      const diagnostics = [];
      if (cancelled) {
        const detail = 'the run was cancelled: the exit code is unknown, and the output above is what the script produced before it was stopped';
        markers.push(detail);
        diagnostics.push({ severity: 'error', code: 'cancelled', message: detail });
      } else if (timedOut) {
        const detail = `the script timed out: it was killed after the ${usedTimeoutMs} ms timeout, so the exit code is unknown and the output above is what it produced first`;
        markers.push(detail);
        diagnostics.push({ severity: 'error', code: 'timeout', message: detail });
      } else if (exitCode !== null && exitCode !== 0) {
        diagnostics.push({ severity: 'warn', code: 'exit_code', message: `the script exited with code ${exitCode}` });
      }
      if (truncated) {
        const detail = `the shell caps each output stream at ${limits.maxOutputBytes} bytes: ${truncatedStreams.join(' and ')} reached that cap, so the end of it may be missing`;
        markers.push(detail);
        diagnostics.push({ severity: 'warn', code: 'shell_cap', message: detail });
      }
      if (streamTruncated) {
        const detail = `the streamed output was capped at ${limits.maxStreamBytes} bytes; anything after that was not delivered`;
        markers.push(detail);
        diagnostics.push({ severity: 'warn', code: 'stream_cap', message: detail });
      }
      if (drainIncomplete) {
        const detail = "the script's process exited, but its output could not be read to the end, so more output may have been produced than is shown";
        markers.push(detail);
        diagnostics.push({ severity: 'warn', code: 'drain_incomplete', message: detail });
      }

      const content = `${blocks.join('\n\n')}${markers.length === 0 ? '' : `\n\n…[${RUN_SCRIPT_TOOL_NAME}: ${markers.join(' ')}]…`}`;

      return {
        content: [{ type: 'text', text: content }],
        ...(diagnostics.length === 0 ? {} : { diagnostics }),
        ...(cancelled || timedOut ? { isError: true } : {}),
        details: {
          path,
          argv,
          exitCode,
          timeoutMs: usedTimeoutMs,
          stdoutBytes,
          stderrBytes,
          timedOut,
          cancelled,
          drainIncomplete,
          streamTruncated,
          stdoutTruncated,
          stderrTruncated,
          truncated,
          truncatedStreams,
        },
      };
    },
  });
}

/** The extension the host installs, so the tool is offered to the model like any other. */
export function createRunScriptTools(options) {
  return defineExtension({ name: RUN_SCRIPT_EXTENSION_NAME, tools: [createRunScriptTool(options)] });
}
