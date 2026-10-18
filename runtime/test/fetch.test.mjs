// Provider HTTP through the Kotlin transport.
//
// pi-ai performs provider HTTP with `options?.fetch ?? globalThis.fetch`. Inside the WebView that
// ambient fetch is the browser's own: the request leaves from the `https://pi.local` origin, which
// no provider grants via CORS, so every turn dies before a model call and the OAuth token exchange
// dies with it. These tests pin the replacement: a fetch-compatible function that performs the
// request in Kotlin and streams the body back over the bridge event channel, plus the injection
// that puts it on every provider call pi-durable makes.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createBridgeFetch, installFetchTransport, withBridgeFetch } from '../src/fetch.mjs';

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const b64 = value => Buffer.from(typeof value === 'string' ? encoder.encode(value) : value).toString('base64');

/** Explicit realm for the shim: no bare globals, so the module stays runtime-injectable. */
const createScope = fetch => ({
  fetch,
  Response,
  ReadableStream,
  Headers,
  AbortController,
  AbortSignal,
  DOMException,
  TextEncoder,
  TextDecoder,
  atob,
  btoa,
  URL,
  Blob,
});

const tick = async (turns = 1) => { for (let index = 0; index < turns; index += 1) await new Promise(resolve => setTimeout(resolve, 0)); };

/**
 * The AndroidNative contract for `http.*`: `call` accepts one request, the body arrives as
 * `http.head`/`http.chunk`/`http.end` events on the same channel `shell.output` uses.
 */
function createFakeBridge({ transport = true, maxRequestBytes = 8 * 1024 * 1024 } = {}) {
  const subscribers = new Set();
  const calls = [];
  const cancelled = [];
  const bridge = {
    async call(op, args = {}) {
      calls.push({ op, args });
      if (op === 'http.capabilities') {
        if (!transport) throw new Error('Unsupported native operation: http.capabilities');
        return { transport: true, maxBytes: 32 * 1024 * 1024, maxRequestBytes };
      }
      if (op === 'http.request') return { accepted: true };
      if (op === 'http.cancel') { cancelled.push(args.requestId); return null; }
      throw new Error(`Unexpected op ${op}`);
    },
    emit() {},
    subscribe(listener) { subscribers.add(listener); return () => subscribers.delete(listener); },
  };
  return {
    bridge,
    calls,
    cancelled,
    requests: () => calls.filter(call => call.op === 'http.request'),
    send(event) { for (const listener of [...subscribers]) listener(event); },
  };
}

test('performs the request in the host and streams an ordered Response body', async () => {
  const host = createFakeBridge();
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  const pending = fetchImpl('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { authorization: 'Bearer sk-ant-secret', 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'claude-fable-5', stream: true }),
  });
  await tick();
  const request = host.requests()[0].args;
  assert.equal(request.method, 'POST');
  assert.equal(request.url, 'https://api.anthropic.com/v1/messages');
  assert.deepEqual(request.headers, { authorization: 'Bearer sk-ant-secret', 'content-type': 'application/json' });
  assert.equal(decoder.decode(Buffer.from(request.body, 'base64')), '{"model":"claude-fable-5","stream":true}');
  assert.equal(Number.isInteger(request.connectTimeoutMs), true);
  assert.equal(Number.isInteger(request.readTimeoutMs), true);
  assert.equal(Number.isInteger(request.maxBytes) && request.maxBytes > 0, true);

  // Out of order on purpose: the body must be handed to pi-ai in sequence order whatever the
  // channel does, because an SSE frame split across two chunks is only parsable in order.
  host.send({ event: 'http.head', requestId: request.requestId, status: 200, statusText: 'OK', headers: { 'content-type': 'text/event-stream' } });
  host.send({ event: 'http.chunk', requestId: request.requestId, seq: 1, base64: b64('event: beta\n\n') });
  host.send({ event: 'http.chunk', requestId: request.requestId, seq: 0, base64: b64('event: alpha\n\n') });
  host.send({ event: 'http.end', requestId: request.requestId, seq: 2, truncated: false, cancelled: false });

  const response = await pending;
  assert.equal(response.status, 200);
  assert.equal(response.ok, true);
  assert.equal(response.headers.get('content-type'), 'text/event-stream');
  assert.equal(await response.text(), 'event: alpha\n\nevent: beta\n\n');
});

