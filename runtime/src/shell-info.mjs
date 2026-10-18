// The one description of the device shell every tool shares.
//
// Android does not ship bash: `/system/bin/sh` is mksh.  The interpreter path, the host-limit
// contract and the timeout conversion used to be copied into `run-script-tool.mjs`,
// `flow-capabilities.mjs`, `flow-example.mjs` and the old upstream `bash` tool.  Every copy could
// drift from the others (and from `backend/ShellLimits.kt`, which is the side that really enforces
// the numbers), so they live here now.

/** The interpreter every shell-shaped tool hands its program to. Never the script itself. */
export const SHELL_EXECUTABLE = '/system/bin/sh';

/** What that interpreter is. Used in tool descriptions so the model is not told "bash". */
export const SHELL_KIND = 'mksh';

/** A failure the model may read: the host would not say what limits it enforces. */
export class ShellLimitsError extends Error {
  constructor(message, cause) {
    super(message);
    this.name = 'ShellLimitsError';
    this.code = 'shell_limits';
    if (cause !== undefined) this.cause = cause;
  }
}

/**
 * A limit the host has not advertised yet. It is deliberately generous: the capture cap is 256 KiB
 * per stream, while this bounds the *streamed* copy the WebView and the spill file receive.
 */
export const DEFAULT_MAX_STREAM_BYTES = 8 * 1024 * 1024;

/**
 * The limits the Kotlin host enforces (`backend/ShellLimits.kt`) and advertises through
 * `shell.capabilities`.
 *
 * `maxStreamBytes` is optional in the protocol: an older host that does not advertise it still
 * works, and the bounded-stream default below keeps a chatty command from flooding the WebView.
 */
export function normalizeShellLimits(advertised) {
  const read = value => (typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null);
  const maxOutputBytes = read(advertised?.maxOutputBytes);
  const defaultTimeoutMs = read(advertised?.defaultTimeoutMs);
  const maxTimeoutMs = read(advertised?.maxTimeoutMs);
  const advertisedStream = read(advertised?.maxStreamBytes);
  if (maxOutputBytes === null || defaultTimeoutMs === null || maxTimeoutMs === null || defaultTimeoutMs > maxTimeoutMs) {
    throw new ShellLimitsError(
      `The host did not advertise usable shell limits: ${JSON.stringify(advertised ?? null)}`,
    );
  }
  return {
    maxOutputBytes,
    defaultTimeoutMs,
    maxTimeoutMs,
    maxStreamBytes: advertisedStream ?? DEFAULT_MAX_STREAM_BYTES,
  };
}

/**
 * Read the host's limits once and reuse them.
 *
 * A rejected read is NOT cached: a transient `shell.capabilities` failure must not disable the tool
 * for the life of the runtime. The promise is cleared as soon as it rejects, so the next call tries
 * again.
 */
export function createShellLimitsReader(read) {
  let cached;
  return {
    async get() {
      if (cached === undefined) {
        cached = (async () => {
          if (typeof read !== 'function') throw new ShellLimitsError('No host shell limits were provided');
          try {
            return normalizeShellLimits(await read());
          } catch (error) {
            if (error instanceof ShellLimitsError) throw error;
            throw new ShellLimitsError(`Could not read the host's shell limits: ${error?.message ?? String(error)}`, error);
          }
        })();
        // Attach a handler so a rejection is not unhandled, and clear the cache for the next call.
        cached.catch(() => { cached = undefined; });
      }
      return cached;
    },
    reset() { cached = undefined; },
  };
}

/** Convert a whole or fractional number of seconds into the host's milliseconds, or null if unusable. */
export function requestedTimeoutMillis(seconds) {
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds <= 0) return null;
  return Math.max(1, Math.round(seconds * 1000));
}

/** A milliseconds value the way the model asked for it, without a trailing `.0`. */
export function formatSeconds(milliseconds) {
  if (!Number.isFinite(milliseconds)) return String(milliseconds);
  const seconds = milliseconds / 1000;
  return Number.isInteger(seconds) ? String(seconds) : seconds.toFixed(3).replace(/0+$/, '').replace(/\.$/, '');
}
