// The durable-backed queue layer, as the Kotlin inbox reads it: the `inbox` payload
// (`InboxReducer`: `{queued:[{id,kind,text,status}]}`) plus steered/follow-up admission, passive
// entry writes and withdrawal. Reads `pi.inbox` and the submission ledger through the real Harness.

import test from 'node:test';
import assert from 'node:assert/strict';
import { fauxAssistantMessage } from '@earendil-works/pi-ai/providers/faux';
import { enqueue, queueState, withdraw, writeEntry } from '../src/sessions.mjs';
import { withFauxHarness } from './support/durable.mjs';

/** A faux answer that parks the run until the returned `release` is called. */
function gatedResponse(answer) {
  let release;
  let entered;
  const started = new Promise(resolve => { entered = resolve; });
  const response = async () => { entered(); await new Promise(resolve => { release = resolve; }); return fauxAssistantMessage(answer); };
  return { response, started, release: () => release() };
}

test('queueState emits the queued rows the inbox renders while the run is busy', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    const gate = gatedResponse('first answer');
    faux.setResponses([gate.response, fauxAssistantMessage('second answer')]);
    const first = await root.submit({ type: 'input', content: 'start', requestId: 'q1' }, context);
    await gate.started;

    const steer = await enqueue(root, { text: 'steer this', mode: 'steer', requestId: 'q2' }, context);
    const follow = await enqueue(root, { text: 'follow up', mode: 'followUp', requestId: 'q3' }, context);

    const queued = await queueState(harness, root.id, context);
    assert.deepEqual(queued.queued.map(item => [item.kind, item.text, item.status]), [
      ['steer', 'steer this', 'queued'],
      ['followUp', 'follow up', 'queued'],
    ]);
    // Ids are strings, because that is what the drawer withdraws by.
    assert.equal(queued.queued[0].id, String(steer.id));
    assert.equal(queued.queued[1].id, String(follow.id));

    // An unknown conversation reads as an empty inbox rather than throwing.
    assert.deepEqual(await queueState(harness, 9999, context), { queued: [] });

    gate.release();
    assert.equal((await first.wait(context)).status, 'done');
  });
});

test('withdraw removes a queued submission and settles it unanswered, not merely hidden', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    const gate = gatedResponse('first answer');
    faux.setResponses([gate.response, fauxAssistantMessage('second answer')]);
    const first = await root.submit({ type: 'input', content: 'start', requestId: 'q1' }, context);
    await gate.started;

    const steer = await enqueue(root, { text: 'never mind', mode: 'steer', requestId: 'q2' }, context);
    assert.equal((await queueState(harness, root.id, context)).queued.length, 1);
    // The drawer withdraws by the string id it rendered.
    assert.equal(await withdraw(harness, String(steer.id), context, root.id), 'aborted');
    assert.equal((await queueState(harness, root.id, context)).queued.length, 0);
    const submission = await harness.submission(steer.id, context);
    assert.equal((await submission.status(context)).status, 'unanswered');
    // Withdrawing twice, or an unknown id, is reported instead of pretending success.
    assert.equal(await withdraw(harness, steer.id, context, root.id), 'settled');
    assert.equal(await withdraw(harness, '987654', context, root.id), 'not_found');
    await assert.rejects(withdraw(harness, 'not-a-number', context, root.id), /Submission id required/);

    gate.release();
    assert.equal((await first.wait(context)).status, 'done');
  });
});

test('enqueue follow-up input starts the next run once the current answer lands', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    const gate = gatedResponse('first answer');
    faux.setResponses([gate.response, fauxAssistantMessage('second answer')]);
    const first = await root.submit({ type: 'input', content: 'start', requestId: 'q1' }, context);
    await gate.started;

    const follow = await enqueue(root, { text: 'and then this', mode: 'followUp', requestId: 'q2' }, context);
    assert.equal(follow.mode, 'followUp');
    assert.equal(follow.status, 'queued');

    gate.release();
    assert.equal((await first.wait(context)).status, 'done');
    const followSubmission = await harness.submission(follow.id, context);
    assert.equal((await followSubmission.wait(context)).status, 'done');
    assert.deepEqual((await queueState(harness, root.id, context)).queued, []);
    const rows = (await root.entries({ order: 'ascending' }, 50, undefined, context)).items;
    assert.equal(JSON.stringify(rows).includes('and then this'), true);
  });
});

test('writeEntry appends a passive entry the model never answers', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    // No scripted response: a passive write must not call the model at all.
    faux.setResponses([]);
    const written = await writeEntry(root, { kind: 'app.note', data: { note: 'opened a file' } }, context);
    assert.equal(written.kind, 'app.note');
    assert.equal(written.status, 'done');
    const newest = (await root.entries({ order: 'descending' }, 1, undefined, context)).items[0];
    assert.equal(newest.kind, 'app.note');
    assert.deepEqual(newest.data, { note: 'opened a file' });
    assert.equal(faux.state.callCount, 0);
    assert.deepEqual((await queueState(harness, root.id, context)).queued, []);
  });
});

test('enqueue rejects empty text and an unsupported mode instead of queueing silently', async () => {
  await withFauxHarness(async ({ context, root }) => {
    await assert.rejects(enqueue(root, { text: '   ', mode: 'steer' }, context), /text required/);
    await assert.rejects(enqueue(root, { text: 'ok', mode: 'now' }, context), /Unsupported queue mode/);
  });
});
