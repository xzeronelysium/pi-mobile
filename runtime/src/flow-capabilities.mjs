// The vocabularies behind an agent-authorable flow: WHICH capabilities a spec step may name, what
// arguments each takes, and how each one is turned into the bridge calls this runtime already makes.
//
// THE RULE THIS FILE EXISTS TO KEEP: a capability grants no authority the agent does not already
// hold through its own tools. Every adapter here is the same op the runtime's own tool already
// sends — `shell.exec` (the shell tool), `file.stat` + `shell.exec` with the interpreter as argv[0]
// (the `run_script` tool, because an app-written file cannot be exec'd: SELinux refuses an app
// process the exec of app storage data and the workspace is mounted `noexec`), `file.read` /
// `file.write` (the environment's file ops), and the native HTTP transport for `fetch_url`. Nothing
// here reaches the network, or a path, or a process by any route the agent could not already use.
//
// The whole module runs in the WebView: no Node API, only globals it has plus the bridge `call` and
// the injected transport.

import { resolve as resolvePath } from './path.mjs';
import { SHELL_EXECUTABLE } from './shell-info.mjs';

/** Re-exported for the callers and tests that used to read it from this module. */
export { SHELL_EXECUTABLE } from './shell-info.mjs';

/** How much of a fetched body is retained in the state; more than this is cut and reported. */
export const MAX_FETCH_BYTES = 64 * 1024;

/** A failure the step raises for something that stopped it running at all, not for a bad outcome. */
export class CapabilityError extends Error {
  constructor(code, message, cause) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'CapabilityError';
    this.code = code;
  }
}

const isPlain = value => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
};

const text = value => (typeof value === 'string' ? value : '');

const bytesOf = value => new TextEncoder().encode(value).length;

/**
 * The kinds an argument may be. `check` returns the reason a value is not of this kind, or null:
 * one place decides what "a positive whole number of milliseconds" means, for the message the model
 * reads and for the value the host is handed.
 */
export const ARGUMENT_KINDS = Object.freeze({
  command: {
    describe: 'a command string, or an array of argv strings',
    check: value => {
      if (typeof value === 'string' && value.trim() !== '') return null;
      if (Array.isArray(value) && value.length > 0 && value.every(entry => typeof entry === 'string' && entry !== '')) return null;
      return 'a non-empty command string, or an array of non-empty argv strings';
    },
  },
  path: {
    describe: 'a path inside the workspace',
    check: value => (typeof value === 'string' && value.trim() !== '' ? null : 'a non-empty path'),
  },
  url: {
    describe: 'an absolute http or https URL',
    check: value => {
      if (typeof value !== 'string') return 'an absolute http or https URL';
      let parsed;
      try {
        parsed = new URL(value);
      } catch {
        return 'an absolute http or https URL';
      }
      return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? null : `a URL the transport can reach (http or https, not ${parsed.protocol.replace(':', '')})`;
    },
  },
  method: {
    describe: 'an HTTP method, like GET or POST',
    check: value => (typeof value === 'string' && /^[A-Za-z][A-Za-z0-9-]*$/.test(value) ? null : 'an HTTP method, like GET or POST'),
  },
  headers: {
    describe: 'an object of header names to text values',
    check: value => (isPlain(value) && Object.values(value).every(entry => typeof entry === 'string' || typeof entry === 'number') ? null : 'an object of header names to text values'),
  },
  string: {
    describe: 'a string',
    check: value => (typeof value === 'string' ? null : 'a string'),
  },
  string_list: {
    describe: 'a list of strings, one argv entry each',
    check: value => (Array.isArray(value) && value.every(entry => typeof entry === 'string') ? null : 'a list of strings'),
  },
  positive_integer: {
    describe: 'a positive whole number of milliseconds',
    check: value => (Number.isInteger(value) && value > 0 ? null : 'a positive whole number of milliseconds'),
  },
});

/**
 * What each capability takes. `required: true` means a step naming this capability must give it.
 * Order is the order the error message lists them in, so the message reads like the format does.
 */
