import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';

function fakeNative(db = new DatabaseSync(':memory:')) {
  const events = [], requests = [];
  // Stand-in for the Android Keystore vault (KeystoreCredentialStore.kt): sealed payloads keyed by
  // provider id, never a plaintext column in the conversation database.
  const vault = new Map();
  const call = async (op, args = {}) => {
    requests.push({ op, args });
    const params = (args.params ?? []).map(p => p?.base64 ? Buffer.from(p.base64, 'base64') : p);
    if (op === 'credential.list') return [...vault].map(([provider, payload]) => ({ provider, payload }));
    if (op === 'credential.put') { vault.set(args.provider, args.payload); return null; }
    if (op === 'credential.delete') { vault.delete(args.provider); return null; }
    if (op === 'sql.exec') return db.exec(args.sql);
    if (op === 'sql.begin') return db.exec('BEGIN IMMEDIATE');
    if (op === 'sql.commit') return db.exec('COMMIT');
    if (op === 'sql.rollback') return db.exec('ROLLBACK');
    if (op === 'sql.close') return;
    if (op === 'sql.run') { db.prepare(args.sql).run(...params); return null; }
    if (op === 'sql.get') return db.prepare(args.sql).get(...params) ?? null;
    if (op === 'sql.all') return db.prepare(args.sql).all(...params);
    if (op === 'shell.exec') return { stdout: args.command === 'printf native-shell-ok' ? 'native-shell-ok' : 'pi-native-probe', stderr: '', exitCode: 0 };
    if (op === 'shell.capabilities') return { maxOutputBytes: 256 * 1024, maxStreamBytes: 8 * 1024 * 1024, defaultTimeoutMs: 30_000, maxTimeoutMs: 120_000 };
    throw new Error(`Unexpected op ${op}`);
  };
  // Mirrors runtime/src/bridge.mjs: the envelope key is `event`, built from the emit type.
  return { db, events, requests, vault, bridge: { call, emit: ({ type, ...fields }) => events.push({ event: type, ...fields }), subscribe: () => () => {} } };
}

test('real Pi harness persists application probe across reopen without model generation', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  const ready = await host.init({ probeShell: true });
  assert.equal(ready.ready, true);
  assert.equal(ready.probe.kind, 'app.native.probe');
  assert.equal(ready.probe.model, undefined);
  assert.equal(ready.shell.stdout, 'native-shell-ok');
  assert.equal(native.events.find(e => e.event === 'ready').probe.id, ready.probe.id);
  assert.deepEqual(native.events.find(e => e.event === 'ready').value, ready);
  assert.equal((await host.history()).items.length, 1);
  const snapshot = await host.snapshot();
  assert.equal(snapshot.entries[0].id, ready.probe.id);
  assert.equal(native.events.some(e => e.event === 'snapshot' && e.value.entries[0].id === ready.probe.id), true);
  assert.equal(native.events.some(e => e.event === 'message' && Array.isArray(e.value?.rows)), true);
  assert.equal(native.events.some(e => e.event === 'message' && 'events' in e), false,
    'the raw durable events must not ride the frame: the projection is the whole payload');
  await host.close();
  const reopened = createPiNative(native.bridge);
  const second = await reopened.init();
  assert.equal(second.previousProbe.id, ready.probe.id);
  assert.equal((await reopened.history()).items.length, 2);
  await reopened.close(); native.db.close();
});