test('delivers body chunks before the stream ends so SSE stays incremental', async () => {
  const host = createFakeBridge();
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  const pending = fetchImpl('https://api.openai.com/v1/responses', { method: 'POST', body: '{}' });
  await tick();
  const { requestId } = host.requests()[0].args;
  host.send({ event: 'http.head', requestId, status: 200, headers: { 'content-type': 'text/event-stream' } });
  const response = await pending;
  const reader = response.body.getReader();

  host.send({ event: 'http.chunk', requestId, seq: 0, base64: b64('data: {"delta":"hi"}\n\n') });
  const first = await reader.read();
  assert.equal(decoder.decode(first.value), 'data: {"delta":"hi"}\n\n');

  host.send({ event: 'http.chunk', requestId, seq: 1, base64: b64('data: [DONE]\n\n') });
  assert.equal(decoder.decode((await reader.read()).value), 'data: [DONE]\n\n');

  host.send({ event: 'http.end', requestId, seq: 2, truncated: false, cancelled: false });
  assert.equal((await reader.read()).done, true);
});

test('surfaces a non-2xx provider body instead of throwing so pi-ai can report it', async () => {
  const host = createFakeBridge();
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  const pending = fetchImpl('https://api.openai.com/v1/responses', { method: 'POST', body: '{}' });
  await tick();
  const { requestId } = host.requests()[0].args;
  const body = '{"error":{"message":"You exceeded your current quota","code":"insufficient_quota"}}';
  host.send({ event: 'http.head', requestId, status: 429, statusText: 'Too Many Requests', headers: { 'content-type': 'application/json', 'retry-after': '12' } });
  host.send({ event: 'http.chunk', requestId, seq: 0, base64: b64(body) });
  host.send({ event: 'http.end', requestId, seq: 1, truncated: false, cancelled: false });
  const response = await pending;
  assert.equal(response.ok, false);
  assert.equal(response.status, 429);
  assert.equal(response.headers.get('retry-after'), '12');
  assert.equal(await response.text(), body);
});

test('aborting before the response headers rejects the fetch and cancels the host request', async () => {
  const host = createFakeBridge();
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  const controller = new AbortController();
  const pending = fetchImpl('https://api.openai.com/v1/responses', { method: 'POST', body: '{}', signal: controller.signal });
  await tick();
  const { requestId } = host.requests()[0].args;
  controller.abort();
  await assert.rejects(pending, error => error.name === 'AbortError');
  assert.deepEqual(host.cancelled, [requestId]);
});

test('aborting mid-stream breaks the body reader and cancels the host request', async () => {
  const host = createFakeBridge();
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  const controller = new AbortController();
  const pending = fetchImpl('https://api.openai.com/v1/responses', { method: 'POST', body: '{}', signal: controller.signal });
  await tick();
  const { requestId } = host.requests()[0].args;
  host.send({ event: 'http.head', requestId, status: 200, headers: { 'content-type': 'text/event-stream' } });
  const response = await pending;
  host.send({ event: 'http.chunk', requestId, seq: 0, base64: b64('data: {"delta":"hi"}\n\n') });
  const reader = response.body.getReader();
  await reader.read();
  controller.abort();
  await assert.rejects(reader.read(), error => error.name === 'AbortError');
  assert.deepEqual(host.cancelled, [requestId]);
});

test('a transport failure rejects with the host error text and never with request headers', async () => {
  const host = createFakeBridge();
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  const pending = fetchImpl('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { authorization: 'Bearer sk-ant-super-secret' },
    body: '{}',
  });
  await tick();
  const { requestId } = host.requests()[0].args;
  host.send({ event: 'http.error', requestId, code: 'ECONNREFUSED', message: 'Failed to connect to api.anthropic.com:443' });
  await assert.rejects(pending, error => {
    assert.equal(error.code, 'ECONNREFUSED');
    assert.match(error.message, /api\.anthropic\.com/);
    // The shim must never echo the outbound headers it was handed.
    assert.equal(error.message.includes('sk-ant-super-secret'), false);
    assert.equal(error.message.includes('authorization'), false);
    return true;
  });
});