export const CAPABILITY_ARGUMENTS = Object.freeze({
  shell: {
    command: { kind: 'command', required: true },
    cwd: { kind: 'path' },
    timeoutMs: { kind: 'positive_integer' },
  },
  run_script: {
    path: { kind: 'path', required: true },
    scriptArgs: { kind: 'string_list' },
    timeoutMs: { kind: 'positive_integer' },
  },
  read_file: {
    path: { kind: 'path', required: true },
  },
  write_file: {
    path: { kind: 'path', required: true },
    text: { kind: 'string', required: true },
  },
  fetch_url: {
    url: { kind: 'url', required: true },
    method: { kind: 'method' },
    headers: { kind: 'headers' },
    body: { kind: 'string' },
    timeoutMs: { kind: 'positive_integer' },
  },
  ask: {
    prompt: { kind: 'string', required: true },
  },
});

/**
 * The six steps a spec may name. `fails` says whether the step has an outcome that can be bad: a
 * command exits non-zero, a fetch answers 4xx/5xx, a read finds nothing. `ask` cannot fail — a
 * question that has not been answered is not a failure, it is a pause — which is why a spec may not
 * give it a failure branch.
 */
export const CAPABILITIES = Object.freeze({
  shell: { fails: true, describe: 'run a command with the device shell' },
  run_script: { fails: true, describe: 'run a workspace script with the interpreter' },
  read_file: { fails: true, describe: 'read a file inside the workspace' },
  write_file: { fails: true, describe: 'write a file inside the workspace' },
  fetch_url: { fails: true, describe: 'fetch a URL with the native transport' },
  ask: { fails: false, describe: 'pause the run and ask the person a question' },
});

/** The capability names, in the order a message should list them. */
export const CAPABILITY_NAMES = Object.freeze(Object.keys(CAPABILITIES));

/** The list of names as error messages print it. */
export const CAPABILITY_LIST = CAPABILITY_NAMES.join(', ');

/** Resolve a workspace-relative path and refuse anything that leaves the workspace. */
export function resolveInsideWorkspace(workspace, target) {
  const base = typeof workspace === 'string' && workspace !== '' ? workspace : '/';
  const path = resolvePath(base, String(target));
  const inside = base === '/' || path === base || path.startsWith(`${base}/`);
  if (!inside) throw new CapabilityError('workspace_escape', `${target} is outside the workspace (${base})`);
  return path;
}

/**
 * A request id that is unique across flow *threads*, not only across a step's own supersteps.
 *
 * Two threads may run the same flow at the same time (`flow.mjs` keeps its busy set per thread), so
 * the flow name alone would make both send the same id and the host would refuse the second as a
 * duplicate shell command. The thread id is part of the identity; the name/step/superstep keep the
 * id stable for a resumed run.
 */
const requestId = context => `flow-spec-${context.threadId ?? context.flow}-${context.flow}-${context.step}-${context.superstep}`;

const codeOf = (error, fallback) => (typeof error?.code === 'string' && error.code !== '' ? error.code : fallback);

/** The shell op's answer, as the state records it. Additive flags appear only when the host set them. */
function shellResult(value) {
  const result = {
    exitCode: Number.isInteger(value?.exitCode) ? value.exitCode : null,
    stdout: text(value?.stdout),
    stderr: text(value?.stderr),
    timedOut: value?.timedOut === true,
    cancelled: value?.cancelled === true,
  };
  if ((value?.timedOut === true || value?.cancelled === true) && Number.isInteger(value?.timeoutMs)) result.timeoutMs = value.timeoutMs;
  if (value?.drainIncomplete === true) result.drainIncomplete = true;
  if (value?.streamTruncated === true) result.streamTruncated = true;
  if (value?.stdoutTruncated === true) result.stdoutTruncated = true;
  if (value?.stderrTruncated === true) result.stderrTruncated = true;
  return result;
}

/** How a shell-shaped result went wrong, or null when it did not. */
function shellFailure(result, what) {
  if (result.cancelled) return { code: 'cancelled', message: `${what} was cancelled` };
  if (result.timedOut) {
    const after = Number.isInteger(result.timeoutMs) ? ` after ${result.timeoutMs} ms` : '';
    return { code: 'timeout', message: `${what} timed out${after}` };
  }
  if (result.exitCode === null) return { code: 'no_exit_code', message: `${what} produced no exit code, so it cannot be read as a success` };
  if (result.exitCode !== 0) {
    const detail = result.stderr.trim() || result.stdout.trim();
    return { code: 'exit_code', message: `${what} exited with code ${result.exitCode}${detail === '' ? '' : `: ${detail}`}` };
  }
  return null;
}

