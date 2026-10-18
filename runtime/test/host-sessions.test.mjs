// The session/usage/queue/export layer as reached from the app: through `window.PiNative`, on the
// real sqlite-backed durable host. These are the methods Kotlin's DurableRuntimeScripts evaluates,
// and their events carry the payloads the Kotlin reducers read.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createNativeHost } from './support/host-native.mjs';
import { compactionOutcome } from '../src/sessions.mjs';

async function openHost(options = {}) {
  const { createPiNative } = await import('../src/host.mjs');
  const native = createNativeHost();
  const host = createPiNative(native.bridge);
  const ready = await host.init(options);
  return { native, host, ready };
}

test('the host exposes the session, usage, queue and export operations', async () => {
  const { native, host, ready } = await openHost();
  try {
    for (const name of ['sessions', 'tree', 'openSession', 'newSession', 'forkSession', 'resetSession', 'usage', 'queue', 'steer', 'followUp', 'withdraw', 'writeEntry', 'exportSession']) {
      assert.equal(typeof host[name], 'function', name);
    }
    const listed = await host.sessions();
    assert.equal(listed.current, String(ready.conversationId));
    assert.equal(listed.rows.length, 1);
    assert.equal(listed.rows[0].current, true);
    const frame = native.events.find(event => event.event === 'conversations');
    assert.equal(frame.value.rows.length, 1);

    const tree = await host.tree();
    assert.deepEqual(tree.nodes, [{ id: String(ready.conversationId), parentId: null, entryId: null, title: 'Conversation ' + ready.conversationId, current: true }]);
    assert.equal(native.events.some(event => event.event === 'tree'), true);
  } finally { await host.close(); native.db.close(); }
});

test('newSession creates a conversation, switches to it and openSession switches back', async () => {
  const { native, host, ready } = await openHost();
  try {
    const rootId = ready.conversationId;
    const created = await host.newSession();
    assert.equal(created.conversationId === rootId, false);
    assert.equal(native.events.some(event => event.event === 'session' && event.conversationId === created.conversationId), true);
    // The drawer is refreshed by the create, not merely by the switch.
    const refreshed = native.events.filter(event => event.event === 'conversations').at(-1);
    assert.equal(refreshed.value.rows.length, 2);
    assert.equal(refreshed.value.current, String(created.conversationId));

    const listed = await host.sessions();
    assert.equal(listed.rows.length, 2);
    assert.equal(listed.rows.find(row => row.id === String(rootId)).current, false);

    const reopened = await host.openSession({ conversationId: rootId });
    assert.equal(reopened.conversationId, rootId);
    // Switching emits a fresh history frame the Kotlin transcript already parses.
    const history = native.events.filter(event => event.event === 'history').at(-1);
    assert.deepEqual(Object.keys(history.value).sort(), ['items', 'rows']);

    await assert.rejects(host.openSession({}), /conversationId required/);
    await assert.rejects(host.openSession({ conversationId: 9999 }), /Unknown conversation/);
  } finally { await host.close(); native.db.close(); }
});

test('usage, queue and writeEntry read the active conversation and emit their frame', async () => {
  const { native, host, ready } = await openHost();
  try {
    const usage = await host.usage();
    assert.deepEqual(usage.models, []);
    assert.deepEqual(usage.tools, []);
    assert.deepEqual(usage.context, { used: 0, window: 0 });
    assert.equal(native.events.some(event => event.event === 'usage'), true);

    const queue = await host.queue();
    assert.deepEqual(queue, { queued: [] });
    assert.equal(native.events.some(event => event.event === 'inbox'), true);

    const written = await host.writeEntry({ kind: 'app.note', data: { note: 'opened a file' } });
    assert.equal(written.status, 'done');
    assert.equal(native.events.some(event => event.event === 'written' && event.value.kind === 'app.note'), true);
    assert.deepEqual((await host.snapshot()).entries.at(-1).data, { note: 'opened a file' });

    await assert.rejects(host.steer({ text: '  ' }), /text required/);
    const missing = await host.withdraw({ submissionId: 123456 });
    assert.equal(missing.result, 'not_found');
    await assert.rejects(host.withdraw({ submissionId: 'not-a-number' }), /Submission id required/);
    // The active conversation is still the root, so the footer's frame names it.
    assert.equal(ready.conversationId > 0, true);
  } finally { await host.close(); native.db.close(); }
});