test('send rejects missing credentials, uses in-memory OpenAI auth and exposes provider failures', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative(); const host = createPiNative(native.bridge);
  await host.init();
  await assert.rejects(host.send({ modelId: 'gpt-4.1', prompt: 'hello', requestId: 'missing' }), /API key/);
  assert.equal((await host.history()).items.length, 1);
  const originalFetch = globalThis.fetch; let authorization;
  globalThis.fetch = async (input, options) => {
    authorization = new Headers(options?.headers ?? input.headers).get('authorization');
    throw new Error('TEST_TRANSPORT_OFFLINE');
  };
  try {
    const result = await host.send({ apiKey: 'test-key-not-a-production-credential', modelId: 'gpt-4.1', prompt: 'hello', requestId: 'request-1' });
    assert.equal(result.status, 'unanswered');
    assert.equal(authorization, 'Bearer test-key-not-a-production-credential');
    assert.equal(native.events.some(e => e.event === 'settled' && e.requestId === 'request-1'), true);
    const stored = native.requests.filter(request => JSON.stringify(request).includes('test-key-not-a-production-credential'));
    // The key reaches only the Keystore vault op; it is never a SQL parameter and never a row.
    assert.equal(stored.length > 0 && stored.every(request => request.op === 'credential.put'), true);
    assert.equal(native.requests.some(request => request.op.startsWith('sql.') && JSON.stringify(request).includes('test-key-not-a-production-credential')), false);
    assert.equal(native.vault.has('openai'), true);
    assert.equal(JSON.stringify(await host.snapshot()).includes('test-key-not-a-production-credential'), false);
    await host.abort();
  } finally { globalThis.fetch = originalFetch; await host.close(); native.db.close(); }
});

test('shell capability probe checks exit status and exact native output', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative(); const host = createPiNative(native.bridge);
  const result = await host.probe();
  assert.equal(result.shell.stdout, 'native-shell-ok');
  assert.deepEqual(native.requests.at(-1).args, { command: 'printf native-shell-ok', timeoutMs: 2000, requestId: 'capability-probe' });
  const broken = createPiNative({ call: async () => ({ exitCode: 1, stdout: '', stderr: 'denied' }), emit: () => {} });
  await assert.rejects(broken.probe(), /Shell capability probe failed/);
  native.db.close();
});

test('provider catalog is the built-in pi-ai set and a key is scoped to that provider', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  const ready = await host.init();
  const ids = ready.providers.map(provider => provider.id);
  for (const id of ['openai', 'anthropic', 'google', 'xai', 'openrouter']) {
    assert.equal(ids.includes(id), true, id);
    assert.equal(ready.providers.find(provider => provider.id === id).apiKey, true);
  }
  await assert.rejects(host.send({ provider: 'not-a-provider', apiKey: 'k', modelId: 'gpt-4.1', prompt: 'hello', requestId: 'bad-provider' }), /Unknown provider/);
  const originalFetch = globalThis.fetch;
  let seen;
  globalThis.fetch = async (input, options) => {
    seen = { url: String(input?.url ?? input), headers: new Headers(options?.headers ?? input.headers) };
    throw new Error('TEST_TRANSPORT_OFFLINE');
  };
  try {
    const result = await host.send({ provider: 'anthropic', apiKey: 'test-anthropic-key', modelId: 'claude-fable-5', prompt: 'hello', requestId: 'anthropic-1' });
    assert.equal(result.status, 'unanswered');
    assert.match(seen.url, /api\.anthropic\.com/);
    assert.equal(seen.headers.get('x-api-key'), 'test-anthropic-key');
    assert.equal(JSON.stringify(await host.snapshot()).includes('test-anthropic-key'), false);
    assert.equal((await host.providers()).find(provider => provider.id === 'anthropic').apiKey, true);
    assert.equal((await host.providers()).find(provider => provider.id === 'openai').oauth, true);
    // Subscription sign-in is no longer blanket-refused; the upstream flow runs and reports its own
    // precondition (ChatGPT sign-in needs this installation's stable device id).
    await assert.rejects(host.login({ provider: 'openai', type: 'oauth' }), /device ID/);
    assert.equal((await host.providers()).find(provider => provider.id === 'openai').oauthMechanismKnown, true);
    const listed = await host.models({ provider: 'openai' });
    assert.equal(listed.models.some(model => model.id === 'gpt-4.1'), true);
    // The Model sheet renders these rows directly, so the catalog metadata has to survive the
    // host op itself and not only the projection function.
    const chat = listed.models.find(model => model.id === 'gpt-4.1');
    assert.equal(chat.contextWindow > 0, true);
    assert.equal(chat.maxTokens > 0, true);
    assert.equal(Array.isArray(chat.input), true);
    assert.equal(typeof chat.cost.input, 'number');
    // GPT-4.1 declares no level map, so no level list is invented for it; a model that does
    // declare one carries it.
    assert.equal('thinkingLevels' in chat, false);
    assert.equal(listed.models.some(model => Array.isArray(model.thinkingLevels)), true);
    const images = await host.generateImages({ provider: 'openai', modelId: 'not-an-image-model', prompt: 'x' });
    assert.match(images.error, /image model/);
    const saved = await host.setKey({ provider: 'openai', apiKey: 'stored-openai-key' });
    assert.equal(saved.type, 'api_key');
    await host.close();
    const again = createPiNative(native.bridge);
    await again.init();
    const originalFetch = globalThis.fetch;
    let authorization;
    globalThis.fetch = async (input, options) => {
      authorization = new Headers(options?.headers ?? input.headers).get('authorization');
      throw new Error('TEST_TRANSPORT_OFFLINE');
    };
    try {
      const reused = await again.send({ prompt: 'again', requestId: 'stored-key', modelId: 'gpt-4.1' });
      assert.equal(reused.status, 'unanswered');
      assert.equal(authorization, 'Bearer stored-openai-key');
      await again.logout({ provider: 'openai' });
      await assert.rejects(again.send({ prompt: 'gone', requestId: 'after-logout', modelId: 'gpt-4.1' }), /needs an API key or subscription sign-in/);
    } finally { globalThis.fetch = originalFetch; await again.close(); }
  } finally { globalThis.fetch = originalFetch; await host.close(); native.db.close(); }
});

