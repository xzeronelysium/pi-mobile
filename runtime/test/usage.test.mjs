// The durable-backed usage layer, as the Kotlin footer reads it: the `usage` payload
// (`UsageReducer`: `{models:[{provider,modelId,input,output,cacheRead,cacheWrite,cost}],
// tools:[{name}], context:{used,window}}`). Spend is read from committed documents only.

import test from 'node:test';
import assert from 'node:assert/strict';
import { fauxAssistantMessage } from '@earendil-works/pi-ai/providers/faux';
import { projectUsage, usageState, zeroUsage } from '../src/sessions.mjs';
import { withFauxHarness } from './support/durable.mjs';

async function turn(context, faux, conversation, { prompt, answer, requestId }) {
  faux.setResponses([fauxAssistantMessage(answer)]);
  return (await conversation.submit({ type: 'input', content: prompt, requestId }, context)).wait(context);
}

const usage = (input, output, extra = {}) => ({
  input,
  output,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: input + output,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
  ...extra,
});

test('projectUsage splits provider/model keys, sorts buckets and sums a grand total', () => {
  const state = {
    models: {
      'openai/gpt-4.1': usage(10, 2),
      'openrouter/black-forest-labs/flux-3-image': usage(1, 1, { reasoning: 3 }),
      'faux/faux-1': usage(5, 5),
    },
    tools: { bash: usage(0, 4), read: usage(0, 1) },
  };
  const projected = projectUsage(state);
  assert.deepEqual(projected.models.map(bucket => bucket.key), [
    'faux/faux-1',
    'openai/gpt-4.1',
    'openrouter/black-forest-labs/flux-3-image',
  ]);
  // A model id may itself contain slashes; only the first segment is the provider.
  assert.equal(projected.models[2].provider, 'openrouter');
  assert.equal(projected.models[2].modelId, 'black-forest-labs/flux-3-image');
  assert.deepEqual(projected.tools.map(bucket => bucket.name), ['bash', 'read']);
  assert.equal(projected.totals.input, 16);
  assert.equal(projected.totals.output, 13);
  assert.equal(projected.totals.totalTokens, 29);
  // An optional counter is added once either side reports it, and stays absent otherwise.
  assert.equal(projected.totals.reasoning, 3);
  assert.equal('cacheWrite1h' in projected.totals, false);
});

test('projectUsage treats an absent ledger as zero rather than throwing', () => {
  assert.deepEqual(projectUsage(undefined), { models: [], tools: [], totals: zeroUsage() });
  assert.deepEqual(projectUsage({}), { models: [], tools: [], totals: zeroUsage() });
});

test('usageState reports the real model buckets a completed turn recorded', async () => {
  await withFauxHarness(async ({ context, faux, harness, root }) => {
    await turn(context, faux, root, { prompt: 'count the tokens', answer: 'a token-rich answer', requestId: 'u1' });
    const window = 128_000;
    const state = await usageState(harness, root, context, {
      windowOf: (provider, modelId) => (provider === 'faux' && modelId === faux.getModel().id ? window : undefined),
    });

    assert.equal(state.models.length, 1);
    const row = state.models[0];
    assert.equal(row.provider, 'faux');
    assert.equal(row.modelId, faux.getModel().id);
    assert.equal(row.input > 0, true);
    assert.equal(row.output > 0, true);
    assert.equal(row.cacheWrite >= 0, true);
    assert.equal(row.cost, 0);
    // Request counts are not part of a durable Usage bucket, so the host never invents one.
    assert.equal('requests' in row, false);
    assert.deepEqual(state.tools, []);

    // `used` is the prompt size the model actually reported for the last request...
    assert.equal(state.context.window, window);
    assert.equal(state.context.used, row.input + row.cacheRead + row.cacheWrite);
    assert.equal(state.context.used > 0, true);
  });
});

test('usageState reports one session ledger and an unknown model window as 0', async () => {
  await withFauxHarness(async ({ context, faux, harness, root, agent }) => {
    await turn(context, faux, root, { prompt: 'first chat', answer: 'first answer here', requestId: 'u1' });
    const { createSession } = await import('../src/sessions.mjs');
    const other = await createSession(harness, { agent }, context);
    await turn(context, faux, other, { prompt: 'second chat', answer: 'second answer here', requestId: 'u2' });

    const first = await usageState(harness, root, context, { windowOf: () => 1000 });
    const second = await usageState(harness, other, context, { windowOf: () => undefined });
    // Both conversations share one provider/model bucket; the ledger is the session's, summed once.
    assert.equal(first.models.length, 1);
    assert.equal(second.models.length, 1);
    assert.equal(first.models[0].input, second.models[0].input);
    assert.equal(first.models[0].output, second.models[0].output);
    // The window is the *conversation's* model: reported for the first, unknown (0) for the second.
    assert.equal(first.context.window, 1000);
    assert.equal(second.context.window, 0);
    assert.equal(second.context.used > 0, true);
  });
});

test('usageState without a conversation reports the session ledger and no context pressure', async () => {
  await withFauxHarness(async ({ context, harness }) => {
    const state = await usageState(harness, null, context);
    assert.deepEqual(state.models, []);
    assert.deepEqual(state.tools, []);
    assert.deepEqual(state.context, { used: 0, window: 0 });
  });
});
