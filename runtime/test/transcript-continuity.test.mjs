// The transcript-continuity contract at the projection level.
//
// Two rules the UI depends on:
//   1. a frame that is not a whole-conversation snapshot may never delete a row it does not carry,
//      so the frame says which it is (`snapshot`) instead of leaving the UI to guess from `full`;
//   2. a transient streaming row retired in a frame is gone from that frame — a later turn reusing
//      the same synthetic key must not resurrect it, and must never stand in for a committed row.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createTranscriptProjection } from '../src/transcript.mjs';

const text = value => ({ type: 'text', text: value });
const assistantEntry = (id, content) => ({ id, kind: 'pi.assistant', model: [{ role: 'assistant', content }] });

test('a snapshot frame is marked as the whole conversation it was seeded from', () => {
  const projection = createTranscriptProjection();
  const seeded = projection.apply([{
    type: 'snapshot',
    entries: [assistantEntry(4, [text('hi')])],
    tools: [], compactions: [], inbox: [], agent: {}, usage: {},
  }]);
  assert.equal(seeded.full, true);
  // The UI may replace its transcript only for a frame that says it carries the whole conversation.
  assert.equal(seeded.snapshot, true, JSON.stringify(seeded));

  const delta = projection.apply([{ type: 'entry_appended', entry: { id: 9, kind: 'pi.compaction' } }]);
  assert.equal(delta.full, false);
  assert.equal(delta.snapshot, false);
});

test('a transient row retired in the same frame is not resurrected by that frame', () => {
  const projection = createTranscriptProjection();
  // One commit can both grow the in-flight partial and commit its entry: the live row is updated
  // and retired in the same batch. It must not survive in the frame's rows.
  const frame = projection.apply([
    { type: 'message_start', message: { role: 'assistant', content: [text('Hel')] } },
    { type: 'message_update', changes: [{ type: 'text_delta', contentIndex: 0, delta: 'lo' }] },
    { type: 'message_end', entry: assistantEntry(5, [text('Hello')]) },
  ]);
  const keys = frame.rows.map(row => row.key);
  assert.deepEqual(keys, ['5:text0'], JSON.stringify(frame));
  assert.equal(frame.removed.includes('live:text0'), true, JSON.stringify(frame.removed));
  assert.equal(projection.rows().some(row => row.key === 'live:text0'), false, 'the transient row is gone');
});

test('the frame a later turn streams never carries an earlier turn\'s transient keys as data', () => {
  const projection = createTranscriptProjection();
  // Turn 1: a streamed answer, committed.
  projection.apply([{ type: 'message_start', message: { role: 'assistant', content: [text('first')] } }]);
  projection.apply([{ type: 'message_end', entry: assistantEntry(5, [text('first answer')]) }]);
  // Turn 2 reuses the synthetic key by design; the committed row of turn 1 is untouched.
  const live = projection.apply([{ type: 'message_start', message: { role: 'assistant', content: [text('second')] } }]);
  assert.deepEqual(live.rows.map(row => row.key), ['live:text0']);
  assert.equal(projection.rows().filter(row => row.key === '5:text0').length, 1);
});
