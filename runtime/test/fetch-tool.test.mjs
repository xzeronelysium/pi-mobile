// The agent-facing `fetch_url` durable tool.
//
// WHY: the harness's agent could only reach the network through `/system/bin/curl`, a firmware
// binary (LG ships it; AOSP's base set does not), so a device without it left the agent unable to
// fetch anything at all. The app already owns a native streaming HTTP client in Kotlin, and
// `src/fetch.mjs` already exposes it as a fetch-compatible transport, so the tool takes that
// transport — never an ambient `fetch` — and the WebView's `https://pi.local` origin and CORS are
// never involved.
//
// These tests pin the whole contract: the schema, the honest truncation with the true byte count,
// streaming a binary body into the workspace through the bridge file ops, non-2xx passthrough of
// the provider's own error text, real-cause transport failures, abort, the workspace confinement of
// `saveToPath`, credential silence (no request header or body ever reaches a message), and — the
// part that makes it a *tool* — registration and visibility in the real harness.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fauxAssistantMessage, fauxToolCall } from '@earendil-works/pi-ai/providers/faux';
import { createRegistry } from '@earendil-works/pi-durable';
import { createBridgeFetch } from '../src/fetch.mjs';
import {
  DEFAULT_MAX_BYTES,
  FETCH_EXTENSION_NAME,
  FETCH_TOOL_NAME,
  HARD_MAX_BYTES,
  createFetchTool,
  createFetchTools,
} from '../src/fetch-tool.mjs';
import { withFauxHarness } from './support/durable.mjs';

const encoder = new TextEncoder();
const b64 = value => Buffer.from(typeof value === 'string' ? encoder.encode(value) : value).toString('base64');
const tick = async (turns = 2) => { for (let index = 0; index < turns; index += 1) await new Promise(resolve => setTimeout(resolve, 0)); };

/** Explicit realm for the transport: the shim reaches no bare global, so a test can hand it one. */
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
  setTimeout,
  clearTimeout,
});

/**
 * The AndroidNative contract for `http.*` plus the file ops `saveToPath` uses.
 *
 * The script decides what the host answers: `{ status, statusText, headers, body, hold }` resolves
 * a response, `{ transport: { code, message } }` fails the request, and `hold` answers the head and
 * then says nothing so a test can abort mid-stream.
 */
function createBridge({ script = {}, root } = {}) {
  const subscribers = new Set();
  const calls = [];
  const cancelled = [];
  const files = new Map();
  const plan = request => (typeof script === 'function' ? script(request) : script);

  const emit = event => { for (const listener of [...subscribers]) listener(event); };

  const reply = (requestId, spec) => {
    if (spec.transport !== undefined) {
      emit({ event: 'http.error', requestId, code: spec.transport.code, message: spec.transport.message });
      return;
    }
    emit({ event: 'http.head', requestId, status: spec.status ?? 200, statusText: spec.statusText ?? 'OK', headers: spec.headers ?? {} });
    if (spec.hold === true) return;
    const body = spec.body === undefined ? '' : spec.body;
    const bytes = typeof body === 'string' ? encoder.encode(body) : body;
    if (bytes.length > 0) emit({ event: 'http.chunk', requestId, seq: 0, base64: b64(bytes) });
    // A real host delivers the body and only then reports that its ceiling ended it, so the
    // truncation lands after the bytes it already sent were handed over.
    if (spec.transportTruncated === true) {
      setTimeout(() => emit({ event: 'http.end', requestId, seq: 1, truncated: true, cancelled: false }), 0);
      return;
    }
    emit({ event: 'http.end', requestId, seq: 1, truncated: false, cancelled: false });
  };

  const bridge = {
    async call(op, args = {}) {
      calls.push({ op, args });
      if (op === 'http.capabilities') return { transport: true, maxBytes: 32 * 1024 * 1024, maxRequestBytes: 8 * 1024 * 1024 };
      if (op === 'http.request') {
        const spec = plan(args);
        // Delivered on its own turn, like the real host: the tool is already awaiting the body.
        setTimeout(() => reply(args.requestId, spec), 0);
        return { accepted: true };
      }
      if (op === 'http.cancel') { cancelled.push(args.requestId); return null; }
      if (op === 'file.write') {
        if (typeof root === 'string' && root !== '/' && args.path !== root && !String(args.path).startsWith(`${root}/`)) {
          throw Object.assign(new Error('Path escapes workspace'), { code: 'permission_denied' });
        }
        const payload = args.base64 ? Buffer.from(args.base64, 'base64') : Buffer.from(args.text ?? '', 'utf8');
        const previous = args.append ? Buffer.from(files.get(args.path) ?? '', 'base64') : Buffer.alloc(0);
        files.set(args.path, Buffer.concat([previous, payload]).toString('base64'));
        return null;
      }
      throw new Error(`Unexpected op ${op}`);
    },
    emit() {},
    subscribe(listener) { subscribers.add(listener); return () => subscribers.delete(listener); },
  };
  return { bridge, calls, cancelled, files, requests: () => calls.filter(call => call.op === 'http.request') };
}

