// Sign-in behavior through the real pi-ai flows, driven by the Android interaction adapter and the
// Keystore-vault credential store. The fake bridge stands in for Kotlin: it seals payloads in a
// map (as KeystoreCredentialStore.kt does in the encrypted vault file) and answers sign-in prompts
// the way SignInSheet does.

import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), { status, headers: { 'content-type': 'application/json' } });
}

function nativeWithVault({ onPrompt } = {}) {
  const db = new DatabaseSync(':memory:');
  const events = [], requests = [], prompts = [];
  const vault = new Map();
  const call = async (op, args = {}) => {
    requests.push({ op, args });
    const params = (args.params ?? []).map(p => p?.base64 ? Buffer.from(p.base64, 'base64') : p);
    if (op === 'credential.list') return [...vault].map(([provider, payload]) => ({ provider, payload }));
    if (op === 'credential.put') { vault.set(args.provider, args.payload); return null; }
    if (op === 'credential.delete') { vault.delete(args.provider); return null; }
    if (op === 'auth.dismissPrompt') return null;
    if (op === 'auth.prompt') {
      prompts.push(args);
      if (!onPrompt) throw new Error(`Unexpected sign-in prompt: ${args.type}`);
      return onPrompt(args, prompts.length);
    }
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
  return { db, events, requests, prompts, vault, // Mirrors runtime/src/bridge.mjs: the envelope key is `event`, built from the emit type.
  bridge: { call, emit: ({ type, ...fields }) => events.push({ event: type, ...fields }), subscribe: () => () => {} } };
}

const eventsOf = (native, type) => native.events.filter(event => event.event === type);

test('api-key sign-in runs the upstream secret prompt and seals the credential in the vault', async () => {
  const native = nativeWithVault({ onPrompt: args => { assert.equal(args.type, 'secret'); return 'sk-from-the-user'; } });
  const { createPiNative } = await import('../src/host.mjs');
  const host = createPiNative(native.bridge);
  await host.init();
  try {
    const result = await host.login({ provider: 'deepseek', type: 'api_key' });
    assert.equal(result.authType, 'api_key');
    assert.equal(result.credential.type, 'api_key');
    assert.equal(result.credential.keyLength, 'sk-from-the-user'.length);
    // The prompt came from the provider's own ApiKeyAuth.login, not from app code.
    assert.equal(native.prompts.length, 1);
    assert.match(native.prompts[0].message, /Enter .*API key/);
    assert.equal(eventsOf(native, 'auth.started').length, 1);
    assert.equal(eventsOf(native, 'auth.settled').length, 1);
    assert.equal(eventsOf(native, 'auth.finished').length, 1);
    // The secret is sealed in the vault and never appears in an event or a transcript row.
    assert.equal(JSON.parse(native.vault.get('deepseek')).key, 'sk-from-the-user');
    assert.equal(JSON.stringify(native.events).includes('sk-from-the-user'), false);
    assert.equal(JSON.stringify(await host.snapshot()).includes('sk-from-the-user'), false);
    const state = await host.authState();
    assert.equal(state.providers.find(row => row.provider === 'deepseek').stored.type, 'api_key');
  } finally { await host.close(); native.db.close(); }
});

test('xAI device-code sign-in surfaces the code and stores the token through the vault', async () => {
  const native = nativeWithVault();
  const { createPiNative } = await import('../src/host.mjs');
  const host = createPiNative(native.bridge);
  await host.init();
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    const target = String(url);
    calls.push({ target, body: String(options?.body ?? '') });
    if (target.includes('/oauth2/device/code')) return json({ device_code: 'device-1', user_code: 'ABCD-EFGH', verification_uri: 'https://x.ai/device', expires_in: 600, interval: 1 });
    if (target.includes('/oauth2/token')) return json({ access_token: 'access-token-1', refresh_token: 'refresh-token-1', expires_in: 3600 });
    throw new Error(`Unexpected request: ${target}`);
  };
  try {
    const result = await host.login({ provider: 'xai', type: 'oauth' });
    assert.equal(result.authType, 'oauth');
    assert.equal(result.credential.type, 'oauth');
    assert.equal(result.credential.refreshable, true);
    assert.equal(calls.length, 2);
    assert.match(calls[0].body, /client_id=/);
    assert.match(calls[1].body, /grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code/);
    // `auth.event` would have overwritten the bridge envelope's event name; the payload rides under
    // `notice` and the envelope keeps its own key.
    const deviceEvent = native.events.find(event => event.event === 'auth.notice' && event.notice?.type === 'device_code');
    assert.equal(deviceEvent.event, 'auth.notice');
    assert.equal(deviceEvent.notice.userCode, 'ABCD-EFGH');
    assert.equal(deviceEvent.notice.verificationUri, 'https://x.ai/device');
    assert.equal(deviceEvent.notice.expiresInSeconds, 600);
    const sealed = JSON.parse(native.vault.get('xai'));
    assert.equal(sealed.access, 'access-token-1');
    assert.equal(sealed.refresh, 'refresh-token-1');
    assert.equal(JSON.stringify(native.events).includes('access-token-1'), false);
    assert.equal(eventsOf(native, 'auth.settled')[0].value.credential.refreshable, true);
  } finally { globalThis.fetch = originalFetch; await host.close(); native.db.close(); }
});