/**
 * One `shell.exec` call with cancellation relayed to the host.
 *
 * A flow currently has no abort signal of its own, but the engine context may grow one; when it
 * does, a cancelled step must stop the process rather than leave it running under the host timeout.
 * The request id is stable for the step/superstep so a cancel can name it.
 */
async function callShell(context, args) {
  const id = requestId(context);
  const signal = context.abortSignal;
  if (signal?.aborted === true) throw new CapabilityError('cancelled', 'the command was aborted before it started');
  const onAbort = () => { context.call('shell.cancel', { requestId: id }).catch(() => {}); };
  if (typeof signal?.addEventListener === 'function') signal.addEventListener('abort', onAbort, { once: true });
  try {
    return await context.call('shell.exec', { ...args, requestId: id });
  } catch (cause) {
    if (signal?.aborted === true) throw new CapabilityError('cancelled', 'the command was aborted', cause);
    throw cause;
  } finally {
    if (typeof signal?.removeEventListener === 'function') signal.removeEventListener('abort', onAbort);
  }
}

async function runShell(args, context) {
  let shell;
  try {
    shell = await callShell(context, {
      command: args.command,
      ...(args.cwd === undefined ? {} : { cwd: resolveInsideWorkspace(context.workspace, args.cwd) }),
      ...(args.timeoutMs === undefined ? {} : { timeoutMs: args.timeoutMs }),
    });
  } catch (cause) {
    if (cause instanceof CapabilityError) throw cause;
    throw new CapabilityError(codeOf(cause, 'shell_error'), `the shell refused the command: ${cause?.message ?? String(cause)}`, cause);
  }
  const result = shellResult(shell);
  const failure = shellFailure(result, 'the command');
  return failure === null ? { ok: true, result, error: null } : { ok: false, result, error: failure };
}

async function runScript(args, context) {
  const path = resolveInsideWorkspace(context.workspace, args.path);
  if (context.abortSignal?.aborted === true) throw new CapabilityError('cancelled', 'the script run was aborted before it started');
  let stat;
  try {
    stat = await context.call('file.stat', { path, follow: true });
  } catch (cause) {
    throw new CapabilityError(codeOf(cause, 'file_error'), `${path} could not be looked at: ${cause?.message ?? String(cause)}`, cause);
  }
  if (stat?.exists !== true) throw new CapabilityError('not_found', `there is no file at ${path}`);
  if (stat.kind !== 'file') throw new CapabilityError('not_a_file', `${path} is a ${stat.kind ?? 'unknown kind'}, not a regular file`);
  // argv[0] is the interpreter and argv[1] the script: a script is data to `/system/bin/sh`, which
  // is why this works where executing the file itself is refused on every device.
  const argv = [SHELL_EXECUTABLE, path, ...(args.scriptArgs ?? [])];
  let shell;
  try {
    shell = await callShell(context, {
      command: argv,
      ...(args.timeoutMs === undefined ? {} : { timeoutMs: args.timeoutMs }),
    });
  } catch (cause) {
    if (cause instanceof CapabilityError) throw cause;
    throw new CapabilityError(codeOf(cause, 'shell_error'), `the interpreter could not run ${path}: ${cause?.message ?? String(cause)}`, cause);
  }
  const result = { path, ...shellResult(shell) };
  const failure = shellFailure(result, `${path} handed to ${SHELL_EXECUTABLE}`);
  return failure === null ? { ok: true, result, error: null } : { ok: false, result, error: failure };
}

async function readFile(args, context) {
  const path = resolveInsideWorkspace(context.workspace, args.path);
  let value;
  try {
    value = await context.call('file.read', { path });
  } catch (cause) {
    throw new CapabilityError(codeOf(cause, 'file_error'), `${path} could not be read: ${cause?.message ?? String(cause)}`, cause);
  }
  const contents = typeof value === 'string' ? value : text(value?.text ?? value?.content);
  return { ok: true, result: { path, text: contents, bytes: bytesOf(contents) }, error: null };
}