/** The whole tool over one fake host, with an ambient fetch that fails if the tool ever reaches it. */
function createTool(options = {}) {
  const host = createBridge(options);
  const transport = createBridgeFetch(host.bridge, { scope: createScope(() => { throw new Error('AMBIENT_FETCH_REACHED'); }) });
  const tool = createFetchTool({
    transport,
    call: host.bridge.call,
    workspaceRoot: () => (typeof options.root === 'string' ? options.root : '/'),
    ...(options.maxBytes === undefined ? {} : { maxBytes: options.maxBytes }),
  });
  return { host, tool };
}

const contextOf = signal => (signal === undefined ? {} : { abortSignal: signal });
const textOf = result => (result.content ?? []).filter(part => part.type === 'text').map(part => part.text).join('');

test('fetch_url is one durable extension holding one tool with a precise schema', () => {
  const extension = createFetchTools({ transport: () => {}, call: () => {} });
  assert.equal(extension.name, FETCH_EXTENSION_NAME);
  assert.equal(extension.tools.length, 1);
  const [tool] = extension.tools;
  assert.equal(tool.name, FETCH_TOOL_NAME);
  assert.equal(tool.parameters.type, 'object');
  assert.deepEqual(tool.parameters.required, ['url']);
  assert.equal(tool.parameters.additionalProperties, false);
  for (const name of ['url', 'method', 'headers', 'body', 'saveToPath', 'maxBytes', 'timeoutMs']) {
    assert.equal(name in tool.parameters.properties, true, name);
  }
  assert.match(tool.description, /http/i);
});

test('fetches through the bridge transport and returns status, response headers and text', async () => {
  const { host, tool } = createTool({ script: { status: 200, statusText: 'OK', headers: { 'content-type': 'application/json', 'content-length': '9' }, body: '{"a": 1}\n' } });
  const result = await tool.execute({ url: 'https://example.test/data.json' }, {}, contextOf());
  const request = host.requests()[0].args;
  assert.equal(request.method, 'GET');
  assert.equal(request.url, 'https://example.test/data.json');
  assert.equal(host.requests().length, 1, 'the request went through the host, not an ambient fetch');
  const text = textOf(result);
  assert.match(text, /HTTP 200 OK/);
  assert.match(text, /content-type: application\/json/);
  assert.ok(text.endsWith('{"a": 1}\n'), text);
  assert.equal(result.isError, undefined);
  assert.deepEqual(result.details, {
    url: 'https://example.test/data.json',
    method: 'GET',
    status: 200,
    statusText: 'OK',
    ok: true,
    contentType: 'application/json',
    binary: false,
    bytes: 9,
    shownBytes: 9,
    limit: DEFAULT_MAX_BYTES,
    truncated: false,
    truncatedBy: null,
    headers: { 'content-type': 'application/json', 'content-length': '9' },
  });
});

test('forwards method, headers and body to the host transport', async () => {
  const { host, tool } = createTool({ script: { headers: { 'content-type': 'text/plain' }, body: 'ok' } });
  await tool.execute({
    url: 'https://example.test/things',
    method: 'post',
    headers: { 'content-type': 'application/json' },
    body: '{"name":"x"}',
  }, {}, contextOf());
  const request = host.requests()[0].args;
  assert.equal(request.method, 'POST');
  assert.deepEqual(request.headers, { 'content-type': 'application/json' });
  assert.equal(Buffer.from(request.body, 'base64').toString('utf8'), '{"name":"x"}');
});

