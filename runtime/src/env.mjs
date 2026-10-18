// AndroidExecutionEnv: the portable Pi Durable `ExecutionEnv` (FileSystem + Shell)
// implemented over the AndroidNative bridge primitives.
//
// Kotlin stays small and exposes primitives (file.stat/open/readAt/readRange/write/
// list/... and shell.exec/cancel, streaming bytes as base64). Everything fiddly —
// decoding, line readers, scanLines, paged dir readers, validation, spill and
// cancellation mapping — lives here, where it is unit-testable against a fake bridge.
//
// The reference for the Kotlin contract is runtime/test/support/node-bridge.mjs.

import {
  ExecutionError,
  FileError,
  LineScanner,
  StreamDecoder,
  err,
  ok,
  rangeDecoder,
  startsWithBom,
} from '@earendil-works/pi-durable/env';
import { basename, join, resolve } from './path.mjs';
import { createPollingWatch } from './watch.mjs';

const READ_CHUNK_BYTES = 256 * 1024;
const FILE_ERROR_CODES = new Set([
  'aborted',
  'not_found',
  'permission_denied',
  'not_directory',
  'is_directory',
  'invalid',
  'not_supported',
  'unknown',
]);

function fileCode(code) {
  return typeof code === 'string' && FILE_ERROR_CODES.has(code) ? code : 'unknown';
}

function toFileError(cause, path) {
  if (cause instanceof FileError) return cause;
  return new FileError(fileCode(cause?.code), cause?.message ?? String(cause), path, cause);
}

function invalid(message, path) {
  return new FileError('invalid', message, path);
}

function aborted(path) {
  return new FileError('aborted', 'Aborted', path);
}

function isAborted(context) {
  return context?.abortSignal?.aborted === true;
}

function toBase64(bytes) {
  let text = '';
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text);
}

function fromBase64(text) {
  const binary = atob(text);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function isAbortError(error) {
  return error?.name === 'AbortError' || error?.code === 'aborted';
}

/** Split decoded text into lines; a trailing newline does not create an empty last line. */
function splitLines(text) {
  if (text.length === 0) return [];
  const lines = text.split('\n');
  if (lines[lines.length - 1] === '') lines.pop();
  return lines;
}

/**
 * The durable shell's timeout, converted from SECONDS to MILLISECONDS at the one boundary where the
 * two contracts meet.
 *
 * Pi Durable's own `bash`/`powershell` tools declare their timeout in seconds — its schema says
 * "Timeout in seconds (optional, no default timeout)" and its own failure text is "Command timed out
 * after <n> seconds" — while `shell.exec` takes milliseconds (`ShellLimits`/`NativeShell` on the
 * Kotlin side, and `run_script`'s `timeoutMs`). The adapter used to pass the number straight through,
 * so a model asking for `timeout: 12` gave the host a **12 millisecond** deadline: the command was
 * killed almost immediately, with whatever output it had produced first, and the tool then reported
 * a timeout. On the device that reads as the shell being broken, and it is why every external command
 * that specified a timeout (5 s, 2 s, 12 s) appeared to die at once.
 *
 * Two properties matter and are tested: a usable value converts exactly (`12` → `12000`), and
 * anything the host could not use as a deadline is *omitted* rather than guessed at — an absent
 * `timeoutMs` is the host's own default (`ShellLimits.DEFAULT_TIMEOUT_MILLIS`), never a one
 * millisecond deadline. A value the model asked for above the host's ceiling is left alone here: the
 * host owns that ceiling and applies it.
 */
export function shellTimeoutMillis(seconds) {
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds <= 0) return undefined;
  // Round, never truncate: a fraction of a second is whole milliseconds, and a sub-millisecond
  // request is the smallest deadline the host accepts rather than 0 (which would read as 1 ms).
  return Math.max(1, Math.round(seconds * 1000));
}