test('exportSession renders the live transcript and can write it into the workspace', async () => {
  const { native, host, ready } = await openHost({ workspace: '/workspace' });
  try {
    // A real reset appends a pi.reset entry, which the projection renders as a notice row.
    await host.resetSession({ handoff: 'handoff note' });
    const exported = await host.exportSession({ format: 'markdown' });
    assert.equal(exported.conversationId, String(ready.conversationId));
    assert.match(exported.content, /^# Pi conversation \d+/);
    assert.equal(exported.content.includes('Conversation reset.'), true);
    assert.equal(native.events.some(event => event.event === 'export' && event.value.format === 'markdown'), true);

    const written = await host.exportSession({ format: 'json', path: 'exports/chat.json' });
    assert.equal(written.path, '/workspace/exports/chat.json');
    assert.equal(native.files.has('/workspace/exports/chat.json'), true);
    assert.equal(JSON.parse(native.files.get('/workspace/exports/chat.json')).conversationId, String(ready.conversationId));
    await assert.rejects(host.exportSession({ format: 'pdf' }), /Unsupported export format/);
  } finally { await host.close(); native.db.close(); }
});

test('renameSession writes the one name the list and the tree report', async () => {
  const { native, host, ready } = await openHost();
  try {
    const id = ready.conversationId;
    const renamed = await host.renameSession({ conversationId: id, name: '  Release  plan  ' });
    assert.deepEqual(renamed, { conversationId: String(id), name: 'Release plan' });
    assert.equal(native.events.some(event => event.event === 'session.renamed' && event.value.name === 'Release plan'), true);

    // The refreshed list carries it and the tree shows the same name: one source of truth, not a
    // stored title beside a derived one.
    const listed = await host.sessions();
    assert.equal(listed.rows[0].name, 'Release plan');
    const refreshed = native.events.filter(event => event.event === 'conversations').at(-1);
    assert.equal(refreshed.value.rows[0].name, 'Release plan');
    const tree = await host.tree();
    assert.deepEqual(tree.nodes, [{ id: String(id), parentId: null, entryId: null, title: 'Release plan', current: true }]);

    // A name is capped exactly the way a derived one is, and the active conversation is the default.
    const long = 'x'.repeat(200);
    const capped = await host.renameSession({ name: long });
    assert.equal(capped.name.length, 120);
    assert.equal((await host.sessions()).rows[0].name, capped.name);
    await assert.rejects(host.renameSession({ name: '   ' }), /Name required/);
  } finally { await host.close(); native.db.close(); }
});

test('compactSession admits a compaction and reports its durable outcome', async () => {
  const { native, host } = await openHost();
  try {
    const admitted = await host.compactSession({ instructions: '  keep the API section  ' });
    assert.equal(admitted.status, 'admitted');
    assert.equal(admitted.instructions, 'keep the API section');
    assert.equal(typeof admitted.taskId, 'string');
    assert.equal(native.events.some(event => event.event === 'session.compacted' && event.value.status === 'admitted'), true);

    // Blank instructions are omitted rather than sent as an empty prompt.
    const bare = await host.compactSession();
    assert.equal(bare.status, 'admitted');
    assert.equal('instructions' in bare, false);

    // The terminal frame is the task's own outcome, never an assumption about it.
    assert.deepEqual(
      compactionOutcome('7', { state: { outcome: { status: 'completed', result: { entryId: 21 } } } }),
      { taskId: '7', status: 'completed', entryId: '21' },
    );
    assert.deepEqual(
      compactionOutcome('7', { state: { outcome: { status: 'failed', error: { message: 'no model configured' } } } }),
      { taskId: '7', status: 'failed', error: 'no model configured' },
    );
    assert.deepEqual(compactionOutcome(9, undefined), { taskId: '9', status: 'terminal' });
  } finally { await host.close(); native.db.close(); }
});