test('a non-2xx answer is a normal result carrying the provider\u2019s own error text', async () => {
  const body = '{"error":{"message":"You exceeded your current quota","code":"insufficient_quota"}}';
  const { tool } = createTool({ script: { status: 429, statusText: 'Too Many Requests', headers: { 'content-type': 'application/json', 'retry-after': '12' }, body } });
  const result = await tool.execute({ url: 'https://api.openai.com/v1/responses', method: 'POST', body: '{}' }, {}, contextOf());
  assert.equal(result.isError, undefined);
  assert.equal(result.details.status, 429);
  assert.equal(result.details.ok, false);
  assert.equal(result.details.headers['retry-after'], '12');
  const text = textOf(result);
  assert.match(text, /HTTP 429 Too Many Requests/);
  assert.ok(text.includes('insufficient_quota'), text);
});

test('a body past the bound is truncated with an explicit marker and the true byte count', async () => {
  const body = `${'x'.repeat(70_000)}TAIL-SENTINEL`;
  const { tool } = createTool({ maxBytes: 1_024, script: { headers: { 'content-type': 'text/plain' }, body } });
  const result = await tool.execute({ url: 'https://example.test/big.txt' }, {}, contextOf());
  const text = textOf(result);
  assert.equal(result.details.truncated, true);
  assert.equal(result.details.truncatedBy, 'bytes');
  assert.equal(result.details.limit, 1_024);
  assert.equal(result.details.bytes, body.length, 'the true byte count, not the shown one');
  assert.equal(result.details.shownBytes, 1_024);
  assert.ok(text.includes('truncated'), 'an explicit marker, never a silent short body');
  assert.ok(text.includes('1024'), text.slice(-300));
  assert.ok(text.includes(String(body.length)), text.slice(-300));
  assert.equal(text.includes('TAIL-SENTINEL'), false, 'the body really was cut at the bound');
  // Exactly the bound was retained, and nothing beyond it leaked into the text.
  const bodyText = text.slice(text.indexOf('\n\n') + 2);
  assert.equal(bodyText.startsWith('x'.repeat(1_024)), true);
  assert.equal(bodyText.includes('x'.repeat(1_025)), false);
  assert.equal(result.isError, undefined);
});

test('the requested bound is clamped to the hard ceiling', async () => {
  const { tool } = createTool({ maxBytes: 100 * 1024 * 1024, script: { headers: { 'content-type': 'text/plain' }, body: 'small' } });
  const result = await tool.execute({ url: 'https://example.test/a' }, {}, contextOf());
  assert.equal(result.details.limit, HARD_MAX_BYTES, 'a per-call bound can never raise the memory ceiling');
  const smaller = createTool({ maxBytes: 4_096, script: { headers: { 'content-type': 'text/plain' }, body: 'small' } });
  assert.equal((await smaller.tool.execute({ url: 'https://example.test/a' }, {}, contextOf())).details.limit, 4_096);
});

test('a bound that is not a positive integer is refused rather than silently reading nothing', async () => {
  const { tool } = createTool({ script: { body: 'x' } });
  for (const maxBytes of [0, -1, 1.5, 'many']) {
    await assert.rejects(tool.execute({ url: 'https://example.test/a', maxBytes }, {}, contextOf()), error => {
      assert.equal(error.code, 'invalid_bound', `${maxBytes}: ${error.message}`);
      return true;
    }, String(maxBytes));
  }
});

