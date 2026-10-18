// Provider HTTP, performed in Kotlin instead of by the WebView.
//
// Every provider request pi-ai makes goes out through `options?.fetch ?? globalThis.fetch`. Inside
// the WebView that is the browser's own fetch, and the document origin is `https://pi.local`, which
// no provider grants via CORS: the preflight/response is refused before a model call can happen,
// and the OAuth token exchange fails the same way. So the runtime installs a fetch-compatible
// transport that hands the request to the Kotlin host, which owns the socket, and streams the
// response body back over the same bridge event channel `shell.output` already uses.
//
// `fetch(input, init)` here returns a real `Response` (status, headers, readable body stream) so
// pi-ai, the OpenAI/Anthropic SDKs and `@google/genai` see a normal fetch. The shim deliberately
// reaches no Node API and no bare global: everything comes off `scope`, so the same module runs in
// the WebView, in the bundled runtime and in a plain Node test.

/** Kotlin ops. `http.request` accepts one request; `http.cancel` stops it by id. */
const CAPABILITIES_OP = 'http.capabilities';
const REQUEST_OP = 'http.request';
const CANCEL_OP = 'http.cancel';

/** Host-to-JS events, mirroring `shell.output`. One head, ordered chunks, one end (or one error). */
const HEAD_EVENT = 'http.head';
const CHUNK_EVENT = 'http.chunk';
const END_EVENT = 'http.end';
const ERROR_EVENT = 'http.error';

/** The host's own limits. These are advertised by `http.capabilities` and used as client defaults. */
export const DEFAULT_CONNECT_TIMEOUT_MS = 30_000;
/** Per-read idle timeout: how long the socket may stay silent mid-stream before it fails. */
export const DEFAULT_READ_TIMEOUT_MS = 120_000;
export const DEFAULT_MAX_BYTES = 32 * 1024 * 1024;

/**
 * How large one request body may be. The host enforces its own message ceiling, so a body over the
 * advertised limit is refused here — a dropped bridge message is never answered, and a fetch waiting
 * on a reply that will not come is worse than a clear failure.
 */
export const DEFAULT_MAX_REQUEST_BYTES = 8 * 1024 * 1024;

const NETWORK_METHODS = ['stream', 'streamSimple', 'complete', 'completeSimple',
  'streamDeferred', 'fetchDeferred', 'cancelDeferred', 'generateImages', 'classify'];

/** Request headers the transport owns: forwarding them would fight the connection or double-decode. */
const MANAGED_REQUEST_HEADERS = new Set(['host', 'content-length', 'connection', 'transfer-encoding', 'accept-encoding']);

const errorOf = (name, message, code) => {
  const error = new Error(message);
  error.name = name;
  if (code) error.code = code;
  return error;
};

const abortError = (scope, message) => (typeof scope.DOMException === 'function'
  ? new scope.DOMException(message, 'AbortError')
  : errorOf('AbortError', message));

const encodeBase64 = (scope, bytes) => {
  let binary = '';
  const window = 0x8000;
  for (let index = 0; index < bytes.length; index += window) {
    binary += String.fromCharCode.apply(null, bytes.subarray(index, index + window));
  }
  return scope.btoa(binary);
};

const decodeBase64 = (scope, text) => {
  const binary = scope.atob(text ?? '');
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
};

/** Normalize Headers instances and plain records (pi-ai uses both) into one lowercase record. */
const mergeHeaders = sources => {
  const merged = {};
  for (const source of sources) {
    if (!source) continue;
    if (typeof source.forEach === 'function' && typeof source.get === 'function') {
      source.forEach((value, name) => { merged[String(name).toLowerCase()] = String(value); });
      continue;
    }
    for (const [name, value] of Object.entries(source)) {
      // A null header means "suppress the provider default" upstream; it must not be sent.
      if (value === undefined || value === null) continue;
      merged[String(name).toLowerCase()] = String(value);
    }
  }
  return merged;
};

/** Body bodies pi-ai and its SDKs use: JSON strings, byte arrays, URLSearchParams, Blobs/streams. */
const bodyBytes = async (scope, body) => {
  if (body === undefined || body === null) return undefined;
  if (typeof body === 'string') return new scope.TextEncoder().encode(body);
  if (typeof body.byteLength === 'number') {
    return new Uint8Array(body.buffer ?? body, body.byteOffset ?? 0, body.byteLength);
  }
  if (typeof body.arrayBuffer === 'function') return new Uint8Array(await body.arrayBuffer());
  if (typeof body.getReader === 'function') {
    const reader = body.getReader();
    const parts = [];
    let total = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parts.push(value);
      total += value.length;
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const part of parts) { bytes.set(part, offset); offset += part.length; }
    return bytes;
  }
  if (typeof body.toString === 'function') return new scope.TextEncoder().encode(body.toString());
  throw new Error('Unsupported request body type');
};

const isRequestLike = input => Boolean(input) && typeof input === 'object' && typeof input.url === 'string';