test('cancelling a device-code sign-in aborts the upstream flow and stores nothing', async () => {
  const native = nativeWithVault();
  const { createPiNative } = await import('../src/host.mjs');
  const host = createPiNative(native.bridge);
  await host.init();
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async url => {
    const target = String(url);
    if (target.includes('/oauth2/device/code')) return json({ device_code: 'device-2', user_code: 'ZZZZ', verification_uri: 'https://x.ai/device', expires_in: 600, interval: 5 });
    if (target.includes('/oauth2/token')) return json({ error: 'authorization_pending' }, 400);
    throw new Error(`Unexpected request: ${target}`);
  };
  try {
    const pending = host.login({ provider: 'xai', type: 'oauth' });
    for (let i = 0; i < 200 && !native.events.some(event => event.event === 'auth.notice' && event.notice?.type === 'device_code'); i++) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(native.events.some(event => event.event === 'auth.notice' && event.notice?.type === 'device_code'), true);
    assert.deepEqual(host.cancelLogin(), { cancelled: true, provider: 'xai' });
    await assert.rejects(pending, /cancel/i);
    assert.equal(native.vault.has('xai'), false);
    assert.equal(eventsOf(native, 'auth.failed')[0].cancelled, true);
    assert.equal(eventsOf(native, 'auth.finished')[0].cancelled, true);
    assert.deepEqual(host.cancelLogin(), { cancelled: false });
  } finally { globalThis.fetch = originalFetch; await host.close(); native.db.close(); }
});

test('an expired OAuth token is refreshed through the upstream flow and re-sealed', async () => {
  const native = nativeWithVault();
  // Seeded the way a previous session leaves it: sealed in the vault, past its expiry.
  native.vault.set('xai', JSON.stringify({ type: 'oauth', access: 'old-access', refresh: 'refresh-old', expires: Date.now() - 1000 }));
  const { createPiNative } = await import('../src/host.mjs');
  const host = createPiNative(native.bridge);
  await host.init();
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ target: String(url), body: String(options?.body ?? '') });
    return json({ access_token: 'new-access', refresh_token: 'refresh-new', expires_in: 3600 });
  };
  try {
    const auth = await host.getAuth({ provider: 'xai' });
    assert.equal(auth.configured, true);
    assert.equal(auth.hasApiKey, true);
    assert.equal(calls.length, 1);
    assert.match(calls[0].body, /grant_type=refresh_token/);
    assert.equal(JSON.parse(native.vault.get('xai')).access, 'new-access');
    assert.equal(JSON.stringify(native.events).includes('new-access'), false);
  } finally { globalThis.fetch = originalFetch; await host.close(); native.db.close(); }
});

test('logout deletes the sealed credential and the next send reports the missing sign-in', async () => {
  const native = nativeWithVault();
  const { createPiNative } = await import('../src/host.mjs');
  const host = createPiNative(native.bridge);
  await host.init();
  try {
    await host.setKey({ provider: 'openai', apiKey: 'logout-me' });
    assert.equal(native.vault.has('openai'), true);
    const removal = await host.logout({ provider: 'openai' });
    assert.deepEqual(removal, { provider: 'openai', removed: true });
    assert.equal(native.vault.has('openai'), false);
    assert.equal((await host.authState()).providers.find(row => row.provider === 'openai').stored, null);
    await assert.rejects(host.send({ provider: 'openai', modelId: 'gpt-4.1', prompt: 'hi', requestId: 'after-logout' }), /needs an API key or subscription sign-in/);
  } finally { await host.close(); native.db.close(); }
});

