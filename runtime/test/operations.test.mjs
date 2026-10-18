// Reachable non-chat operations: catalog capabilities, auth status, image generation and
// classification. Nothing here claims a feature works because metadata exists.

import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), { status, headers: { 'content-type': 'application/json' } });
}

function fakeNative() {
  const db = new DatabaseSync(':memory:');
  const events = [], requests = [];
  const vault = new Map();
  const call = async (op, args = {}) => {
    requests.push({ op, args });
    const params = (args.params ?? []).map(p => p?.base64 ? Buffer.from(p.base64, 'base64') : p);
    if (op === 'credential.list') return [...vault].map(([provider, payload]) => ({ provider, payload }));
    if (op === 'credential.put') { vault.set(args.provider, args.payload); return null; }
    if (op === 'credential.delete') { vault.delete(args.provider); return null; }
    if (op === 'auth.prompt') throw new Error('No sign-in prompt expected');
    if (op === 'sql.exec') return db.exec(args.sql);
    if (op === 'sql.begin') return db.exec('BEGIN IMMEDIATE');
    if (op === 'sql.commit') return db.exec('COMMIT');
    if (op === 'sql.rollback') return db.exec('ROLLBACK');
    if (op === 'sql.close') return;
    if (op === 'sql.run') { db.prepare(args.sql).run(...params); return null; }
    if (op === 'sql.get') return db.prepare(args.sql).get(...params) ?? null;
    if (op === 'sql.all') return db.prepare(args.sql).all(...params);
    if (op === 'shell.exec') return { stdout: 'native-shell-ok', stderr: '', exitCode: 0 };
    throw new Error(`Unexpected op ${op}`);
  };
  return { db, events, requests, vault, bridge: { call, emit: event => events.push(event), subscribe: () => () => {} } };
}

test('every advertised OAuth provider has a source-reviewed transport entry', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const { OAUTH_MECHANISMS } = await import('../src/providers.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  const catalog = await host.providers();
  assert.equal(catalog.length, 42);
  const withOAuth = catalog.filter(row => row.oauth);
  assert.equal(withOAuth.length, 9);
  for (const row of withOAuth) {
    // A provider whose flow has never been read must not be reported as portable.
    assert.equal(row.oauthMechanismKnown, true, row.id);
    assert.equal(OAUTH_MECHANISMS[row.id] !== undefined, true, row.id);
  }
  const byId = Object.fromEntries(catalog.map(row => [row.id, row]));
  assert.equal(byId['github-copilot'].oauthDeviceCode, true);
  assert.equal(byId['github-copilot'].oauthLoopback, false);
  assert.equal(byId['openrouter'].oauthLoopbackRequired, true);
  assert.equal(byId['openai-codex'].apiKey, false);
  assert.equal(byId['openai-codex'].oauthSubscription, true);
  assert.equal(byId.openrouter.counts.image > 0, true);
  assert.equal(byId.openai.counts.classifier > 0, true);
  // pi-ai 1.1.0 ships no `fetchDeferred` implementation: the matrix must say so.
  assert.deepEqual(catalog.filter(row => row.deferred).map(row => row.id), []);
  const capabilities = await host.capabilities();
  assert.equal(capabilities.some(row => row.operations.includes('deferred')), false);
  assert.equal(capabilities.find(row => row.id === 'openrouter').operations.includes('images'), true);
  assert.equal(capabilities.find(row => row.id === 'xai').signIn.includes('device code'), true);
  native.db.close();
});

test('auth status reports configured providers without resolving secrets', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  await host.init();
  try {
    const before = await host.authState();
    assert.equal(before.providers.length, 42);
    assert.deepEqual(before.providers.find(row => row.provider === 'openai'), { provider: 'openai', name: 'OpenAI', configured: false, source: null, type: null, stored: null });
    await host.setKey({ provider: 'openai', apiKey: 'status-key' });
    const after = await host.authState();
    const openai = after.providers.find(row => row.provider === 'openai');
    assert.equal(openai.configured, true);
    assert.equal(openai.type, 'api_key');
    assert.equal(openai.source, 'stored credential');
    assert.equal(openai.stored.keyLength, 'status-key'.length);
    assert.equal(JSON.stringify(after).includes('status-key'), false);
  } finally { await host.close(); native.db.close(); }
});

