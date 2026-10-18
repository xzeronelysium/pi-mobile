import test from 'node:test';
import assert from 'node:assert/strict';
import { createTranscriptProjection } from '../src/transcript.mjs';
import { projectTranscript } from '../src/ui.mjs';

// A batch shaped exactly like `runtime/src/host.mjs` forwards one commit's durable agent events.
const assistantStart = message => ({ type: 'message_start', message });
const assistantDelta = changes => ({ type: 'message_update', usage: {}, changes });
const assistantEnd = entry => ({ type: 'message_end', entry });
const text = value => ({ type: 'text', text: value });
const assistantEntry = (id, content) => ({ id, kind: 'pi.assistant', model: [{ role: 'assistant', content }] });
const toolResultEntry = (id, toolCallId, output) => ({
  id,
  kind: 'pi.tool-result',
  model: [{ role: 'toolResult', toolCallId, toolName: 'read', content: [text(output)], isError: false }],
});

/** The projection's rows as a key-keyed map, the shape the Kotlin reducer upserts by id. */
const byKey = rows => new Map(rows.map(row => [row.key, row]));

test('a streamed text delta updates the in-flight assistant row without re-sending any other row', () => {
  const projection = createTranscriptProjection();
  const opened = projection.apply([assistantStart({ role: 'assistant', content: [text('Hel')] })]);
  assert.equal(opened.full, false);
  assert.equal(opened.removed.length, 0);
  assert.deepEqual(opened.rows.map(row => row.role), ['assistant']);
  assert.equal(opened.rows[0].text, 'Hel');

  const grown = projection.apply([assistantDelta([{ type: 'text_delta', contentIndex: 0, delta: 'lo' }])]);
  // Only the in-flight row is re-sent — the delta carries the increment, not the whole projection.
  assert.equal(grown.rows.length, 1, JSON.stringify(grown));
  assert.equal(grown.rows[0].key, opened.rows[0].key);
  assert.equal(grown.rows[0].role, 'assistant');
  assert.equal(grown.rows[0].text, 'Hello');
  assert.deepEqual(grown.removed, []);
});

test('a tool call start and its result attach to the same durable row by id', () => {
  const projection = createTranscriptProjection();
  projection.apply([assistantStart({ role: 'assistant', content: [{ type: 'toolCall', id: 'call-1', name: 'read', arguments: { path: 'a' } }] })]);

  const streamed = projection.apply([assistantDelta([{ type: 'toolcall_delta', contentIndex: 0, path: ['path'], delta: '.txt' }])]);
  const streamedRow = streamed.rows.find(row => row.toolCallId === 'call-1');
  assert.equal(streamedRow.role, 'tool');
  assert.equal(streamedRow.name, 'read');
  assert.equal(streamedRow.arguments, JSON.stringify({ path: 'a.txt' }));

  // The durable assistant entry lands, then the tool result. Both name `call-1`; the result is
  // folded onto the tool-call row rather than opened as a second row.
  const settled = projection.apply([
    assistantEnd(assistantEntry(5, [{ type: 'toolCall', id: 'call-1', name: 'read', arguments: { path: 'a.txt' } }])),
    { type: 'tool_execution_end', toolCallId: 'call-1', toolName: 'read', entry: toolResultEntry(6, 'call-1', 'file body') },
    assistantEnd(toolResultEntry(6, 'call-1', 'file body')),
  ]);

  const tools = settled.rows.filter(row => row.role === 'tool' && row.toolCallId === 'call-1');
  assert.equal(tools.length, 1, JSON.stringify(settled.rows));
  assert.equal(tools[0].key, '5:tool0');
  assert.equal(tools[0].status, 'ok');
  assert.equal(tools[0].output, 'file body');
  // The transient streaming row is retired in the same frame.
  assert.equal(settled.removed.includes('live:tool0'), true, JSON.stringify(settled.removed));
});

test('an appended entry appears as its own incremental row', () => {
  const projection = createTranscriptProjection();
  const frame = projection.apply([{ type: 'entry_appended', entry: { id: 9, kind: 'pi.compaction' } }]);
  assert.equal(frame.rows.length, 1);
  assert.equal(frame.rows[0].role, 'notice');
  assert.equal(frame.rows[0].key, '9:notice');
  assert.equal(frame.rows[0].text, 'Context compacted.');
});

test('a batch that changes nothing carries no rows and no removals', () => {
  const projection = createTranscriptProjection();
  projection.apply([{ type: 'entry_appended', entry: { id: 1, kind: 'pi.compaction' } }]);
  const frame = projection.apply([{ type: 'usage_changed', usage: {} }, { type: 'inbox_update', items: [] }, { type: 'turn_start' }]);
  assert.deepEqual(frame.rows, []);
  assert.deepEqual(frame.removed, []);
  assert.equal(frame.full, false);
});

test('a snapshot frame is a full replace seeded from the snapshot entries', () => {
  const projection = createTranscriptProjection();
  projection.apply([{ type: 'entry_appended', entry: { id: 1, kind: 'pi.compaction' } }]);
  const frame = projection.apply([{ type: 'snapshot', entries: [assistantEntry(4, [text('hi')])], tools: [], compactions: [], inbox: [], agent: {}, usage: {} }]);
  assert.equal(frame.full, true);
  assert.deepEqual(frame.rows.map(row => row.key), ['4:text0']);
});

test('the incremental projection equals the whole-transcript projection once the turn settles', () => {
  const entries = [
    { id: 1, kind: 'pi.system', model: [{ role: 'system', content: 'prompt' }] },
    { id: 2, kind: 'pi.user', model: [{ role: 'user', content: [text('read data.txt')] }] },
    { id: 7, kind: 'pi.assistant', model: [{ role: 'assistant', content: [{ type: 'toolCall', id: 'call-1', name: 'read', arguments: { path: 'data.txt' } }], stopReason: 'toolUse' }] },
    toolResultEntry(8, 'call-1', 'hello'),
    assistantEntry(9, [text('It says hello.')]),
  ];
  const projection = createTranscriptProjection();

  // Replay the same stream the harness emits, including a streamed answer for entry 9.
  projection.apply([{ type: 'snapshot', entries: [], tools: [], compactions: [], inbox: [], agent: {}, usage: {} }]);
  projection.apply([assistantEnd(entries[1])]);
  projection.apply([assistantEnd(entries[2])]);
  projection.apply([
    { type: 'tool_execution_end', toolCallId: 'call-1', toolName: 'read', entry: entries[3] },
    assistantEnd(entries[3]),
  ]);
  projection.apply([assistantStart({ role: 'assistant', content: [text('It says ')] })]);
  projection.apply([assistantDelta([{ type: 'text_delta', contentIndex: 0, delta: 'hello.' }])]);
  projection.apply([assistantEnd(entries[4])]);

  // The settled projection and a plain whole-transcript read must agree, row for row, by key.
  assert.deepEqual(byKey(projection.rows()), byKey(projectTranscript(entries)));
});
