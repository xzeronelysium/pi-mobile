// The reconcile read the UI transcript is rebuilt from.
//
// The reader is looking at the TAIL of the conversation, so the read that backs the transcript has
// to be the tail too. A head page (the oldest entries) that reaches the UI as a whole-conversation
// read deletes the newest rows from the screen — the reported "the previous message and its answer
// vanish when the next message is sent". A page that does not reach the conversation's first entry
// is flagged the same way it already is (`next`), which is what tells the UI to merge it instead of
// replacing the transcript with it.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createNativeHost } from './support/host-native.mjs';

const PROVIDER = 'groq';
const MODEL = 'llama-3.1-8b-instant';

/** One OpenAI-completions SSE stream, the shape pi-ai's `openai-completions` api consumes. */
const stream = content => `data: ${JSON.stringify({
  id: 'chatcmpl-continuity', object: 'chat.completion.chunk', created: 1, model: MODEL,
  choices: [{ index: 0, delta: { role: 'assistant', content }, finish_reason: null }],
})}\n\ndata: ${JSON.stringify({
  id: 'chatcmpl-continuity', object: 'chat.completion.chunk', created: 1, model: MODEL,
  choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
})}\n\ndata: [DONE]\n\n`;

/** A model that answers each turn with one chunk, so a turn costs no network. */
function offlineModel() {
  let calls = 0;
  return async () => new Response(stream(`answer ${calls += 1}`), { status: 200, headers: { 'content-type': 'text/event-stream' } });
}

async function openHost() {
  const { createPiNative } = await import('../src/host.mjs');
  const native = createNativeHost();
  const host = createPiNative(native.bridge, { fetch: offlineModel() });
  await host.init({ probeShell: false });
  return { native, host };
}

/** How many entries the conversation holds, read past any one page. */
const entriesOf = async host => (await host.history({ limit: 500 })).items.length;

test('the reconcile read carries the newest entries, not the oldest page', async () => {
  const { host } = await openHost();
  try {
    await host.send({ provider: PROVIDER, apiKey: 'test-key-not-a-production-credential', modelId: MODEL, prompt: 'first question', requestId: 'r1' });
    // The reader keeps chatting in the one session they get (nothing records where they were), so
    // the conversation outgrows a single page of entries.
    while ((await entriesOf(host)) <= 200) await host.writeEntry({ kind: 'app.note', data: { note: 'padding' } });
    await host.send({ provider: PROVIDER, apiKey: 'test-key-not-a-production-credential', modelId: MODEL, prompt: 'second question', requestId: 'r2' });

    const page = await host.history();
    const texts = page.rows.map(row => row.text);
    assert.equal(texts.includes('second question'), true, `the newest message is not in the read: ${JSON.stringify(texts)}`);
    assert.equal(texts.some(text => text?.startsWith('answer 2')), true, `the newest answer is not in the read: ${JSON.stringify(texts)}`);
    assert.equal(texts.includes('first question'), false, 'the read is the tail, not the head');
    // It is a page, not the whole conversation, and says so — so the UI merges it instead of
    // replacing the transcript with it.
    assert.notEqual(page.next, undefined, 'an incomplete read must carry its continuation');
    assert.equal(page.rows.length, 2, `only the newest entries fall in this page: ${JSON.stringify(texts)}`);
  } finally { await host.close(); }
});

test('a read that does reach the first entry is complete, so it may replace', async () => {
  const { host } = await openHost();
  try {
    await host.send({ provider: PROVIDER, apiKey: 'test-key-not-a-production-credential', modelId: MODEL, prompt: 'only question', requestId: 'r1' });
    const page = await host.history();
    assert.equal(page.next, undefined, 'a short conversation is read whole');
    assert.equal(page.rows.map(row => row.text).join('|'), 'only question|answer 1');
  } finally { await host.close(); }
});

test('the seed frame a launch starts from is a whole-conversation snapshot', async () => {
  const { native, host } = await openHost();
  try {
    await host.send({ provider: PROVIDER, apiKey: 'test-key-not-a-production-credential', modelId: MODEL, prompt: 'first question', requestId: 'r1' });
    const seed = native.events.find(event => event.event === 'message');
    assert.equal(seed.value.full, true);
    assert.equal(seed.value.snapshot, true, JSON.stringify(seed.value));
  } finally { await host.close(); }
});