test('image generation reaches the provider and projects the result', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  await host.init();
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ target: String(url), body: JSON.parse(String(options?.body ?? '{}')) });
    return json({
      id: 'image-response-1',
      choices: [{ message: { content: 'Rendered.', images: [{ image_url: 'data:image/png;base64,QUJD' }] } }],
      usage: { prompt_tokens: 4, completion_tokens: 1 },
    });
  };
  try {
    // Discovery is real: only OpenRouter serves image models in this build.
    const available = await host.available({ provider: 'openrouter', type: 'image' });
    assert.equal(available.models.length, 0); // no credential yet
    await host.setKey({ provider: 'openrouter', apiKey: 'openrouter-key' });
    const listed = await host.models({ provider: 'openrouter', type: 'image' });
    assert.equal(listed.type, 'image');
    assert.equal(listed.models.some(model => model.id === 'black-forest-labs/flux-3-image'), true);
    const configured = await host.available({ provider: 'openrouter', type: 'image' });
    assert.equal(configured.models.length > 0, true);
    // The authenticated rows the picker offers carry their own metadata, not just ids.
    assert.equal(configured.models.every(model => typeof model.name === 'string' && model.name !== ''), true);
    assert.deepEqual(configured.models.find(model => model.id === 'black-forest-labs/flux-3-image').output, ['image']);

    const result = await host.generateImages({ provider: 'openrouter', modelId: 'black-forest-labs/flux-3-image', prompt: 'a pi symbol' });
    assert.equal(calls.length, 1);
    assert.match(calls[0].target, /openrouter\.ai\/api\/v1\/chat\/completions/);
    assert.equal(calls[0].body.model, 'black-forest-labs/flux-3-image');
    assert.equal(calls[0].body.messages[0].content[0].text, 'a pi symbol');
    // FLUX.3 Image declares `output: ["image"]` only, so upstream asks for image modalities alone.
    assert.deepEqual(calls[0].body.modalities, ['image']);
    assert.equal(result.stopReason, 'stop');
    assert.equal(result.error, null);
    assert.deepEqual(result.output, [
      { type: 'text', text: 'Rendered.' },
      { type: 'image', mimeType: 'image/png', base64: 'QUJD' },
    ]);
    assert.equal(native.events.some(event => event.type === 'images' && event.value.model === 'black-forest-labs/flux-3-image'), true);

    // An unknown id is a structured error naming the models that do exist, never a throw.
    const unknown = await host.generateImages({ provider: 'openrouter', modelId: 'not-a-model', prompt: 'x' });
    assert.match(unknown.error, /Unknown OpenRouter image model/);
    assert.equal(unknown.known.includes('black-forest-labs/flux-3-image'), true);
    // A provider with no image models says so instead of blaming the model id.
    const none = await host.generateImages({ provider: 'anthropic', modelId: 'claude-opus-4-7', prompt: 'x' });
    assert.match(none.error, /no image models/);
  } finally { globalThis.fetch = originalFetch; await host.close(); native.db.close(); }
});

test('classification reports unknown models and never rejects on a provider failure', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  await host.init();
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error('TEST_TRANSPORT_OFFLINE'); };
  try {
    await host.setKey({ provider: 'openai', apiKey: 'classifier-key' });
    const listed = await host.models({ provider: 'openai', type: 'classifier' });
    assert.deepEqual(listed.models.map(model => model.id), ['gpt-6-luna']);
    const available = await host.available({ provider: 'openai', type: 'classifier' });
    assert.equal(available.models.length > 0, true);
    const unknown = await host.classify({ provider: 'openai', modelId: 'nope', questions: {} });
    assert.match(unknown.error, /Unknown OpenAI classifier model/);
    assert.deepEqual(unknown.known, ['gpt-6-luna']);
    const failed = await host.classify({
      provider: 'openrouter',
      modelId: 'cloudflare/clef',
      state: { text: 'hello' },
      questions: { spam: { type: 'bool', instructions: 'Is it spam?', criteria: { true: 'spam', false: 'not spam' } } },
    });
    assert.equal(typeof failed, 'object');
    assert.equal(['stop', 'error', 'aborted'].includes(failed.stopReason), true);
    assert.equal(native.events.some(event => event.type === 'classify'), true);
  } finally { globalThis.fetch = originalFetch; await host.close(); native.db.close(); }
});

test('deferred operations are absent, not silently faked', async () => {
  const { createPiNative } = await import('../src/host.mjs');
  const native = fakeNative();
  const host = createPiNative(native.bridge);
  await host.init();
  try {
    await assert.rejects(host.fetchDeferred({ provider: 'openai', modelId: 'gpt-4.1', handle: {} }), /Deferred handle required/);
    // A structurally valid handle still fails in upstream because no provider implements it.
    const result = await host.fetchDeferred({
      provider: 'openai',
      modelId: 'gpt-4.1',
      handle: { provider: 'openai', modelId: 'gpt-4.1', api: 'openai-responses', id: 'resp-1' },
    });
    assert.equal(result.stopReason === 'error' || result.errorMessage !== null, true);
  } finally { await host.close(); native.db.close(); }
});