test('a binary body is saved into the workspace through the bridge file ops, with path and bytes', async () => {
  const bytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0xff, 0xfe, 0x01]);
  const { host, tool } = createTool({ root: '/work', script: { headers: { 'content-type': 'image/png', 'content-length': String(bytes.length) }, body: new Uint8Array(bytes) } });
  const result = await tool.execute({ url: 'https://example.test/pixel.png', saveToPath: 'art/pixel.png' }, {}, contextOf());
  const writes = host.calls.filter(call => call.op === 'file.write');
  assert.ok(writes.length >= 1);
  assert.equal(writes.every(call => call.args.path === '/work/art/pixel.png'), true);
  assert.equal(Buffer.from(host.files.get('/work/art/pixel.png'), 'base64').equals(bytes), true);
  assert.equal(result.details.savedPath, '/work/art/pixel.png');
  assert.equal(result.details.savedBytes, bytes.length);
  assert.equal(result.details.bytes, bytes.length);
  const text = textOf(result);
  assert.ok(text.includes('/work/art/pixel.png'), text);
  assert.ok(text.includes(String(bytes.length)), text);
  // The binary was streamed to disk, never decoded into the transcript as text.
  assert.equal(text.includes('\ufffd'), false);
  assert.equal(text.includes('\u0000'), false);
});

test('an absolute saveToPath inside the workspace is honoured', async () => {
  const { host, tool } = createTool({ root: '/work', script: { headers: { 'content-type': 'text/plain' }, body: 'hello' } });
  const result = await tool.execute({ url: 'https://example.test/a.txt', saveToPath: '/work/deep/a.txt' }, {}, contextOf());
  assert.equal(result.details.savedPath, '/work/deep/a.txt');
  assert.equal(Buffer.from(host.files.get('/work/deep/a.txt'), 'base64').toString('utf8'), 'hello');
});

test('a saveToPath outside the workspace is refused and nothing is written', async () => {
  const { host, tool } = createTool({ root: '/work', script: { body: 'secret' } });
  for (const path of ['../escape.txt', '../../etc/passwd', '/etc/passwd', '/work/../escape.txt']) {
    await assert.rejects(tool.execute({ url: 'https://example.test/a', saveToPath: path }, {}, contextOf()), error => {
      assert.equal(error.code, 'workspace_escape', `${path}: ${error.message}`);
      return true;
    }, path);
  }
  assert.equal(host.calls.some(call => call.op === 'file.write'), false);
});

test('a binary body without saveToPath is reported as binary instead of dumped as text', async () => {
  const bytes = new Uint8Array([0x00, 0x01, 0xff, 0xfe]);
  const { tool } = createTool({ script: { headers: { 'content-type': 'application/octet-stream' }, body: bytes } });
  const result = await tool.execute({ url: 'https://example.test/blob.bin' }, {}, contextOf());
  assert.equal(result.details.binary, true);
  assert.equal(result.details.bytes, 4);
  const text = textOf(result);
  assert.match(text, /binary/i);
  assert.ok(text.includes('saveToPath'), text);
  assert.equal(text.includes('\ufffd'), false);
});

test('an invalid url or a non-http scheme is refused before any request is made', async () => {
  const { host, tool } = createTool({ script: { body: 'x' } });
  const bad = ['ftp://example.test/a', 'file:///etc/passwd', 'data:text/plain,hi', 'javascript:alert(1)', 'example.test/a', '/etc/passwd', '', 'http://'];
  for (const url of bad) {
    await assert.rejects(tool.execute({ url }, {}, contextOf()), error => {
      assert.equal(error.code, 'invalid_url', `${url}: ${error.message}`);
      return true;
    }, url);
  }
  await assert.rejects(tool.execute({}, {}, contextOf()), error => {
    assert.equal(error.code, 'invalid_url');
    return true;
  });
  await assert.rejects(tool.execute({ url: 'https://example.test/a', method: 'GET POST' }, {}, contextOf()), error => {
    assert.equal(error.code, 'invalid_method');
    return true;
  });
  assert.equal(host.requests().length, 0);
});

test('an aborted request cancels the host request and rejects with an AbortError', async () => {
  const { host, tool } = createTool({ script: { status: 200, headers: { 'content-type': 'text/event-stream' }, hold: true } });
  const controller = new AbortController();
  const pending = tool.execute({ url: 'https://example.test/stream' }, {}, contextOf(controller.signal));
  await tick(3);
  assert.equal(host.requests().length, 1);
  const { requestId } = host.requests()[0].args;
  controller.abort();
  await assert.rejects(pending, error => {
    assert.equal(error.name, 'AbortError');
    return true;
  });
  await tick(2);
  assert.deepEqual(host.cancelled, [requestId]);
});

