// The shipped bundle, exercising provider traffic end to end against a fake AndroidNative host.
//
// `build.test.mjs` proves the bundle loads without Node globals and that a provider request reaches
// an *ambient* fetch. This proves what the app needs: with a host that implements `http.*`, the
// provider request leaves the runtime as an `http.request` bridge message carrying the provider URL
// and its auth header, and the WebView's own fetch — the CORS-refused call that killed every turn —
// is never touched. The host then streams the response back the way Kotlin does.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, rm } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { DatabaseSync } from 'node:sqlite';

const b64 = value => Buffer.from(value, 'utf8').toString('base64');

/**
 * A per-call scratch output path. This file builds the runtime to its OWN path rather than the
 * tracked `app/src/main/assets/native-runtime/runtime.js`: two test files rebuilding that one shared
 * file concurrently race on it (esbuild truncates and rewrites it in place, so a read can land
 * mid-write), and a test that rebuilds a file it then asserts about also leaves it dirty. The
 * freshness check in build.test.mjs owns the assertion that the tracked asset matches a fresh build.
 */
let scratchSequence = 0;
const scratchPath = () => path.join(tmpdir(), `pi-bundle-${process.pid}-${Date.now()}-${++scratchSequence}.js`);

/**
 * Load the built bundle in a browser-shaped realm whose AndroidNative implements the whole durable
 * contract plus the HTTP transport. [respond] receives each accepted `http.request` and drives the
 * head/chunk/end events the Kotlin host emits.
 */
async function runBundle(respond) {
  const scratch = scratchPath();
  try {
    execFileSync(process.execPath, ['build.mjs'], { cwd: new URL('..', import.meta.url), stdio: 'pipe', env: { ...process.env, PI_RUNTIME_OUT: scratch } });
    const code = await readFile(scratch, 'utf8');
  const messages = [];
  const scope = { AndroidNative: { postMessage: input => messages.push(JSON.parse(input)) } };
  const state = { browserFetchCalls: 0, httpRequests: [] };
  const globals = {
    window: scope, console, setTimeout, clearTimeout, queueMicrotask, AbortController, AbortSignal,
    TextEncoder, TextDecoder, URL, URLSearchParams, Headers, Request, Response, FormData, Blob, File,
    ReadableStream, btoa, atob, crypto: globalThis.crypto, performance,
    // The transport pi-ai would otherwise use: the WebView's own, from the https://pi.local origin.
    fetch: async () => { state.browserFetchCalls += 1; throw new Error('BROWSER_FETCH_MUST_NOT_CARRY_PROVIDER_TRAFFIC'); },
  };
  vm.runInNewContext(code, globals, { timeout: 10_000 });

  const db = new DatabaseSync(':memory:');
  const vault = new Map();
  scope.AndroidNative.postMessage = input => {
    const request = JSON.parse(input); messages.push(request);
    if (!request.op) return;
    queueMicrotask(() => {
      try {
        const { op, args, id } = request;
        const params = (args.params ?? []).map(p => (p?.base64 ? Buffer.from(p.base64, 'base64') : p));
        let value = null;
        if (op === 'credential.list') value = [...vault].map(([provider, payload]) => ({ provider, payload }));
        else if (op === 'credential.put') vault.set(args.provider, args.payload);
        else if (op === 'credential.delete') vault.delete(args.provider);
        else if (op === 'sql.exec') db.exec(args.sql);
        else if (op === 'sql.begin') db.exec('BEGIN IMMEDIATE');
        else if (op === 'sql.commit') db.exec('COMMIT');
        else if (op === 'sql.rollback') db.exec('ROLLBACK');
        else if (op === 'sql.run') db.prepare(args.sql).run(...params);
        else if (op === 'sql.get') value = db.prepare(args.sql).get(...params) ?? null;
        else if (op === 'sql.all') value = db.prepare(args.sql).all(...params);
        else if (op === 'http.capabilities') value = { transport: true, maxBytes: 1024 * 1024, maxRequestBytes: 1024 * 1024 };
        else if (op === 'http.request') { state.httpRequests.push(args); value = { accepted: true }; }
        else if (op === 'http.cancel') value = null;
        else if (op !== 'sql.close') throw new Error(op);
        scope.nativeReply(JSON.stringify({ id, ok: true, value }));
        // The host performs the exchange on its own thread, so the reply and the body events that
        // follow it can interleave; the shim routes them by request id.
        if (op === 'http.request') {
          for (const event of respond(args) ?? []) {
            queueMicrotask(() => scope.nativeEvent(JSON.stringify(event)));
          }
        }
      } catch (error) { scope.nativeReply({ id: request.id, ok: false, error: error.message }); }
    });
  };

  try {
    await scope.PiNative.init();
    const result = await scope.PiNative.send({ apiKey: 'bundle-transport-key', modelId: 'gpt-4.1', prompt: 'hello', requestId: 'bundle-request' });
    return { messages, state, result, vault };
  } finally { db.close(); }
  } finally { await rm(scratch, { force: true }); }
}

test('the shipped bundle routes provider HTTP through the Kotlin transport, never the WebView fetch', async () => {
  const { messages, state, result, vault } = await runBundle(args => ([
    { event: 'http.error', requestId: args.requestId, code: 'connect', message: 'Failed to connect to api.openai.com:443' },
  ]));

  assert.equal(state.browserFetchCalls, 0, 'the WebView fetch must never carry provider traffic');
  const request = state.httpRequests[0];
  assert.equal(Boolean(request), true, 'the provider request must reach the host');
  assert.equal(request.url, 'https://api.openai.com/v1/responses');
  assert.equal(request.method, 'POST');
  assert.equal(request.headers.authorization, 'Bearer bundle-transport-key');
  assert.equal(Buffer.from(request.body, 'base64').toString('utf8').includes('hello'), true);
  assert.equal(Number.isInteger(request.readTimeoutMs), true);
  assert.equal(messages.some(message => message.op === 'http.capabilities'), true, 'the shim probes the transport first');
  assert.equal(vault.get('openai').includes('bundle-transport-key'), true, 'the key still lives in the Keystore vault');

  // A transport failure ends the turn visibly instead of leaving the composer spinning.
  assert.equal(result.status, 'unanswered');
  assert.equal(result.reason, 'model_error');
  const settled = messages.find(message => message.event === 'settled');
  assert.equal(settled.value.reason, 'model_error');
  assert.equal(JSON.stringify(settled).includes('bundle-transport-key'), false);
});

test("the provider's own error text survives a non-2xx response", async () => {
  const body = '{"error":{"message":"You exceeded your current quota","code":"insufficient_quota"}}';
  const { state, result } = await runBundle(args => ([
    { event: 'http.head', requestId: args.requestId, status: 429, statusText: 'Too Many Requests', headers: { 'content-type': 'application/json', 'retry-after': '12' } },
    { event: 'http.chunk', requestId: args.requestId, seq: 0, base64: b64(body) },
    { event: 'http.end', requestId: args.requestId, seq: 1, truncated: false, cancelled: false },
  ]));

  assert.equal(state.browserFetchCalls, 0);
  assert.equal(state.httpRequests.length, 1);
  assert.equal(result.status, 'unanswered');
  assert.equal(result.reason, 'model_error');
  // pi-ai reads the body of a failed response itself, so the provider's own message is what the
  // user is shown — not a generic "connection failed" invented by the transport.
  assert.equal(result.detail.includes('insufficient_quota'), true, result.detail);
  assert.equal(result.detail.includes('You exceeded your current quota'), true, result.detail);
});