test('a truncated body ends the stream as an error rather than a clean EOF', async () => {
  const host = createFakeBridge();
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  const pending = fetchImpl('https://api.openai.com/v1/responses', { method: 'POST', body: '{}' });
  await tick();
  const { requestId } = host.requests()[0].args;
  host.send({ event: 'http.head', requestId, status: 200, headers: { 'content-type': 'text/event-stream' } });
  const response = await pending;
  const reader = response.body.getReader();
  host.send({ event: 'http.chunk', requestId, seq: 0, base64: b64('partial') });
  assert.equal(decoder.decode((await reader.read()).value), 'partial');
  host.send({ event: 'http.end', requestId, seq: 1, truncated: true, cancelled: false });
  await assert.rejects(reader.read(), error => {
    assert.equal(error.code, 'truncated');
    assert.match(error.message, /limit/i);
    return true;
  });
});

test('falls back to the ambient fetch when the host has no transport, and respects a later one', async () => {
  const host = createFakeBridge({ transport: false });
  const original = async (input, init) => ({ marker: 'original', url: String(input), init });
  const scope = createScope(original);
  const installed = installFetchTransport(host.bridge, { scope });
  assert.equal(scope.fetch, installed);
  // No native transport (a plain browser/Node harness): the ambient fetch keeps working.
  assert.deepEqual((await scope.fetch('https://example.test/a', { method: 'POST' })).marker, 'original');
  // If something else takes over `fetch` afterwards, that transport wins over the captured one.
  const replacement = async () => ({ marker: 'replacement' });
  scope.fetch = replacement;
  assert.deepEqual((await installed('https://example.test/b')).marker, 'replacement');
});

test('probes the transport once and caches the answer', async () => {
  const host = createFakeBridge();
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  const first = fetchImpl('https://api.openai.com/v1/responses', { method: 'POST', body: '{}' });
  await tick();
  const requestId = host.requests()[0].args.requestId;
  host.send({ event: 'http.head', requestId, status: 204, statusText: 'No Content', headers: {} });
  host.send({ event: 'http.end', requestId, seq: 0, truncated: false, cancelled: false });
  const response = await first;
  assert.equal(response.body, null);
  const second = fetchImpl('https://api.openai.com/v1/responses', { method: 'POST', body: '{}' });
  await tick(2);
  host.send({ event: 'http.head', requestId: host.requests()[1].args.requestId, status: 200, headers: {} });
  host.send({ event: 'http.end', requestId: host.requests()[1].args.requestId, seq: 0, truncated: false, cancelled: false });
  await second;
  assert.equal(host.calls.filter(call => call.op === 'http.capabilities').length, 1);
});