test('a request already aborted before it starts never reaches the host', async () => {
  const { host, tool } = createTool({ script: { body: 'x' } });
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(tool.execute({ url: 'https://example.test/a' }, {}, contextOf(controller.signal)), error => {
    assert.equal(error.name, 'AbortError');
    return true;
  });
  assert.equal(host.requests().length, 0);
});

test('a transport failure surfaces the real cause and never the request headers or body', async () => {
  const secret = 'sk-live-DO-NOT-LEAK-0123456789';
  const { tool } = createTool({ script: { transport: { code: 'ECONNREFUSED', message: 'Failed to connect to api.example.test:443' } } });
  await assert.rejects(tool.execute({
    url: 'https://api.example.test/v1/things',
    method: 'POST',
    headers: { authorization: `Bearer ${secret}`, 'x-api-key': secret },
    body: `{"token":"${secret}"}`,
  }, {}, contextOf()), error => {
    assert.match(error.message, /api\.example\.test/);
    assert.equal(error.code, 'ECONNREFUSED');
    assert.equal(error.message.includes(secret), false, error.message);
    assert.equal(error.message.includes('authorization'), false, error.message);
    assert.equal(error.message.includes('x-api-key'), false, error.message);
    assert.equal(JSON.stringify({ ...error, cause: String(error.cause) }).includes(secret), false);
    return true;
  });
});

test('the request headers and body never reach the result the model sees', async () => {
  const secret = 'sk-live-DO-NOT-LEAK-0123456789';
  const { tool } = createTool({ script: { headers: { 'content-type': 'text/plain' }, body: 'fine' } });
  const result = await tool.execute({
    url: 'https://example.test/a',
    method: 'POST',
    headers: { authorization: `Bearer ${secret}` },
    body: `{"token":"${secret}"}`,
  }, {}, contextOf());
  const serialized = JSON.stringify(result);
  assert.equal(serialized.includes(secret), false, serialized);
  assert.equal(serialized.includes('authorization'), false, serialized);
});

test('a body the host itself truncated is reported, never silently short', async () => {
  const { tool } = createTool({ maxBytes: 4, script: { headers: { 'content-type': 'text/plain' }, body: 'abcdefgh', transportTruncated: true } });
  const result = await tool.execute({ url: 'https://example.test/big' }, {}, contextOf());
  const text = textOf(result);
  assert.equal(result.details.truncated, true);
  assert.equal(result.details.truncatedBy, 'transport');
  assert.ok(text.includes('truncated'), text);
  assert.ok(text.includes('8'), text.slice(-300));
});

test('a binary or saved body the host truncated is marked incomplete too', async () => {
  const bytes = new Uint8Array(2_048).fill(7);
  const binary = createTool({ script: { headers: { 'content-type': 'image/png' }, body: bytes, transportTruncated: true } });
  const binaryText = textOf(await binary.tool.execute({ url: 'https://example.test/a.png' }, {}, contextOf()));
  assert.ok(binaryText.includes('truncated'), binaryText);
  assert.ok(binaryText.includes('incomplete'), binaryText);

  const saved = createTool({ root: '/work', script: { headers: { 'content-type': 'application/octet-stream' }, body: bytes, transportTruncated: true } });
  const savedResult = await saved.tool.execute({ url: 'https://example.test/a.bin', saveToPath: 'a.bin' }, {}, contextOf());
  assert.equal(savedResult.details.truncated, true);
  assert.equal(savedResult.details.truncatedBy, 'transport');
  assert.ok(textOf(savedResult).includes('incomplete'), textOf(savedResult));
});

