// The durable-backed HTML export: a self-contained grayscale document rendered from the same
// projected transcript rows the Compose screen shows, so an off-device reader sees what the app
// showed. Standalone file: the structured-data formats keep their own contract in export.test.mjs.

import test from 'node:test';
import assert from 'node:assert/strict';
import { fauxAssistantMessage, fauxText, fauxThinking } from '@earendil-works/pi-ai/providers/faux';
import { EXPORT_FORMATS, EXPORT_TARGETS, exportConversation } from '../src/sessions.mjs';
import { withFauxHarness } from './support/durable.mjs';

async function turn(context, faux, conversation, responses, { prompt, requestId }) {
  faux.setResponses(responses);
  return (await conversation.submit({ type: 'input', content: prompt, requestId }, context)).wait(context);
}

test('exportConversation renders a self-contained HTML document from the projected rows', async () => {
  await withFauxHarness(async ({ context, faux, root }) => {
    await turn(context, faux, root, [
      fauxAssistantMessage([fauxThinking('a private chain of thought'), fauxText('the public answer')]),
    ], { prompt: 'what is pi', requestId: 'h1' });

    const value = await exportConversation(root, { format: 'html', now: 0 }, context);
    // The structured formats keep their exact inventory; HTML is added as a rendered target.
    assert.deepEqual(EXPORT_FORMATS, ['markdown', 'json', 'jsonl']);
    assert.equal(EXPORT_TARGETS.includes('html'), true);
    assert.equal(value.format, 'html');
    assert.equal(value.mime, 'text/html');
    assert.equal(value.conversationId, String(root.id));
    assert.equal(value.truncated, false);
    assert.equal(value.content.startsWith('<!DOCTYPE html>'), true);
    assert.equal(value.content.trimEnd().endsWith('</html>'), true);
    assert.match(value.content, /<title>Pi conversation \d+<\/title>/);
    // The transcript itself is in the page, not base64-wrapped away from a text reader.
    assert.equal(value.content.includes('what is pi'), true);
    assert.equal(value.content.includes('the public answer'), true);
    assert.equal(value.content.includes('a private chain of thought'), false);
    assert.equal(value.bytes, value.content.length);

    const withThinking = await exportConversation(root, { format: 'html', includeThinking: true, now: 0 }, context);
    assert.equal(withThinking.content.includes('a private chain of thought'), true);
  });
});

test('the HTML export escapes user text so a prompt can never inject markup', async () => {
  await withFauxHarness(async ({ context, faux, root }) => {
    await turn(context, faux, root, [fauxAssistantMessage('ok')], {
      prompt: 'a <script>alert(1)</script> & "quoted" tag',
      requestId: 'h2',
    });

    const { content } = await exportConversation(root, { format: 'html', now: 0 }, context);
    assert.equal(content.includes('&lt;script&gt;alert(1)&lt;/script&gt;'), true);
    assert.equal(content.includes('&amp;'), true);
    assert.equal(content.includes('<script>alert(1)</script>'), false);
  });
});

test('the HTML export still refuses an unknown format and reports truncation', async () => {
  await withFauxHarness(async ({ context, faux, root }) => {
    await turn(context, faux, root, [fauxAssistantMessage('one more answer')], { prompt: 'single', requestId: 'h3' });
    await assert.rejects(exportConversation(root, { format: 'pdf' }, context), /Unsupported export format/);

    const capped = await exportConversation(root, { format: 'html', limit: 1, now: 0 }, context);
    assert.equal(capped.truncated, true);
    assert.equal(capped.entries, 1);
    assert.equal(capped.content.includes('truncated'), true);
  });
});