test('a message frame carries the incremental rows the UI renders, not a change count alone', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  await host.init();
  // The first frame is the snapshot: a full projection, so the UI is never behind before `ready`.
  const seed = native.events.find(e => e.event === 'message');
  assert.equal(seed.value.full, true);
  assert.deepEqual(seed.value.rows, []);
  assert.ok(Array.isArray(seed.value.removed));

  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error('TEST_TRANSPORT_OFFLINE'); };
  try {
    const result = await host.send({ apiKey: 'test-key-not-a-production-credential', modelId: 'gpt-4.1', prompt: 'hello there', requestId: 'rows-1' });
    assert.equal(result.status, 'unanswered');
  } finally { globalThis.fetch = originalFetch; }

  // The appended user entry rides the `message` event as a row the UI can upsert, so no follow-up
  // whole-transcript read is needed to render it.
  const frames = native.events.filter(e => e.event === 'message' && Array.isArray(e.value?.rows));
  const userRows = frames.flatMap(frame => frame.value.rows).filter(row => row.role === 'user');
  assert.ok(userRows.length >= 1, JSON.stringify(frames));
  assert.ok(userRows.every(row => typeof row.key === 'string' && row.text === 'hello there'), JSON.stringify(userRows));
  assert.equal(userRows.every(row => frameRemovedHasNoClash(frames, row.key)), true);
  // The raw durable events no longer ride the frame: the projection is the entire payload, so the
  // streaming text is not transmitted twice.
  assert.equal(frames.every(frame => !('events' in frame)), true, JSON.stringify(frames));
  await host.close(); native.db.close();
});

const frameRemovedHasNoClash = (frames, key) =>
  frames.every(frame => !(frame.value.removed ?? []).includes(key));

test('the host offers the fetch_url tool, so a send carries it to the model', async () => {
  // The tool only helps if the harness actually offers it. The request body the host builds is the
  // evidence: whatever tools reach the model reach it through here, and a tool that exists but is
  // never registered would be absent from this list.
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  await host.init();
  const originalFetch = globalThis.fetch;
  let body;
  globalThis.fetch = async (input, options) => {
    const raw = options?.body ?? input?.body;
    body = typeof raw === 'string' ? raw : new TextDecoder().decode(raw ?? new Uint8Array());
    throw new Error('TEST_TRANSPORT_OFFLINE');
  };
  try {
    await host.send({ apiKey: 'unit-test-key', modelId: 'gpt-4.1', prompt: 'hello', requestId: 'fetch-tool-1' });
  } finally { globalThis.fetch = originalFetch; await host.close(); native.db.close(); }
  assert.notEqual(body, undefined, 'the outbound request carried the offered tools');
  assert.ok(body.includes('fetch_url'), body.slice(0, 400));
  assert.ok(body.includes('saveToPath'), 'the workspace-save argument is offered too');
  assert.ok(body.includes('https://'), 'and the description names the schemes it accepts');
  assert.ok(body.includes('"shell"'), 'the native Android shell tool is offered');
  assert.equal(body.includes('"bash"'), false, 'the upstream bash tool is not offered');
  assert.ok(body.includes('js_search'), 'the autonomous JS registry search tool is offered');
  assert.ok(body.includes('js_run'), 'the isolated JS sandbox tool is offered');
});

