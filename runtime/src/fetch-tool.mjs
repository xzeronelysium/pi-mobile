// `fetch_url`: the agent's way to reach the network.
//
// The harness could only fetch through `/system/bin/curl`, a firmware binary (LG ships it; AOSP's
// base set does not), so on a device without it the agent had no way to fetch anything at all. The
// app already owns a native streaming HTTP client in Kotlin and `src/fetch.mjs` already exposes it
// as a fetch-compatible transport, so the tool takes *that* transport as a parameter and never an
// ambient `fetch`: the request leaves from Kotlin's socket, and the WebView's `https://pi.local`
// origin — which no provider grants via CORS — is never involved.
//
// Two things the tool must never do, because the Kotlin host forwards the runtime's console to
// logcat (`backend/ConsoleRedaction.kt`, tag `PiRuntimeConsole`):
//   * log or echo request headers or a request body at any level — the app's convention is that the
//     runtime logs messages and never header values, and the redactor is a safety net, not a licence;
//   * report a body as complete when it was cut. Truncation is always explicit, always carries the
//     true received byte count, and always bounds what is retained in memory.
//
// The whole module runs in the WebView: it reaches no Node API and no bare `Buffer`, only globals
// the WebView has (URL, TextDecoder, AbortController, btoa) plus the injected transport and the
// injected bridge `call` for workspace writes.
//
// The argument schema is a plain JSON Schema object rather than a TypeBox builder on purpose: the
// harness validates `parameters` through pi-ai's `validateToolArguments`, which compiles any JSON
// Schema and has an explicit non-TypeBox coercion path (a tool built here validates identically,
// `required` included), and a plain object keeps this module free of a dependency the runtime does
// not itself declare.

import { defineExtension, defineTool } from '@earendil-works/pi-durable';
import { resolve as resolvePath } from './path.mjs';

/** The tool's name. Not `fetch`: the model must not confuse a tool call with a shell invocation. */
export const FETCH_TOOL_NAME = 'fetch_url';
/** The extension that carries it; installed alongside the coding tools and selected by default. */
export const FETCH_EXTENSION_NAME = 'fetch';

/** The platform's own explicit-text bound (pi-durable `truncate.js`): 50KB. */
const PLATFORM_MAX_BYTES = 50 * 1024;
/**
 * How much of a body is retained by default. Deliberately just under the platform's own bound: the
 * header block and the truncation marker have to survive that second cut, or the model would be
 * handed a body that looks complete.
 */
export const DEFAULT_MAX_BYTES = 48 * 1024;
/** The ceiling a per-call `maxBytes` can never raise: the retained body stays bounded in memory. */
export const HARD_MAX_BYTES = 4 * 1024 * 1024;

/** Methods that are a single HTTP token; anything else is a malformed call, not a request. */
const METHOD = /^[A-Z][A-Z0-9-]*$/;

/** Content types that are text, not bytes to be stored. */
const TEXTISH = /^(text\/|application\/(json|xml|javascript|ecmascript|x-www-form-urlencoded|x-ndjson|ld\+json|graphql)|[^/]+\/[^;]+\+json|[^/]+\/[^;]+\+xml)/i;

/** A failure the model should read: `code` mirrors the host's, which is never secret material. */
export class FetchToolError extends Error {
  constructor(code, message, cause) {
    super(message);
    this.name = 'FetchToolError';
    this.code = code;
    if (cause !== undefined) this.cause = cause;
  }
}

const abortError = message => (typeof DOMException === 'function'
  ? new DOMException(message, 'AbortError')
  : Object.assign(new Error(message), { name: 'AbortError' }));

const isAbort = error => error?.name === 'AbortError';

/** The request's own scheme rule: http and https only, and only a real absolute URL. */
function parseTarget(args) {
  const raw = typeof args?.url === 'string' ? args.url.trim() : '';
  if (raw === '') throw new FetchToolError('invalid_url', 'url is required');
  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    throw new FetchToolError('invalid_url', `Not an absolute URL: ${raw}`);
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new FetchToolError('invalid_url', `Only http and https URLs can be fetched, not ${parsed.protocol.replace(':', '')}: ${raw}`);
  }
  const method = args?.method === undefined ? 'GET' : String(args.method).trim().toUpperCase();
  if (!METHOD.test(method)) throw new FetchToolError('invalid_method', `Not an HTTP method: ${JSON.stringify(args?.method)}`);
  return { url: parsed.toString(), href: parsed.href, method };
}

