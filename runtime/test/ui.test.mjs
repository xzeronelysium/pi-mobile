import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BACKGROUND_CONTEXT } from '@earendil-works/chord/context';
import { createModels } from '@earendil-works/pi-ai/models';
import { fauxAssistantMessage, fauxProvider, fauxToolCall } from '@earendil-works/pi-ai/providers/faux';
import { Harness, MemoryStorage, createRegistry } from '@earendil-works/pi-durable';
import { CodingTools } from '@earendil-works/pi-durable/tools';
import { createAndroidExecutionEnv } from '../src/env.mjs';
import { projectTranscript } from '../src/ui.mjs';
import { createNodeBridge } from './support/node-bridge.mjs';

test('projectTranscript is a pure projection of durable entry shapes', () => {
  const rows = projectTranscript([
    { kind: 'pi.system', id: 1, model: [{ role: 'system', content: 'prompt' }] },
    { kind: 'pi.user', id: 2, model: [{ role: 'user', content: [{ type: 'text', text: 'hi' }, { type: 'image', data: 'x', mimeType: 'image/png' }] }] },
    { kind: 'pi.compaction', id: 3, model: [] },
    {
      kind: 'pi.assistant',
      id: 4,
      model: [{
        role: 'assistant',
        content: [
          { type: 'thinking', thinking: 'hmm' },
          { type: 'text', text: 'working' },
          { type: 'toolCall', id: 'call-1', name: 'read', arguments: { path: 'a.txt' } },
        ],
        stopReason: 'toolUse',
      }],
    },
    { kind: 'pi.tool-result', id: 5, model: [{ role: 'toolResult', toolCallId: 'call-1', toolName: 'read', content: [{ type: 'text', text: 'file body' }], isError: false, durationMs: 3 }] },
    { kind: 'pi.tool-result', id: 6, model: [{ role: 'toolResult', toolCallId: 'orphan', toolName: 'bash', content: [{ type: 'text', text: 'boom' }], isError: true }] },
  ]);

  assert.deepEqual(rows.map(row => row.role), ['user', 'notice', 'thinking', 'assistant', 'tool', 'tool']);
  assert.equal(rows[0].text, 'hi');
  assert.equal(rows[0].attachments, 1);
  assert.equal(rows[1].text, 'Context compacted.');
  assert.equal(rows[2].text, 'hmm');

  const call = rows.find(row => row.toolCallId === 'call-1');
  assert.equal(call.name, 'read');
  assert.equal(call.status, 'ok');
  assert.equal(call.output, 'file body');
  assert.equal(call.arguments, JSON.stringify({ path: 'a.txt' }));
  assert.equal(call.durationMs, 3);

  const orphan = rows.find(row => row.toolCallId === 'orphan');
  assert.equal(orphan.status, 'error');
  assert.equal(orphan.output, 'boom');
});

test('projectTranscript folds a real CodingTools turn into ordered rows', async () => {
  const context = BACKGROUND_CONTEXT;
  const dir = await mkdtemp(join(tmpdir(), 'pi-ui-'));
  const bridge = createNodeBridge({ root: dir, tempRoot: dir });
  const env = createAndroidExecutionEnv({ call: bridge.call, cwd: dir, subscribe: bridge.subscribe });
  await bridge.call('file.write', { path: join(dir, 'data.txt'), text: 'hello from android' });

  const faux = fauxProvider();
  const models = createModels();
  models.setProvider(faux.provider);
  const registry = createRegistry();
  registry.install(CodingTools);
  faux.setResponses([
    fauxAssistantMessage([fauxToolCall('read', { path: 'data.txt' })], { stopReason: 'toolUse' }),
    fauxAssistantMessage('It says hello.'),
  ]);

  const harness = await Harness.open(new MemoryStorage(), { models, registry, env: () => env }, context);
  try {
    const root = await harness.root(context, { agent: { model: { provider: faux.provider.id, modelId: faux.getModel().id } } });
    const settled = await (await root.submit({ type: 'input', content: 'read data.txt', requestId: 'r1' }, context)).wait(context);
    assert.equal(settled.status, 'done');

    const page = await root.entries({ order: 'ascending' }, 50, undefined, context);
    const rows = projectTranscript(page.items ?? page);

    assert.deepEqual(rows.map(row => row.role), ['user', 'tool', 'assistant']);
    assert.equal(rows[0].text, 'read data.txt');
    assert.equal(rows[1].name, 'read');
    assert.equal(rows[1].status, 'ok');
    assert.equal(rows[1].output, 'hello from android');
    assert.equal(rows[2].text, 'It says hello.');
  } finally {
    await harness.close(context);
    await bridge.close();
    await rm(dir, { recursive: true, force: true });
  }
});