/**
 * One in-flight request: the Kotlin side owns the socket, this owns the ordering and the stream.
 *
 * Chunks are accepted by sequence number and handed on contiguously, so an SSE frame split across
 * two reads is still delivered whole and in order. The body is capped by the host; a truncated body
 * terminates the stream as an error instead of a clean EOF, because a silently short body would be
 * parsed as a complete provider answer.
 */
class Exchange {
  constructor(scope, bridge, requestId, { onSettled }) {
    this.scope = scope;
    this.bridge = bridge;
    this.requestId = requestId;
    this.onSettled = onSettled;
    this.pending = new Map();
    this.next = 0;
    this.responded = false;
    this.terminated = false;
    this.controller = undefined;
    this.signalListener = undefined;
    this.timer = undefined;
    this.response = new Promise((resolve, reject) => { this.resolve = resolve; this.reject = reject; });
    this.stream = new scope.ReadableStream({
      start: controller => { this.controller = controller; this.pump(); },
      cancel: () => { this.abort('The response body was cancelled'); },
    });
  }

  /** Hand every contiguous chunk to the reader; a gap in `seq` can only mean a lost chunk. */
  pump() {
    if (!this.controller) return;
    while (this.pending.has(this.next)) {
      const bytes = this.pending.get(this.next);
      this.pending.delete(this.next);
      this.next += 1;
      this.controller.enqueue(bytes);
    }
  }

  settle() {
    this.terminated = true;
    if (this.signalListener) this.signal.removeEventListener('abort', this.signalListener);
    if (this.timer !== undefined) this.scope.clearTimeout(this.timer);
    this.onSettled(this.requestId);
  }

  respond(head) {
    if (this.terminated) return;
    this.responded = true;
    // A 1xx is not representable as a Response; report it as a bad gateway rather than throw.
    const status = Number.isInteger(head.status) && head.status >= 200 && head.status <= 599 ? head.status : 502;
    const bodyless = status === 204 || status === 205 || status === 304;
    const init = { status, headers: head.headers ?? {} };
    if (typeof head.statusText === 'string' && head.statusText) init.statusText = head.statusText;
    this.resolve(new this.scope.Response(bodyless ? null : this.stream, init));
  }

  fail(error) {
    if (this.terminated) return;
    this.settle();
    if (!this.responded) this.reject(error);
    else this.terminate(error, true);
  }

  end({ truncated, cancelled }) {
    if (this.terminated) return;
    if (cancelled) return this.fail(abortError(this.scope, 'The response stream was cancelled by the host'));
    if (truncated) {
      return this.fail(errorOf('TransportError',
        `Response body exceeded the transport limit of ${this.maxBytes ?? DEFAULT_MAX_BYTES} bytes`, 'truncated'));
    }
    if (this.pending.size > 0) return this.fail(errorOf('TransportError', 'Response body chunks arrived out of order'));
    // A body that ended before any head is still a successful empty response, so the head is
    // synthesized before the exchange is settled (settling closes the door on any further event).
    if (!this.responded) this.respond({ status: 200, statusText: '', headers: {} });
    this.settle();
    this.terminate(undefined, false);
  }

  terminate(error, isError) {
    if (!this.controller) return;
    try { if (isError) this.controller.error(error); else this.controller.close(); } catch { /* already closed */ }
  }

  /** Cancellation: tell the host to stop, then surface AbortError to whoever is waiting. */
  abort(message) {
    if (this.terminated) return;
    void this.bridge.call(CANCEL_OP, { requestId: this.requestId }).catch(() => undefined);
    this.fail(abortError(this.scope, message));
  }

  dispatch(event) {
    if (this.terminated) return;
    switch (event.event) {
      case HEAD_EVENT: return this.respond(event);
      case CHUNK_EVENT:
        this.pending.set(event.seq, decodeBase64(this.scope, event.base64));
        return this.pump();
      case END_EVENT: return this.end(event);
      case ERROR_EVENT: return this.fail(errorOf('TransportError', event.message ?? 'Provider transport failed', event.code));
      default: return undefined;
    }
  }
}

/**
 * The fetch-compatible transport.
 *
 * `ambient` is the fallback used when the host cannot perform HTTP at all (a plain Node or browser
 * harness, where `http.capabilities` is not implemented): the current global fetch when something
 * else installed it, otherwise the one that was there before this transport was installed.
 */