export function createAndroidExecutionEnv({ call, cwd = '/', subscribe } = {}) {
  if (typeof call !== 'function') throw new TypeError('createAndroidExecutionEnv requires a call function');

  const activeWatches = new Set();
  const runs = new Map();
  let currentCwd = cwd;
  let nextRequest = 0;

  const unsubscribe = typeof subscribe === 'function'
    ? subscribe(event => {
        if (event?.event === 'shell.output') {
          const run = runs.get(event.requestId);
          if (run) run.onChunk(event);
        }
      })
    : () => {};

  const attempt = async (path, run) => {
    try {
      return ok(await run());
    } catch (error) {
      return err(toFileError(error, path));
    }
  };

  const absolute = path => resolve(currentCwd, path);

  async function readRange(path, offset, length) {
    const value = await call('file.readRange', { path, offset, length });
    return fromBase64(value.base64);
  }

  async function readAllBytes(path, context) {
    const info = await call('file.stat', { path, follow: true });
    if (!info.exists) throw new FileError('not_found', `No such file: ${path}`, path);
    if (info.kind === 'directory') throw new FileError('is_directory', `Is a directory: ${path}`, path);
    const chunks = [];
    let offset = 0;
    for (;;) {
      if (isAborted(context)) throw aborted(path);
      const chunk = await readRange(path, offset, READ_CHUNK_BYTES);
      if (chunk.length === 0) break;
      chunks.push(chunk);
      offset += chunk.length;
      if (chunk.length < READ_CHUNK_BYTES) break;
    }
    const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
    const bytes = new Uint8Array(total);
    let at = 0;
    for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
    return bytes;
  }

  function statToInfo(path, stat) {
    return { name: basename(path), path, kind: stat.kind, size: stat.size ?? 0, mtimeMs: stat.mtimeMs ?? 0 };
  }

  /** Incremental line reader over a file read in chunks. */
  function openLineReader(path) {
    const decoder = rangeDecoder();
    let offset = 0;
    let buffer = '';
    let eof = false;
    let closed = false;
    return {
      async readLine(context) {
        if (closed) return err(invalid('Reader closed', path));
        for (;;) {
          const index = buffer.indexOf('\n');
          if (index >= 0) {
            const text = buffer.slice(0, index);
            buffer = buffer.slice(index + 1);
            return ok({ text, terminated: true });
          }
          if (eof) {
            if (buffer.length === 0) return ok(undefined);
            const text = buffer;
            buffer = '';
            return ok({ text, terminated: false });
          }
          if (isAborted(context)) return err(aborted(path));
          let chunk = await readRange(path, offset, READ_CHUNK_BYTES);
          if (chunk.length === 0) { eof = true; continue; }
          // A leading byte-order mark at the start of the file is not content.
          if (offset === 0 && startsWithBom(chunk)) chunk = chunk.subarray(3);
          offset += chunk.length;
          buffer += decoder.decode(chunk);
        }
      },
      async close() { closed = true; },
    };
  }

  async function openBinary(path, options, context) {
    if (isAborted(context)) throw aborted(path);
    const target = absolute(path);
    const handle = await call('file.open', { path: target, noFollow: options?.noFollow === true });
    let closed = false;
    const info = {
      name: basename(target),
      path: target,
      kind: handle.kind,
      size: handle.size ?? 0,
      mtimeMs: handle.mtimeMs ?? 0,
    };
    return {
      async info(context) {
        if (closed) return err(invalid('Reader closed', path));
        if (isAborted(context)) return err(aborted(path));
        return ok(info);
      },
      async read(offset, length, context) {
        if (closed) return err(invalid('Reader closed', path));
        if (isAborted(context)) return err(aborted(path));
        if (!Number.isInteger(offset) || offset < 0) return err(invalid('Invalid offset', path));
        if (!Number.isInteger(length) || length < 0) return err(invalid('Invalid length', path));
        if (length === 0) return ok(new Uint8Array(0));
        try {
          const value = await call('file.readAt', { id: handle.id, offset, length });
          return ok(fromBase64(value.base64));
        } catch (error) {
          return err(toFileError(error, path));
        }
      },
      async scanLines(options, context) {
        if (closed) return err(invalid('Reader closed', path));
        if (isAborted(context)) return err(aborted(path));
        const { startLine, endLine } = options ?? {};
        if (!Number.isInteger(startLine) || startLine < 0) return err(invalid('Invalid line range', path));
        if (endLine !== undefined && (!Number.isInteger(endLine) || endLine <= startLine)) {
          return err(invalid('Invalid line range', path));
        }
        const scanner = new LineScanner(startLine, endLine);
        let offset = 0;
        for (;;) {
          if (isAborted(context)) return err(aborted(path));
          const value = await call('file.readAt', { id: handle.id, offset, length: READ_CHUNK_BYTES });
          const chunk = fromBase64(value.base64);
          if (chunk.length === 0) break;
          scanner.push(chunk);
          offset += chunk.length;
          if (chunk.length < READ_CHUNK_BYTES) break;
        }
        return ok(scanner.finish());
      },
      async close() {
        if (closed) return;
        closed = true;
        await call('file.close', { id: handle.id }).catch(() => {});
      },
    };
  }

  const env = {
    id: 'android',
    get cwd() { return currentCwd; },
    set cwd(value) { currentCwd = typeof value === 'string' && value.length > 0 ? value : currentCwd; },

    async absolutePath(path) { return ok(absolute(path)); },
    async joinPath(parts) { return ok(join(...(parts ?? []))); },

    async readTextFile(path, context) {
      return attempt(path, async () => {
        const bytes = await readAllBytes(absolute(path), context);
        return new TextDecoder().decode(bytes);
      });
    },

    async openTextLineReader(path, context) {
      return attempt(path, async () => {
        if (isAborted(context)) throw aborted(path);
        const target = absolute(path);
        const info = await call('file.stat', { path: target, follow: true });
        if (!info.exists) throw new FileError('not_found', `No such file: ${path}`, path);
        if (info.kind === 'directory') throw new FileError('is_directory', `Is a directory: ${path}`, path);
        return openLineReader(target);
      });
    },

    async readTextLines(path, options, context) {
      return attempt(path, async () => {
        const bytes = await readAllBytes(absolute(path), context);
        const lines = splitLines(new TextDecoder().decode(bytes));
        const max = options?.maxLines;
        return typeof max === 'number' && max >= 0 ? lines.slice(0, max) : lines;
      });
    },

    async readBinaryFile(path, context) {
      return attempt(path, async () => readAllBytes(absolute(path), context));
    },

    async openBinaryReader(path, options, context) {
      return attempt(path, async () => openBinary(path, options, context));
    },

    async writeFile(path, content) {
      return attempt(path, async () => {
        const args = typeof content === 'string'
          ? { path: absolute(path), text: content }
          : { path: absolute(path), base64: toBase64(content) };
        await call('file.write', args);
      });
    },

    async appendFile(path, content) {
      return attempt(path, async () => {
        const args = typeof content === 'string'
          ? { path: absolute(path), text: content, append: true }
          : { path: absolute(path), base64: toBase64(content), append: true };
        await call('file.write', args);
      });
    },

    async truncateFile(path, size) {
      return attempt(path, async () => {
        await call('file.write', { path: absolute(path), text: '', truncateTo: size });
      });
    },

    async flushFile(path) {
      return attempt(path, async () => {
        await call('file.flush', { path: absolute(path) });
      });
    },

    async renameFile(sourcePath, destinationPath) {
      return attempt(sourcePath, async () => {
        await call('file.rename', { source: absolute(sourcePath), destination: absolute(destinationPath) });
      });
    },

    async fileInfo(path, context) {
      return attempt(path, async () => {
        if (isAborted(context)) throw aborted(path);
        const target = absolute(path);
        const stat = await call('file.stat', { path: target, follow: true });
        if (!stat.exists) throw new FileError('not_found', `No such file: ${path}`, path);
        return statToInfo(target, stat);
      });
    },

    async listDir(path, context) {
      return attempt(path, async () => {
        if (isAborted(context)) throw aborted(path);
        const target = absolute(path);
        const stat = await call('file.stat', { path: target, follow: true });
        if (!stat.exists) throw new FileError('not_found', `No such file: ${path}`, path);
        if (stat.kind !== 'directory') throw new FileError('not_directory', `Not a directory: ${path}`, path);
        const value = await call('file.list', { path: target });
        return (value.entries ?? []).map(entry => statToInfo(join(target, entry.name), entry));
      });
    },

    async openDirReader(path, context) {
      return attempt(path, async () => {
        if (isAborted(context)) throw aborted(path);
        const target = absolute(path);
        const stat = await call('file.stat', { path: target, follow: true });
        if (!stat.exists) throw new FileError('not_found', `No such file: ${path}`, path);
        if (stat.kind !== 'directory') throw new FileError('not_directory', `Not a directory: ${path}`, path);
        const value = await call('file.list', { path: target });
        const names = (value.entries ?? []).map(entry => entry.name);
        let index = 0;
        let closed = false;
        return {
          // Entries are re-stat'ed as they are handed out, so one removed during
          // enumeration is skipped rather than reported stale.
          async next(maxEntries, context) {
            if (closed) return err(invalid('Reader closed', path));
            if (isAborted(context)) return err(aborted(path));
            if (!Number.isInteger(maxEntries) || maxEntries <= 0) return err(invalid('Invalid maxEntries', path));
            const entries = [];
            while (entries.length < maxEntries && index < names.length) {
              const name = names[index];
              index += 1;
              const child = join(target, name);
              const info = await call('file.stat', { path: child, follow: false });
              if (info.exists) entries.push(statToInfo(child, info));
            }
            return ok({ entries, done: index >= names.length });
          },
          async close() { closed = true; },
        };
      });
    },

    async watch(targets, onChange) {
      const resolved = (targets ?? []).map(target => ({
        path: absolute(target.path),
        recursive: target.recursive === true,
        exclude: target.exclude,
      }));
      const watcher = createPollingWatch({ call, targets: resolved, onChange });
      await watcher.ready();
      activeWatches.add(watcher);
      return ok({
        mode: watcher.mode,
        async close() {
          if (!activeWatches.delete(watcher)) return;
          await watcher.close();
        },
      });
    },

    async canonicalPath(path) {
      return attempt(path, async () => {
        const value = await call('file.canonical', { path: absolute(path) });
        return value.path;
      });
    },

    async exists(path) {
      return attempt(path, async () => {
        const stat = await call('file.stat', { path: absolute(path), follow: true });
        return stat.exists === true;
      });
    },

    async createDir(path, options) {
      return attempt(path, async () => {
        await call('file.mkdir', { path: absolute(path), recursive: options?.recursive === true });
      });
    },

    async remove(path, options) {
      return attempt(path, async () => {
        await call('file.remove', { path: absolute(path), recursive: options?.recursive === true, force: options?.force === true });
      });
    },

    async createTempDir(prefix) {
      return attempt(undefined, async () => {
        const value = await call('file.tempDir', { prefix: prefix ?? 'pi-' });
        return value.path;
      });
    },

    async createTempFile(options) {
      return attempt(undefined, async () => {
        const value = await call('file.tempFile', { prefix: options?.prefix ?? 'pi-', suffix: options?.suffix ?? '' });
        return value.path;
      });
    },

    async exec(command, options, context) {
      // An already-aborted context must not start a process. Adding an `abort` listener after the
      // fact does not help: the event has already fired, so the command would run to completion and
      // only then be reported as aborted.
      if (isAborted(context)) return err(new ExecutionError('aborted', 'Command aborted', undefined));

      const requestId = `s${++nextRequest}`;
      const decoders = { stdout: new StreamDecoder(), stderr: new StreamDecoder() };
      const spill = createSpill(options?.spill, call);
      const run = {
        onChunk(event) {
          const decoder = decoders[event.stream] ?? decoders.stdout;
          const text = decoder.decode(fromBase64(event.base64));
          if (text.length === 0) return;
          spill.push(event.stream, text);
          options?.onOutput?.(text, context, { stream: event.stream });
        },
      };
      runs.set(requestId, run);

      // `timeoutMs` is this adapter's internal, already-clamped form. The public ExecutionEnv
      // contract takes seconds (`options.timeout`); the native `shell.exec` op takes milliseconds.
      // Keep the conversion at this one boundary (see [shellTimeoutMillis]).
      const timeoutMs = Number.isInteger(options?.timeoutMs) && options.timeoutMs > 0
        ? options.timeoutMs
        : shellTimeoutMillis(options?.timeout);

      const onAbort = () => { call('shell.cancel', { requestId }).catch(() => {}); };
      context?.abortSignal?.addEventListener('abort', onAbort, { once: true });
      try {
        const value = await call('shell.exec', {
          command: Array.isArray(command) ? command : String(command),
          cwd: options?.cwd ? absolute(options.cwd) : currentCwd,
          env: options?.env,
          inheritEnv: options?.inheritEnv === true,
          ...(timeoutMs === undefined ? {} : { timeoutMs }),
          // Only the caller that actually consumes `onOutput` needs the host to stream every chunk
          // through the WebView. `run_script` and flow steps await the reply and would otherwise pay
          // for one JS evaluation per chunk they never read.
          stream: typeof options?.onOutput === 'function',
          requestId,
        });
        // Flush any incomplete characters held back by the streaming decoders.
        for (const stream of ['stdout', 'stderr']) {
          const tail = decoders[stream].decode();
          if (tail.length === 0) continue;
          spill.push(stream, tail);
          options?.onOutput?.(tail, context, { stream });
        }
        const spillResult = await spill.finish();
        const spillPath = spillResult.path;
        const spillTruncated = spillResult.truncated === true;
        const usedTimeoutMs = Number.isInteger(value?.timeoutMs) ? value.timeoutMs : timeoutMs;
        if (value.cancelled || context?.abortSignal?.aborted) {
          const error = new ExecutionError('aborted', 'Command aborted', undefined);
          if (spillPath !== undefined) error.spillPath = spillPath;
          return err(error);
        }
        if (value.timedOut) {
          const error = new ExecutionError('timeout', 'Command timed out', undefined);
          if (spillPath !== undefined) error.spillPath = spillPath;
          if (usedTimeoutMs !== undefined) error.timeoutMs = usedTimeoutMs;
          return err(error);
        }
        return ok({
          exitCode: typeof value.exitCode === 'number' ? value.exitCode : 1,
          ...(usedTimeoutMs === undefined ? {} : { timeoutMs: usedTimeoutMs }),
          ...(spillPath === undefined ? {} : { spillPath }),
          ...(spillTruncated ? { spillTruncated: true } : {}),
          ...(value.streamTruncated === true ? { streamTruncated: true } : {}),
          ...(value.drainIncomplete === true ? { drainIncomplete: true } : {}),
          ...(value.stdoutTruncated === true ? { stdoutTruncated: true } : {}),
          ...(value.stderrTruncated === true ? { stderrTruncated: true } : {}),
        });
      } catch (error) {
        if (context?.abortSignal?.aborted || isAbortError(error)) {
          return err(new ExecutionError('aborted', 'Command aborted', error));
        }
        return err(new ExecutionError('spawn_error', error?.message ?? String(error), error));
      } finally {
        context?.abortSignal?.removeEventListener('abort', onAbort);
        runs.delete(requestId);
      }
    },

    async cleanup() {
      unsubscribe();
      for (const watcher of [...activeWatches]) await watcher.close();
      activeWatches.clear();
      const ids = [...runs.keys()];
      runs.clear();
      await Promise.all(ids.map(id => call('shell.cancel', { requestId: id }).catch(() => {})));
      await call('file.cleanup', {}).catch(() => {});
    },
  };

  return env;
}