test('wraps every provider call with the bridge fetch without disturbing the catalog', async () => {
  const seen = [];
  const returned = { kind: 'stream' };
  const models = {
    getModels: () => [{ id: 'gpt-4.1' }],
    getProviders: () => ['openai'],
    stream: (model, context, options) => { seen.push(['stream', options]); return returned; },
    streamSimple: (model, context, options) => { seen.push(['streamSimple', options]); return returned; },
    complete: async (model, context, options) => { seen.push(['complete', options]); return 'done'; },
    completeSimple: async (model, context, options) => { seen.push(['completeSimple', options]); return 'done'; },
    streamDeferred: (model, handle, options) => { seen.push(['streamDeferred', options]); return returned; },
    fetchDeferred: async (model, handle, options) => { seen.push(['fetchDeferred', options]); return 'deferred'; },
    cancelDeferred: async (model, handle, options) => { seen.push(['cancelDeferred', options]); },
    generateImages: async (model, context, options) => { seen.push(['generateImages', options]); return 'images'; },
    classify: async (model, context, options) => { seen.push(['classify', options]); return 'classification'; },
  };
  const bridgeFetch = () => {};
  const wrapped = withBridgeFetch(models, bridgeFetch);

  assert.equal(wrapped.getModels().length, 1);
  assert.equal(wrapped.streamSimple({}, {}, undefined), returned);
  await wrapped.completeSimple({}, {}, { maxRetries: 0 });
  assert.equal(await wrapped.fetchDeferred({}, {}, { signal: 'signal' }), 'deferred');
  await wrapped.cancelDeferred({}, {});
  await wrapped.generateImages({}, {}, undefined);
  await wrapped.classify({}, {}, { metadata: { a: 1 } });

  assert.deepEqual(seen.map(([name]) => name), ['streamSimple', 'completeSimple', 'fetchDeferred', 'cancelDeferred', 'generateImages', 'classify']);
  for (const [name, options] of seen) assert.equal(options.fetch, bridgeFetch, name);
  // Caller-set options survive; an explicitly chosen transport is not overridden.
  const explicit = () => {};
  wrapped.streamSimple({}, {}, { fetch: explicit, sessionId: 's' });
  assert.equal(seen.at(-1)[1].fetch, explicit);
  assert.equal(seen.at(-1)[1].sessionId, 's');
  // Stream options that were absent still arrive as an options object carrying the transport.
  wrapped.stream({}, {}, undefined);
  assert.equal(seen.at(-1)[1].fetch, bridgeFetch);
});

test('refuses a body the host could not carry, instead of dropping the message', async () => {
  // A bridge message over the host's own limit is dropped without a reply, which would leave the
  // fetch pending forever. A request body larger than the advertised limit must fail loudly here.
  const host = createFakeBridge({ maxRequestBytes: 1_024 });
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  await assert.rejects(fetchImpl('https://api.openai.com/v1/models', { method: 'POST', body: 'x'.repeat(4_096) }), error => {
    assert.equal(error.code, 'request_too_large');
    assert.match(error.message, /4096/);
    return true;
  });
  assert.equal(host.requests().length, 0);
});

test('a body that ends without a head still resolves as an empty response', async () => {
  const host = createFakeBridge();
  const fetchImpl = createBridgeFetch(host.bridge, { scope: createScope(undefined) });
  const pending = fetchImpl('https://api.openai.com/v1/models', { method: 'GET' });
  await tick();
  const { requestId } = host.requests()[0].args;
  host.send({ event: 'http.end', requestId, seq: 0, truncated: false, cancelled: false });
  const response = await pending;
  assert.equal(response.status, 200);
  assert.equal(await response.text(), '');
});

test('the installed ambient fetch carries the calls that take no fetch option', async () => {
  // The OAuth token exchange, dynamic model listing and the Google SDK resolve `globalThis.fetch`
  // themselves; installing the transport there is what covers them.
  const host = createFakeBridge();
  const scope = createScope(undefined);
  const installed = installFetchTransport(host.bridge, { scope });
  const pending = scope.fetch('https://oauth2.googleapis.com/token', { method: 'POST', body: 'grant_type=refresh_token' });
  await tick();
  assert.equal(host.requests().length, 1);
  assert.equal(host.requests()[0].args.url, 'https://oauth2.googleapis.com/token');
  host.send({ event: 'http.head', requestId: host.requests()[0].args.requestId, status: 200, headers: { 'content-type': 'application/json' } });
  host.send({ event: 'http.chunk', requestId: host.requests()[0].args.requestId, seq: 0, base64: b64('{"access_token":"fresh"}') });
  host.send({ event: 'http.end', requestId: host.requests()[0].args.requestId, seq: 1, truncated: false, cancelled: false });
  const response = await pending;
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { access_token: 'fresh' });
  assert.equal(scope.fetch, installed);
});

test('the shim reaches no Node API, so the browser bundle stays Node-free', async () => {
  const source = await readFile(new URL('../src/fetch.mjs', import.meta.url), 'utf8');
  for (const banned of ['node:', 'require(', 'process.', 'Buffer.']) {
    assert.equal(source.includes(banned), false, banned);
  }
});