/** Request headers: a flat record of strings. Never logged, never echoed back to the model. */
function parseHeaders(value) {
  if (value === undefined || value === null) return {};
  if (typeof value !== 'object' || Array.isArray(value)) throw new FetchToolError('invalid_request', 'headers must be an object of string values');
  const headers = {};
  for (const [name, header] of Object.entries(value)) {
    if (header === undefined || header === null) continue;
    if (typeof header !== 'string' && typeof header !== 'number') {
      throw new FetchToolError('invalid_request', `Header ${name} must be a string`);
    }
    headers[name] = String(header);
  }
  return headers;
}

function parseArguments(args) {
  const target = parseTarget(args);
  const body = args?.body;
  if (body !== undefined && typeof body !== 'string') throw new FetchToolError('invalid_request', 'body must be a string');
  const timeoutMs = args?.timeoutMs;
  if (timeoutMs !== undefined && (!Number.isInteger(timeoutMs) || timeoutMs <= 0)) {
    throw new FetchToolError('invalid_request', 'timeoutMs must be a positive whole number of milliseconds');
  }
  return { ...target, headers: parseHeaders(args?.headers), body, timeoutMs };
}

/** The retained-body bound: an explicit bound is honoured, clamped, and never allowed to be a lie. */
function parseBound(value, fallback) {
  if (value === undefined || value === null) return fallback;
  if (!Number.isInteger(value) || value <= 0) {
    throw new FetchToolError('invalid_bound', 'maxBytes must be a positive whole number of bytes');
  }
  return Math.min(value, HARD_MAX_BYTES);
}

/** `saveToPath` resolves against the workspace root and must stay inside it. */
function resolveSavePath(root, target) {
  const base = typeof root === 'string' && root !== '' ? root : '/';
  const path = resolvePath(base, String(target));
  const inside = base === '/' || path === base || path.startsWith(`${base}/`);
  if (!inside) {
    throw new FetchToolError('workspace_escape', `saveToPath must stay inside the workspace (${base}): ${target}`);
  }
  return path;
}

const headerEntries = headers => {
  if (!headers) return [];
  if (typeof headers.forEach === 'function' && typeof headers.get === 'function') {
    const entries = [];
    headers.forEach((value, name) => entries.push([String(name).toLowerCase(), String(value)]));
    return entries;
  }
  return Object.entries(headers).map(([name, value]) => [String(name).toLowerCase(), String(value ?? '')]);
};

const headerValue = (headers, name) => {
  const found = headerEntries(headers).find(([key]) => key === name);
  return found === undefined ? '' : found[1];
};

const statusBlock = (status, statusText, headers) => {
  const lines = [`HTTP ${status}${statusText ? ` ${statusText}` : ''}`];
  for (const [name, value] of headerEntries(headers)) lines.push(`${name}: ${value}`);
  return `${lines.join('\n')}\n\n`;
};

/** base64 without a Node `Buffer`: windowed, so a large chunk cannot blow the call stack. */
function toBase64(bytes) {
  let binary = '';
  const window = 0x8000;
  for (let index = 0; index < bytes.length; index += window) {
    binary += String.fromCharCode.apply(null, bytes.subarray(index, index + window));
  }
  return btoa(binary);
}

/** The charset the response declares, when it names one the platform can decode. */
function decoderFor(headers) {
  const declared = /charset="?([^";]+)"?/i.exec(headerValue(headers, 'content-type'))?.[1];
  if (declared) {
    try {
      return new TextDecoder(declared.trim());
    } catch { /* an unknown label falls back to UTF-8 */ }
  }
  return new TextDecoder();
}

const markerOf = lines => `\n\n…[${FETCH_TOOL_NAME}: ${lines.join(' ')}]…`;

/**
 * The tool.
 *
 * `transport` is the bridge fetch (`createBridgeFetch`), the only transport used: the request is
 * performed in Kotlin. `call` is the bridge's own `call`, used for the workspace file ops
 * (`file.write`) that stream `saveToPath`. `workspaceRoot` is read per call, because the tool is
 * installed before the host knows the workspace.
 */