export function createBridgeFetch(bridge, options = {}) {
  const scope = options.scope ?? globalThis;
  const originalFetch = scope.fetch;
  const exchanges = new Map();
  const connectTimeoutMs = options.connectTimeoutMs ?? DEFAULT_CONNECT_TIMEOUT_MS;
  const readTimeoutMs = options.readTimeoutMs ?? DEFAULT_READ_TIMEOUT_MS;
  const maxBytes = options.maxBytes ?? DEFAULT_MAX_BYTES;
  let sequence = 0;
  let supported;
  let limits;

  if (typeof bridge?.subscribe === 'function') {
    bridge.subscribe(event => {
      const exchange = event && exchanges.get(event.requestId);
      if (exchange) exchange.dispatch(event);
    });
  }

  const onSettled = requestId => exchanges.delete(requestId);

  const ambientFetch = () => {
    const current = scope.fetch;
    if (typeof current === 'function' && current !== bridgeFetch) return current;
    return typeof originalFetch === 'function' && originalFetch !== bridgeFetch ? originalFetch : scope.fetch?.bind?.(scope);
  };

  const transportAvailable = async () => {
    if (supported === undefined) {
      try {
        const info = await bridge.call(CAPABILITIES_OP, {});
        supported = info?.transport === true;
        if (supported) {
          limits = {
            maxBytes: Number.isFinite(info?.maxBytes) ? info.maxBytes : maxBytes,
            maxRequestBytes: Number.isFinite(info?.maxRequestBytes) ? info.maxRequestBytes : DEFAULT_MAX_REQUEST_BYTES,
          };
        }
      } catch { supported = false; }
    }
    return supported;
  };

  async function bridgeFetch(input, init = {}) {
    if (!(await transportAvailable())) {
      const ambient = ambientFetch();
      if (typeof ambient !== 'function') throw errorOf('TransportError', 'No HTTP transport is available in this runtime');
      return ambient(input, init);
    }
    const requestLike = isRequestLike(input) ? input : undefined;
    const signal = init?.signal ?? requestLike?.signal ?? undefined;
    const timeoutMs = Number.isFinite(init?.timeoutMs) && init.timeoutMs > 0 ? init.timeoutMs : undefined;
    const url = typeof input === 'string' ? input : String(requestLike?.url ?? input);
    const method = String(init?.method ?? requestLike?.method ?? 'GET').toUpperCase();
    const headers = mergeHeaders([requestLike?.headers, init?.headers]);
    for (const name of MANAGED_REQUEST_HEADERS) delete headers[name];
    const body = await bodyBytes(scope, init?.body ?? (requestLike && typeof requestLike.arrayBuffer === 'function' ? new Uint8Array(await requestLike.arrayBuffer()) : undefined));
    const ceiling = limits ?? { maxBytes, maxRequestBytes: DEFAULT_MAX_REQUEST_BYTES };
    if (body && body.length > ceiling.maxRequestBytes) {
      throw errorOf('TransportError',
        `Request body of ${body.length} bytes exceeds the transport limit of ${ceiling.maxRequestBytes} bytes`,
        'request_too_large');
    }

    const requestId = `http-${++sequence}`;
    const exchange = new Exchange(scope, bridge, requestId, { onSettled });
    exchange.maxBytes = ceiling.maxBytes;
    exchanges.set(requestId, exchange);

    const abort = () => exchange.abort('The request was aborted');
    if (signal) {
      if (signal.aborted) {
        exchanges.delete(requestId);
        throw abortError(scope, 'The request was aborted');
      }
      exchange.signal = signal;
      exchange.signalListener = abort;
      signal.addEventListener('abort', abort, { once: true });
    }
    if (timeoutMs !== undefined) {
      exchange.timer = scope.setTimeout(() => exchange.fail(errorOf('TimeoutError',
        `Provider request timed out after ${timeoutMs}ms`, 'timeout')), timeoutMs);
      if (typeof exchange.timer === 'object' && typeof exchange.timer.unref === 'function') exchange.timer.unref();
    }

    try {
      const accepted = await bridge.call(REQUEST_OP, {
        requestId,
        method,
        url,
        headers,
        body: body ? encodeBase64(scope, body) : undefined,
        connectTimeoutMs,
        readTimeoutMs: timeoutMs ?? readTimeoutMs,
        maxBytes,
      });
      if (accepted?.accepted === false) exchange.fail(errorOf('TransportError', 'The host refused the request', 'refused'));
    } catch (error) {
      exchange.fail(errorOf('TransportError',
        `The host could not start the request: ${error?.message ?? String(error)}`, error?.code));
    }
    return exchange.response;
  }

  return bridgeFetch;
}

/**
 * Install the transport as the ambient fetch, so the provider calls that take no `fetch` option —
 * the OAuth token exchange, dynamic model listing, the Google SDK — go through Kotlin too.
 */
export function installFetchTransport(bridge, options = {}) {
  const scope = options.scope ?? globalThis;
  const transport = createBridgeFetch(bridge, { ...options, scope });
  scope.fetch = transport;
  return transport;
}

/**
 * Wrap a `Models` collection so every operation that performs provider HTTP carries the transport
 * explicitly (pi-ai's documented seam: `ProviderRequestOptions.fetch`). Non-network members pass
 * through with their original receiver. A caller that set its own `fetch` keeps it.
 */
export function withBridgeFetch(models, bridgeFetch) {
  const bound = new Map();
  const originalOf = name => {
    if (!bound.has(name)) bound.set(name, models[name].bind(models));
    return bound.get(name);
  };
  return new Proxy(models, {
    get(target, property) {
      if (typeof property === 'string' && NETWORK_METHODS.includes(property)) {
        const invoke = originalOf(property);
        return (...args) => {
          while (args.length < 3) args.push(undefined);
          const options = args[2];
          args[2] = {
            ...(options && typeof options === 'object' ? options : {}),
            fetch: options?.fetch ?? bridgeFetch,
          };
          return invoke(...args);
        };
      }
      const value = Reflect.get(target, property, target);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
}
