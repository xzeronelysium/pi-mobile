// Where the next launch starts: the conversation the app was last reading.
//
// The durable database owns conversations, never "what this app had open", so the choice travels
// from the app as `init({conversationId})`. It is honoured only while that conversation still
// exists — a stale or unknown id falls back to the conversation the host would have opened anyway
// rather than failing the launch or resurrecting something that is gone.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createNativeHost } from './support/host-native.mjs';

async function openHost(native, options = {}) {
  const { createPiNative } = await import('../src/host.mjs');
  const host = createPiNative(native.bridge);
  const ready = await host.init({ probeShell: false, ...options });
  return { host, ready };
}

const readyOf = native => native.events.find(event => event.event === 'ready').value;

test('a launch resumes the conversation it was told the reader was in', async () => {
  const native = createNativeHost();
  const { host } = await openHost(native);
  const root = readyOf(native).conversationId;
  // The reader moves to another conversation and stays there.
  const created = await host.newSession();
  await host.writeEntry({ kind: 'app.note', data: { note: 'a real entry' } });
  await host.close();

  const mark = native.events.length;
  const { host: reopened, ready } = await openHost(native, { conversationId: created.conversationId });
  try {
    assert.equal(ready.conversationId, created.conversationId, 'the launch is on the recorded conversation');
    assert.notEqual(ready.conversationId, root);
    // Its transcript is seeded, not the root's: the seed frame is the whole conversation, and the
    // event stream is attached to the conversation that was resumed.
    const seed = native.events.slice(mark).find(event => event.event === 'message');
    assert.equal(seed.value.snapshot, true);
    assert.equal((await reopened.sessions()).current, String(created.conversationId));
    assert.equal((await reopened.history()).items.some(entry => entry.kind === 'app.note'), true);
  } finally { await reopened.close(); native.db.close(); }
});

test('a recorded id that names no conversation falls back instead of failing the launch', async () => {
  const native = createNativeHost();
  const { host, ready } = await openHost(native, { conversationId: 9999 });
  try {
    assert.equal(ready.ready, true);
    assert.equal(ready.conversationId, readyOf(native).conversationId);
    assert.equal((await host.sessions()).rows.length, 1, 'nothing was created to satisfy the stale id');
  } finally { await host.close(); native.db.close(); }
});

test('a recorded id that is not a conversation id falls back', async () => {
  const native = createNativeHost();
  const { host, ready } = await openHost(native, { conversationId: 'not-a-number' });
  try {
    assert.equal(ready.ready, true);
    assert.equal((await host.history()).items.length > 0, true, 'the root conversation is the honest default');
  } finally { await host.close(); native.db.close(); }
});

test('a conversation the reader moved away from cannot put itself back', async () => {
  const native = createNativeHost();
  const { host } = await openHost(native);
  const root = readyOf(native).conversationId;
  const created = await host.newSession();
  // The reader goes back to the root; that switch, not the resume, is the newest choice.
  await host.openSession({ conversationId: root });
  await host.close();

  const { host: reopened, ready } = await openHost(native, { conversationId: created.conversationId });
  try {
    assert.equal(ready.conversationId, created.conversationId, 'an id that still exists is still honoured');
    // And the host never drags the reader back to it later: only an explicit switch moves it, and
    // the current conversation is the one the drawer marks.
    const back = await reopened.openSession({ conversationId: root });
    assert.equal(back.conversationId, root);
    assert.equal((await reopened.sessions()).current, String(root));
  } finally { await reopened.close(); native.db.close(); }
});