test('credential store serializes concurrent writes for one provider', async () => {
  const { createCredentialStore } = await import('../src/credentials.mjs');
  const order = [];
  const store = createCredentialStore(async (op, args) => {
    if (op === 'credential.list') return [];
    if (op === 'credential.put') {
      order.push(`begin:${args.payload}`);
      await new Promise(resolve => setTimeout(resolve, 5));
      order.push(`end:${args.payload}`);
      return null;
    }
    if (op === 'credential.delete') { order.push(`delete:${args.provider}`); return null; }
    throw new Error(`Unexpected op ${op}`);
  });
  await store.load();
  await Promise.all([1, 2, 3, 4].map(index => store.modify('openai', async current => ({
    type: 'api_key',
    key: `k${index}`,
    env: { rounds: (current?.env?.rounds ?? 0) + 1 },
  }))));
  // No interleaved begin/end: each task observed the previous task's committed credential.
  for (let index = 0; index < order.length; index += 2) {
    assert.match(order[index], /^begin:/);
    assert.equal(order[index].slice(6), order[index + 1].slice(4));
  }
  assert.equal(order.length, 8);
  assert.equal((await store.read('openai')).env.rounds, 4);
  await store.delete('openai');
  assert.equal(order.at(-1), 'delete:openai');
  assert.equal(await store.read('openai'), undefined);
});

test('a credential-store write failure surfaces the vault reason and code, not just pi-ai\'s wrapper', async () => {
  const native = nativeWithVault({ onPrompt: args => { assert.equal(args.type, 'secret'); return 'sk-live-key'; } });
  // Exactly what the Kotlin bridge sends when the Keystore refuses the seal (NativeFailure).
  const reason = 'Cannot encrypt credential: Caller-provided IV not permitted (InvalidAlgorithmParameterException)';
  const base = native.bridge.call;
  native.bridge.call = async (op, args) => {
    if (op === 'credential.put') throw Object.assign(new Error(reason), { code: 'keystore' });
    return base(op, args);
  };
  const { createPiNative } = await import('../src/host.mjs');
  const host = createPiNative(native.bridge);
  await host.init();
  try {
    await assert.rejects(host.login({ provider: 'deepseek', type: 'api_key' }), /Credential store modify failed/);
    const failed = eventsOf(native, 'auth.failed')[0];
    assert.equal(failed.cancelled, false);
    // pi-ai's wrapper ("Credential store modify failed for …") overwrites the native `code`
    // ("keystore") with its own ("auth"), so the host must surface the cause explicitly or the
    // sheet can only show the generic wrapper.
    assert.equal(failed.reason, reason);
    assert.equal(failed.causeCode, 'keystore');
    assert.match(failed.message, /Caller-provided IV not permitted/);
    assert.equal(eventsOf(native, 'auth.finished').length, 1);
    // No secret material in any event.
    assert.equal(JSON.stringify(native.events).includes('sk-live-key'), false);
  } finally { await host.close(); native.db.close(); }
});

test('a raced prompt is dismissed and its awaited value rejects, and notices keep their own key', async () => {
  const { createInteraction } = await import('../src/interaction.mjs');
  const calls = [], events = [];
  let failPrompt, answerPrompt;
  const call = async (op, args) => {
    calls.push({ op, args });
    if (op === 'auth.dismissPrompt') {
      failPrompt(new Error('Sign-in step cancelled'));
      return null;
    }
    if (op === 'auth.prompt') return new Promise((resolve, reject) => { answerPrompt = resolve; failPrompt = reject; });
    throw new Error(`Unexpected op ${op}`);
  };
  const interaction = createInteraction(call, event => events.push(event), new AbortController().signal);
  const manual = new AbortController();
  const pending = interaction.prompt({ type: 'manual_code', message: 'paste the code', signal: manual.signal });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(events[0].type, 'auth.prompt');
  assert.equal(events[0].prompt.type, 'manual_code');
  assert.equal(events[0].prompt.message, 'paste the code');
  // The browser callback won, so the flow aborts the losing prompt.
  manual.abort();
  await assert.rejects(pending, /cancelled/);
  assert.equal(calls.some(call => call.op === 'auth.dismissPrompt' && call.args.requestId === events[0].requestId), true);
  assert.equal(events.at(-1).type, 'auth.prompt.done');

  interaction.notify({ type: 'auth_url', url: 'https://example.test/authorize', instructions: 'Complete login.' });
  const notice = events.at(-1);
  assert.equal(notice.type, 'auth.notice');
  assert.equal(notice.event, undefined);
  assert.equal(notice.notice.type, 'auth_url');
  assert.equal(notice.notice.url, 'https://example.test/authorize');

  // A select prompt keeps the provider's own option ids and labels.
  const selecting = interaction.prompt({ type: 'select', message: 'Select method:', options: [{ id: 'browser', label: 'Browser login (default)' }, { id: 'copy_code', label: 'Copy code login (headless)', description: 'headless' }] });
  await new Promise(resolve => setTimeout(resolve, 0));
  const select = events.filter(event => event.type === 'auth.prompt').at(-1);
  assert.deepEqual(select.prompt.options, [
    { id: 'browser', label: 'Browser login (default)' },
    { id: 'copy_code', label: 'Copy code login (headless)', description: 'headless' },
  ]);
  answerPrompt('browser');
  assert.equal(await selecting, 'browser');
});