test('saveToPath streams the whole body, so the retained-bytes bound never cuts the file', async () => {
  // The bound exists to keep the transcript-sized copy in memory bounded; a file on disk is not that
  // copy, so a body far larger than the bound is still stored whole and reported whole.
  const body = 'q'.repeat(8_000);
  const { host, tool } = createTool({ root: '/work', maxBytes: 1_024, script: { headers: { 'content-type': 'text/plain' }, body } });
  const result = await tool.execute({ url: 'https://example.test/big.txt', saveToPath: 'big.txt' }, {}, contextOf());
  assert.equal(result.details.truncated, false);
  assert.equal(result.details.bytes, body.length);
  assert.equal(result.details.savedBytes, body.length);
  assert.equal(Buffer.from(host.files.get('/work/big.txt'), 'base64').toString('utf8'), body);
});

test('a 204 with no body is an empty answer', async () => {
  const { host, tool } = createTool({ script: { status: 204, statusText: 'No Content', headers: {}, body: '' } });
  const result = await tool.execute({ url: 'https://example.test/empty' }, {}, contextOf());
  assert.equal(result.details.status, 204);
  assert.equal(result.details.bytes, 0);
  assert.match(textOf(result), /HTTP 204 No Content/);
  assert.equal(host.requests().length, 1);
});

test('the tool performs the request through the injected transport, never an ambient fetch', async () => {
  const calls = [];
  const transport = async (input, init) => {
    calls.push({ url: String(input), init });
    return new Response('via transport', { status: 200, headers: { 'content-type': 'text/plain' } });
  };
  const original = globalThis.fetch;
  let ambient = 0;
  globalThis.fetch = async () => { ambient += 1; return new Response('ambient'); };
  try {
    const tool = createFetchTool({ transport, call: async () => null, workspaceRoot: () => '/' });
    const result = await tool.execute({ url: 'https://example.test/a', headers: { accept: 'text/plain' } }, {}, contextOf());
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, 'https://example.test/a');
    assert.deepEqual(await (await transport('https://example.test/a')).text(), 'via transport');
    assert.equal(ambient, 0);
    assert.match(textOf(result), /via transport/);
  } finally { globalThis.fetch = original; }
});

test('fetch_url is registered in the registry and visible to the harness, and a call really runs', async () => {
  const host = createBridge({ script: { headers: { 'content-type': 'text/plain' }, body: 'REMOTE-BODY-CONTENT' }, root: '/work' });
  const transport = createBridgeFetch(host.bridge, { scope: createScope(() => { throw new Error('AMBIENT_FETCH_REACHED'); }) });
  const registry = createRegistry();
  registry.install(createFetchTools({ transport, call: host.bridge.call, workspaceRoot: () => '/work' }));

  await withFauxHarness(async ({ context, faux, root }) => {
    // Visibility: the tool is offered to the request, which is what the model chooses from.
    const agent = await root.agent(context);
    assert.equal(agent.tools.some(tool => tool.name === FETCH_TOOL_NAME), true,
      `offered tools: ${agent.tools.map(tool => tool.name).join(', ')}`);
    assert.equal(agent.extensions.some(extension => extension.name === FETCH_EXTENSION_NAME), true);

    faux.setResponses([
      fauxAssistantMessage([fauxToolCall(FETCH_TOOL_NAME, { url: 'https://example.test/notes.txt' })], { stopReason: 'toolUse' }),
      fauxAssistantMessage('fetched'),
    ]);
    const settled = await (await root.submit({ type: 'input', content: 'fetch notes.txt', requestId: 'f1' }, context)).wait(context);
    assert.equal(settled.status, 'done');

    const page = await root.entries({ order: 'ascending' }, 200, undefined, context);
    const result = page.items.find(item => item.kind === 'pi.tool-result');
    assert.notEqual(result, undefined, JSON.stringify(page.items.map(item => item.kind)));
    assert.ok(JSON.stringify(result).includes('REMOTE-BODY-CONTENT'), 'the fetched body reached the transcript');
    assert.equal(host.requests().length, 1);
    assert.equal(host.requests()[0].args.url, 'https://example.test/notes.txt');
  }, { registry });
});

test('the tool module reaches no Node API, so the WebView bundle stays Node-free', async () => {
  const source = await readFile(new URL('../src/fetch-tool.mjs', import.meta.url), 'utf8');
  for (const banned of ['node:', 'require(', 'process.', 'Buffer.']) {
    assert.equal(source.includes(banned), false, banned);
  }
});