test('initialization failure emits error and does not claim readiness', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const events = [];
  const host = createPiNative({ call: async () => { throw new Error('SQLITE_UNAVAILABLE'); }, emit: ({ type, ...fields }) => events.push({ event: type, ...fields }) });
  await assert.rejects(host.init(), /SQLITE_UNAVAILABLE/);
  assert.equal(events.some(e => e.event === 'ready'), false);
  assert.equal(events.find(e => e.event === 'error').message, 'SQLITE_UNAVAILABLE');
});

/**
 * `close()` is the runtime's own teardown: the Kotlin host evaluates it on the app's teardown path
 * (`NativeRuntimeHost.close()` → `RuntimeTeardown`, with a bounded drain window before the WebView is
 * destroyed). Nothing used to evaluate it at all, so none of this ran; these tests pin what the host
 * now gets when it does.
 */
test('close() really closes the runtime, and a second close is a no-op', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  await host.init();
  assert.equal((await host.history()).items.length, 1);

  await host.close();
  // Closed, not merely idle: the conversation the runtime was holding is released.
  await assert.rejects(host.history(), /not initialized/i);

  // The app closes a runtime from both teardown paths it has (a workspace change, then onDestroy),
  // and a rejected teardown would surface as an unhandled rejection inside the WebView.
  await host.close();
  await host.close();
  native.db.close();
});

test('close() before init is a no-op, and a runtime started after a close still works', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  // A WebView that never finished loading still has a host, and the app still closes it.
  const never = createPiNative(native.bridge);
  await never.close();
  await never.close();

  // The restart path: a new runtime over the same storage starts, reads and closes cleanly.
  const first = createPiNative(native.bridge);
  const ready = await first.init();
  await first.close();
  await assert.rejects(first.history(), /not initialized/i);

  const second = createPiNative(native.bridge);
  const reopened = await second.init();
  assert.equal(reopened.ready, true);
  assert.equal(reopened.previousProbe.id, ready.probe.id);
  assert.equal((await second.history()).items.length, 2);
  await second.close();
  native.db.close();
});

test('a failing teardown step is reported, not thrown, so the release still finishes', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  // The conversation database goes away under the runtime mid-teardown: the app's own teardown can
  // race the SQL worker, and `harness.close()` flushes through the bridge. A step that fails must not
  // reject `close()` — the host's drain window would be spent on an unhandled rejection inside the
  // WebView — and must not skip the steps after it.
  const original = native.bridge.call;
  let failing = false;
  native.bridge.call = async (op, args = {}) => {
    if (failing && String(op).startsWith('sql.')) throw new Error('SQLITE_GONE');
    return original(op, args);
  };
  const host = createPiNative(native.bridge);
  await host.init();
  failing = true;

  await host.close();
  const reported = native.events.filter(e => e.event === 'error');
  assert.equal(reported.length, 1, 'the failure is reported once, as a frame, never thrown');
  assert.equal(reported[0].operation, 'close');
  assert.match(reported[0].message, /SQLITE_GONE/);
  // And the teardown still finished: nothing is left holding the conversation.
  await assert.rejects(host.history(), /not initialized/i);
});

test('close() tolerates storage that has already gone away', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  await host.init();
  native.db.close();
  await host.close();
  await assert.rejects(host.history(), /not initialized/i);
});

export { fakeNative };
