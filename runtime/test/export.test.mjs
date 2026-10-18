// The durable-backed export layer: turn a conversation's committed transcript into a portable
// document (markdown, json, jsonl). Exports project the same durable entries the UI renders.

import test from 'node:test';
import assert from 'node:assert/strict';
import { fauxAssistantMessage, fauxText, fauxThinking, fauxToolCall } from '@earendil-works/pi-ai/providers/faux';
import { EXPORT_FORMATS, exportConversation } from '../src/sessions.mjs';
import { withFauxHarness } from './support/durable.mjs';

async function turn(context, faux, conversation, responses, { prompt, requestId }) {
  faux.setResponses(responses);
  return (await conversation.submit({ type: 'input', content: prompt, requestId }, context)).wait(context);
}

test('exportConversation renders a markdown transcript and hides thinking by default', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    await turn(context, faux, root, [
      fauxAssistantMessage([fauxThinking('a private chain of thought'), fauxText('the public answer')]),
    ], { prompt: 'what is pi', requestId: 'e1' });

    const value = await exportConversation(root, { format: 'markdown', now: 0 }, context);
    assert.deepEqual(EXPORT_FORMATS, ['markdown', 'json', 'jsonl']);
    assert.equal(value.format, 'markdown');
    assert.equal(value.conversationId, String(root.id));
    assert.equal(value.exportedAt, new Date(0).toISOString());
    assert.equal(value.truncated, false);
    assert.match(value.content, /^# Pi conversation \d+/);
    assert.equal(value.content.includes('what is pi'), true);
    assert.equal(value.content.includes('the public answer'), true);
    assert.equal(value.content.includes('a private chain of thought'), false);
    assert.equal(value.messages >= 2, true);
    assert.equal(value.bytes, value.content.length);

    const withThinking = await exportConversation(root, { format: 'markdown', includeThinking: true, now: 0 }, context);
    assert.equal(withThinking.content.includes('a private chain of thought'), true);
  });
});

test('exportConversation folds a tool result onto one tool row with its status', async () => {
  await withFauxHarness(async ({ context, faux, root }) => {
    // No tool is registered, so generation records an error result for the call it did not offer.
    await turn(context, faux, root, [
      fauxAssistantMessage([fauxToolCall('bash', { command: 'echo hi' })], { stopReason: 'toolUse' }),
      fauxAssistantMessage('done'),
    ], { prompt: 'run it', requestId: 'e2' });

    const { content } = await exportConversation(root, { format: 'markdown', now: 0 }, context);
    const headings = content.split('\n').filter(line => line.startsWith('## '));
    assert.equal(headings.filter(line => line.startsWith('## Tool: bash')).length, 1);
    assert.equal(headings.filter(line => line.startsWith('## User')).length, 1);
    assert.match(content, /## Tool: bash \[(ok|error)\]/);
  });
});

test('exportConversation emits parsed json and one-object-per-line jsonl', async () => {
  await withFauxHarness(async ({ context, faux, root }) => {
    await turn(context, faux, root, [fauxAssistantMessage('json answer')], { prompt: 'json please', requestId: 'e3' });

    const json = await exportConversation(root, { format: 'json', now: 1000 }, context);
    const parsed = JSON.parse(json.content);
    assert.equal(parsed.conversationId, String(root.id));
    assert.equal(parsed.exportedAt, new Date(1000).toISOString());
    assert.equal(Array.isArray(parsed.messages), true);
    assert.equal(parsed.messages[0].role, 'user');
    assert.equal(parsed.messages.at(-1).role, 'assistant');
    assert.equal(parsed.messages.length, json.messages);

    const jsonl = await exportConversation(root, { format: 'jsonl', now: 1000 }, context);
    const lines = jsonl.content.trimEnd().split('\n');
    assert.equal(lines.length, jsonl.messages);
    assert.deepEqual(lines.map(line => JSON.parse(line).role), parsed.messages.map(row => row.role));
  });
});

test('exportConversation rejects an unknown format and never claims a truncated read is complete', async () => {
  await withFauxHarness(async ({ context, faux, root }) => {
    await turn(context, faux, root, [fauxAssistantMessage('one more answer')], { prompt: 'single entry', requestId: 'e4' });
    await assert.rejects(exportConversation(root, { format: 'pdf' }, context), /Unsupported export format/);

    const capped = await exportConversation(root, { format: 'json', limit: 1, now: 0 }, context);
    assert.equal(capped.truncated, true);
    assert.equal(capped.entries, 1);
    assert.equal(JSON.parse(capped.content).messages.length, 1);
  });
});