async function writeFile(args, context) {
  const path = resolveInsideWorkspace(context.workspace, args.path);
  try {
    await context.call('file.write', { path, text: args.text });
  } catch (cause) {
    throw new CapabilityError(codeOf(cause, 'file_error'), `${path} could not be written: ${cause?.message ?? String(cause)}`, cause);
  }
  return { ok: true, result: { path, bytes: bytesOf(args.text) }, error: null };
}

const headersOf = headers => {
  if (headers === null || headers === undefined) return {};
  const object = {};
  if (typeof headers.forEach === 'function' && typeof headers.get === 'function') {
    headers.forEach((value, name) => { object[String(name)] = String(value); });
    return object;
  }
  if (typeof headers === 'object' && !Array.isArray(headers)) {
    for (const [name, value] of Object.entries(headers)) object[name] = String(value);
    return object;
  }
  return {};
};

async function fetchUrl(args, context) {
  // The transport is handed in, never reached ambiently: in this runtime the request leaves through
  // Kotlin's own socket (`createBridgeFetch`), which is what makes it work at all.
  if (typeof context.fetch !== 'function') {
    throw new CapabilityError('no_transport', 'the fetch_url capability needs the runtime\'s native HTTP transport, and none was handed to the flow context (the host must pass `fetch` beside `call` and `workspace`)');
  }
  let response;
  try {
    response = await context.fetch(args.url, {
      method: args.method ?? 'GET',
      ...(args.headers === undefined ? {} : { headers: args.headers }),
      ...(args.body === undefined ? {} : { body: args.body }),
      ...(args.timeoutMs === undefined ? {} : { timeoutMs: args.timeoutMs }),
    });
  } catch (cause) {
    throw new CapabilityError(codeOf(cause, 'transport_error'), `the request to ${args.url} could not be made: ${cause?.message ?? String(cause)}`, cause);
  }
  let whole;
  try {
    whole = await response.text();
  } catch (cause) {
    throw new CapabilityError('transport_error', `the reply from ${args.url} could not be read: ${cause?.message ?? String(cause)}`, cause);
  }
  const body = typeof whole === 'string' ? whole : '';
  const kept = body.length > MAX_FETCH_BYTES ? body.slice(0, MAX_FETCH_BYTES) : body;
  const status = Number.isInteger(response?.status) ? response.status : null;
  const result = {
    status,
    statusText: text(response?.statusText),
    headers: headersOf(response?.headers),
    body: kept,
    bytes: bytesOf(kept),
    truncated: kept.length !== body.length,
  };
  // The status IS the outcome: 4xx and 5xx are a failure of this step, so the spec's failure branch
  // is the natural "if it did not work" and no cleverness is needed.
  if (status === null) return { ok: false, result, error: { code: 'no_status', message: `the reply from ${args.url} carried no HTTP status` } };
  if (status >= 400) return { ok: false, result, error: { code: 'http_status', message: `the request to ${args.url} answered ${status}${result.statusText === '' ? '' : ` ${result.statusText}`}` } };
  return { ok: true, result, error: null };
}

function ask(args, context) {
  // The first time through, the step pauses the run and records what it awaits. On resume it is
  // re-run with the answer as `context.resume` and writes it down — it must not write and interrupt
  // in the same pass, or the write would be applied twice when the step re-runs.
  if (context.resume === undefined) return { interrupt: { prompt: args.prompt } };
  return { ok: true, result: { prompt: args.prompt, answer: context.resume }, error: null };
}

/**
 * Run one capability. The return value is either `{ interrupt }` (the run pauses) or
 * `{ ok, result, error }`: `result` is the JSON the state records, `error` is the reason an honest
 * failure happened (a non-zero exit, a 4xx) while a thrown `CapabilityError` means the step could
 * not be taken at all.
 */
export async function invokeCapability(name, args, context) {
  switch (name) {
    case 'shell': return runShell(args, context);
    case 'run_script': return runScript(args, context);
    case 'read_file': return readFile(args, context);
    case 'write_file': return writeFile(args, context);
    case 'fetch_url': return fetchUrl(args, context);
    case 'ask': return ask(args, context);
    default: throw new CapabilityError('unknown_capability', `unknown capability ${name}`);
  }
}