/** Writes the complete raw output to a temp file once either spill threshold is crossed. */
function createSpill(options, call) {
  if (!options || (options.afterBytes === undefined && options.afterLines === undefined)) {
    return { push() {}, async finish() { return { path: undefined, truncated: false }; } };
  }
  const encoder = new TextEncoder();
  const maxBytes = Number.isInteger(options.maxBytes) && options.maxBytes > 0 ? options.maxBytes : Infinity;
  let path;
  let bytes = 0;
  let lines = 0;
  let written = 0;
  let truncated = false;
  let buffered = [];
  let queue = Promise.resolve();
  const crosses = () => (options.afterBytes !== undefined && bytes >= options.afterBytes)
    || (options.afterLines !== undefined && lines >= options.afterLines);
  const flush = text => {
    queue = queue.then(async () => {
      if (truncated) return;
      if (!path) {
        const created = await call('file.tempFile', { prefix: 'pi-spill-', suffix: '.txt' });
        path = created.path;
        const all = `${buffered.join('')}${text}`;
        buffered = [];
        const size = encoder.encode(all).length;
        await call('file.write', { path, text: all });
        written = size;
        if (written > maxBytes) truncated = true;
        return;
      }
      if (!text) return;
      const size = encoder.encode(text).length;
      if (written + size > maxBytes) { truncated = true; return; }
      await call('file.write', { path, text, append: true });
      written += size;
    });
  };
  return {
    push(_stream, text) {
      bytes += encoder.encode(text).length;
      for (let i = 0; i < text.length; i += 1) if (text.charCodeAt(i) === 10) lines += 1;
      if (!path && !crosses()) { buffered.push(text); return; }
      if (!path) { buffered.push(text); flush(''); return; }
      flush(text);
    },
    async finish() {
      await queue;
      return { path, truncated };
    },
  };
}