export function createFetchTool({ transport, call, workspaceRoot, maxBytes = DEFAULT_MAX_BYTES } = {}) {
  if (typeof transport !== 'function') throw new TypeError('createFetchTool requires a transport');
  if (typeof call !== 'function') throw new TypeError('createFetchTool requires a bridge call');
  const rootOf = typeof workspaceRoot === 'function' ? workspaceRoot : () => (typeof workspaceRoot === 'string' ? workspaceRoot : '/');
  const defaultBound = parseBound(maxBytes, DEFAULT_MAX_BYTES);

  return defineTool({
    name: FETCH_TOOL_NAME,
    description: `Fetch a http:// or https:// URL with the app's own native HTTP client and return the `
      + `status, the response headers and the body. This is the portable way to fetch on this device: it `
      + `needs no shell and no curl, which is a firmware binary that may not exist here. A text-ish body `
      + `(text/*, JSON, XML, JavaScript, form data, +json/+xml) comes back as text, and a long body is cut `
      + `at maxBytes (default ${DEFAULT_MAX_BYTES} bytes, ceiling ${HARD_MAX_BYTES}) with an explicit marker `
      + `carrying the true byte count, so an incomplete body is never mistaken for a complete one. A binary `
      + `body is reported by size and type rather than decoded; pass saveToPath to stream it into the `
      + `workspace instead (the result then carries the absolute path and the byte count). The status is `
      + `returned whatever it is: a 4xx or 5xx is a normal result carrying the server's own error body, not `
      + `a tool failure. Only a transport failure, a refused argument or an abort is an error.`,
    // A plain JSON Schema: pi-ai's `validateToolArguments` compiles any JSON Schema and has an
    // explicit non-TypeBox coercion path, so this validates (and coerces) exactly as a TypeBox
    // parameter object would, without adding an import the runtime does not declare.
    parameters: {
      type: 'object',
      properties: {
        url: { type: 'string', description: 'Absolute http:// or https:// URL to fetch.' },
        method: { type: 'string', description: 'HTTP method; defaults to GET.' },
        headers: {
          type: 'object',
          additionalProperties: { type: 'string' },
          description: 'Extra request headers. They are sent to the host and never logged or echoed back.',
        },
        body: { type: 'string', description: 'Request body text, sent verbatim.' },
        saveToPath: {
          type: 'string',
          description: 'Workspace path (relative or absolute) to stream the response body into, for a large or binary body.',
        },
        maxBytes: { type: 'integer', description: `How many body bytes to retain for the text result; default ${DEFAULT_MAX_BYTES}, ceiling ${HARD_MAX_BYTES}.` },
        timeoutMs: { type: 'integer', description: 'Abort the request after this many milliseconds.' },
      },
      required: ['url'],
      additionalProperties: false,
    },
    async execute(args, api, context) {
      const { url, method, headers, body, timeoutMs } = parseArguments(args);
      const bound = parseBound(args?.maxBytes, defaultBound);
      const saveTo = args?.saveToPath === undefined || args?.saveToPath === null ? undefined : String(args.saveToPath);
      const savedPath = saveTo === undefined || saveTo.trim() === '' ? undefined : resolveSavePath(rootOf(), saveTo);

      // Cancellation is the caller's signal, relayed to the transport's own abort path so the host
      // is told to stop the transfer rather than left writing into a stream nobody reads.
      const controller = new AbortController();
      const signal = context?.abortSignal;
      const onAbort = () => controller.abort();
      if (signal?.aborted === true) throw abortError('The request was aborted');
      if (typeof signal?.addEventListener === 'function') signal.addEventListener('abort', onAbort, { once: true });

      try {
        let response;
        try {
          response = await transport(url, {
            method,
            headers,
            ...(body === undefined ? {} : { body }),
            ...(timeoutMs === undefined ? {} : { timeoutMs }),
            signal: controller.signal,
          });
        } catch (error) {
          if (isAbort(error)) throw abortError('The request was aborted');
          throw transportError(error, url);
        }

        const status = Number.isInteger(response?.status) ? response.status : 0;
        const statusText = typeof response?.statusText === 'string' ? response.statusText : '';
        const headersOut = Object.fromEntries(headerEntries(response?.headers));
        const contentType = headerValue(response?.headers, 'content-type');
        const binary = contentType !== '' && !TEXTISH.test(contentType);
        // A binary body is never decoded, and a body streamed to disk is never also held in memory:
        // the retained-bytes bound is the only copy that can grow.
        const retain = savedPath === undefined && !binary;
        const decoder = retain ? decoderFor(response?.headers) : undefined;

        const reader = typeof response?.body?.getReader === 'function' ? response.body.getReader() : undefined;
        let text = '';
        let retained = 0;
        let bytes = 0;
        let transportTruncated = false;
        let wrote = false;
        if (reader) {
          for (;;) {
            let step;
            try {
              step = await reader.read();
            } catch (error) {
              if (isAbort(error)) throw abortError('The request was aborted');
              // The host's own ceiling: the body really is incomplete, and saying so is the point.
              if (error?.code === 'truncated') { transportTruncated = true; break; }
              throw transportError(error, url);
            }
            if (step.done) break;
            const chunk = step.value;
            if (chunk === undefined || chunk === null || chunk.length === 0) continue;
            bytes += chunk.length;
            if (savedPath !== undefined) {
              await call('file.write', { path: savedPath, base64: toBase64(chunk), append: wrote });
              wrote = true;
            } else if (retain && retained < bound) {
              const slice = chunk.subarray(0, bound - retained);
              retained += slice.length;
              text += decoder.decode(slice, { stream: true });
            }
          }
          // A body cut at the bound ends on a byte boundary, not a character one: flushing would
          // emit a replacement character for a character that was never fully received.
          if (retain && retained === bytes && !transportTruncated) text += decoder.decode();
        } else if (savedPath !== undefined) {
          await call('file.write', { path: savedPath, text: '' });
          wrote = true;
        }

        const truncated = transportTruncated || (retain && bytes > retained);
        const truncatedBy = truncated ? (transportTruncated ? 'transport' : 'bytes') : null;
        let marker;
        if (transportTruncated) {
          marker = `the host truncated the transfer at its own limit, so this body is incomplete; the ${bytes} bytes received are all there is to show`;
        } else if (truncated) {
          // Whatever the body became — retained text, a saved file, or just a byte count — the cut
          // is stated. A binary or saved body that was cut must not look complete either.
          const where = retain ? `the first ${retained} are shown` : savedPath !== undefined ? 'the saved file is incomplete' : 'none are shown';
          marker = `body truncated at the ${bound}-byte limit: ${bytes} bytes were received, ${where}`;
        }

        const summary = savedPath !== undefined
          ? `[saved ${bytes} bytes to ${savedPath}]`
          : binary
            ? `[binary body: ${bytes} bytes of ${contentType || 'an undeclared content type'}; not decoded as text — pass saveToPath to store it in the workspace]`
            : text;
        const content = `${statusBlock(status, statusText, response?.headers)}${summary}${marker === undefined ? '' : markerOf([marker])}`;

        return {
          content: [{ type: 'text', text: content }],
          details: {
            url,
            method,
            status,
            statusText,
            ok: status >= 200 && status < 300,
            contentType,
            binary,
            bytes,
            shownBytes: savedPath !== undefined ? 0 : retained,
            limit: bound,
            truncated,
            truncatedBy,
            ...(savedPath === undefined ? {} : { savedPath, savedBytes: bytes }),
            headers: headersOut,
          },
          ...(truncated ? { diagnostics: [{ severity: 'warn', code: truncatedBy, message: marker }] } : {}),
        };
      } finally {
        if (typeof signal?.removeEventListener === 'function') signal.removeEventListener('abort', onAbort);
      }
    },
  });
}

/** The extension the host installs, so the tool is offered to the model like any other. */
export function createFetchTools(options) {
  return defineExtension({ name: FETCH_EXTENSION_NAME, tools: [createFetchTool(options)] });
}

/**
 * A transport failure, told as itself.
 *
 * Only the host's own error text and the URL cross: the request headers and the request body are
 * never part of any message, whatever the failure was.
 */
function transportError(error, url) {
  const text = error?.message ? String(error.message) : String(error);
  const code = typeof error?.code === 'string' && error.code !== '' ? error.code : 'transport_error';
  const detail = text.includes(url) ? text : `${text} (${url})`;
  return new FetchToolError(code, detail, error);
}
