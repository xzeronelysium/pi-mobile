// The durable-backed session layer, as the Kotlin drawer reads it: the `conversations` payload
// (`SessionListReducer`: `{current, rows:[{id,name,created,modified,messageCount,owner,current}]}`)
// and the `tree` payload (`ConversationTreeReducer`: `{nodes:[{id,parentId,entryId,title,current}]}`).
// Every read comes from committed storage through the real Harness.

import test from 'node:test';
import assert from 'node:assert/strict';
import { fauxAssistantMessage } from '@earendil-works/pi-ai/providers/faux';
import { conversationTree, createSession, forkSession, listSessions, openSession, resetSession } from '../src/sessions.mjs';
import { withFauxHarness } from './support/durable.mjs';

/** Answer one input on `conversation` with a scripted faux response and wait for its receipt. */
async function turn(context, faux, conversation, { prompt, answer, requestId }) {
  faux.setResponses([fauxAssistantMessage(answer)]);
  return (await conversation.submit({ type: 'input', content: prompt, requestId }, context)).wait(context);
}

test('listSessions emits the conversations rows the drawer reads, newest first', async () => {
  await withFauxHarness(async ({ context, faux, harness, root, agent }) => {
    await turn(context, faux, root, { prompt: 'hello world', answer: 'first answer', requestId: 's1' });
    const other = await createSession(harness, { agent }, context);
    await turn(context, faux, other, { prompt: 'second chat', answer: 'second answer', requestId: 's2' });

    const listed = await listSessions(harness, { currentId: other.id }, context);
    assert.equal(listed.current, String(other.id));
    assert.equal(listed.rows.length, 2);
    assert.equal(listed.next, null);

    const newest = listed.rows[0];
    assert.equal(newest.id, String(other.id));
    assert.equal(newest.name, 'second chat');
    assert.equal(newest.current, true);
    assert.equal(newest.messageCount, 2);
    assert.equal(typeof newest.created, 'number');
    assert.equal(newest.modified >= newest.created, true);
    // An ownerless conversation has no owner; the drawer omits the segment rather than inventing one.
    assert.equal(newest.owner, null);

    const oldest = listed.rows[1];
    assert.equal(oldest.id, String(root.id));
    assert.equal(oldest.name, 'hello world');
    assert.equal(oldest.current, false);
    assert.equal(oldest.messageCount, 2);
  });
});

test('listSessions pages with a cursor and reports the selection it was asked about', async () => {
  await withFauxHarness(async ({ context, harness, root, agent }) => {
    for (let index = 0; index < 3; index += 1) await createSession(harness, { agent }, context);
    const first = await listSessions(harness, { limit: 2, currentId: root.id }, context);
    assert.equal(first.rows.length, 2);
    assert.equal(first.current, String(root.id));
    assert.equal(typeof first.next, 'object');
    const second = await listSessions(harness, { limit: 2, cursor: first.next }, context);
    assert.equal(second.rows.length, 2);
    const ids = new Set([...first.rows, ...second.rows].map(row => row.id));
    assert.equal(ids.size, 4);
    assert.equal(ids.has(String(root.id)), true);
  });
});

test('openSession returns a handle, its rows and its fork edge; an unknown id is reported', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    await turn(context, faux, root, { prompt: 'root prompt', answer: 'root answer', requestId: 'r1' });
    const opened = await openSession(harness, root.id, context);
    assert.equal(opened.conversation.id, root.id);
    assert.equal(opened.parent, null);
    assert.equal(opened.rows.some(row => row.role === 'user' && row.text === 'root prompt'), true);
    assert.equal(opened.rows.some(row => row.role === 'assistant' && row.text === 'root answer'), true);
    assert.equal(await openSession(harness, 9999, context), null);
  });
});

test('forkSession inherits the parent transcript through the fork entry and records its parent', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    const settled = await turn(context, faux, root, { prompt: 'fork me', answer: 'root answer', requestId: 'r1' });
    assert.equal(settled.status, 'done');

    const fork = await forkSession(root, settled.entry, {}, context);
    assert.equal(fork.id === root.id, false);
    const forked = await openSession(harness, fork.id, context);
    assert.equal(forked.rows.some(row => row.text === 'fork me'), true);
    assert.equal(forked.parent.conversationId, root.id);
    assert.equal(forked.parent.at, settled.entry);

    // The fork continues independently: a new turn never appears in the parent.
    await turn(context, faux, fork, { prompt: 'on the fork', answer: 'fork answer', requestId: 'r2' });
    const parent = await openSession(harness, root.id, context);
    assert.equal(parent.rows.some(row => row.text === 'on the fork'), false);
  });
});

test('conversationTree nests a fork under its parent with the entry it forked at', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    const settled = await turn(context, faux, root, { prompt: 'fork me', answer: 'root answer', requestId: 'r1' });
    const fork = await forkSession(root, settled.entry, {}, context);

    const tree = await conversationTree(harness, { currentId: fork.id }, context);
    const forkNode = tree.nodes.find(node => node.id === String(fork.id));
    assert.equal(forkNode.parentId, String(root.id));
    assert.equal(forkNode.entryId, String(settled.entry));
    assert.equal(forkNode.title, 'fork me');
    assert.equal(forkNode.current, true);
    // The reducer computes depth itself, so the host must not claim one.
    assert.equal('depth' in forkNode, false);

    const rootNode = tree.nodes.find(node => node.id === String(root.id));
    assert.equal(rootNode.parentId, null);
    assert.equal(rootNode.entryId, null);
    assert.equal(rootNode.current, false);
  });
});

test('resetSession starts a new context without destroying stored entries', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    await turn(context, faux, root, { prompt: 'old prompt', answer: 'before', requestId: 'r1' });
    const before = await root.context(context);
    assert.equal(JSON.stringify(before.messages).includes('old prompt'), true);

    const value = await resetSession(root, 'handoff note', context);
    assert.deepEqual(value, { conversationId: root.id, reset: true });
    const view = await root.context(context);
    assert.equal(view.entries.map(entry => entry.kind).includes('pi.reset'), true);
    assert.equal(JSON.stringify(view.messages).includes('handoff note'), true);
    // The model no longer sees the pre-reset prompt...
    assert.equal(JSON.stringify(view.messages).includes('old prompt'), false);
    // ...but the durable transcript keeps it, so nothing is silently destroyed.
    const stored = await openSession(harness, root.id, context);
    assert.equal(stored.rows.some(row => row.text === 'old prompt'), true);
  });
});
