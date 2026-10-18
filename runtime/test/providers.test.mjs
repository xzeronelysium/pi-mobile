// The model-picker feed: `modelRows` is what the Model sheet renders, so it must carry the
// catalog metadata (context window, pricing, input modalities, thinking levels) that the picker
// shows — and it must never drop a row, because a dropped row is a model the user cannot choose.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createModels } from '@earendil-works/pi-ai/models';
import { installProviders, modelRows } from '../src/providers.mjs';

const withCatalog = () => {
  const models = createModels();
  installProviders(models);
  return models;
};

test('modelRows carries every catalog field the Model sheet renders', () => {
  const models = withCatalog();
  const source = models.getModelsOfType('chat', 'openrouter');
  const rows = modelRows(source);
  assert.equal(rows.length, source.length);
  const model = source.find(entry => entry.id === 'anthropic/claude-fable-5');
  const row = rows.find(entry => entry.id === 'anthropic/claude-fable-5');
  assert.equal(row.provider, 'openrouter');
  assert.equal(row.name, model.name);
  assert.equal(row.type, 'chat');
  assert.equal(row.api, model.api);
  assert.equal(row.contextWindow, model.contextWindow);
  assert.equal(row.maxTokens, model.maxTokens);
  assert.equal(row.reasoning, true);
  assert.deepEqual(row.input, ['text', 'image']);
  assert.deepEqual(row.cost, model.cost);
  // `thinkingLevelMap` marks unsupported levels `null`; the picker offers only the real ones.
  assert.deepEqual(row.thinkingLevels, ['low', 'medium', 'high', 'xhigh', 'max']);
});

test('modelRows keeps every model of every provider and type reachable', () => {
  const models = withCatalog();
  const providers = models.getProviders().map(provider => provider.id);
  for (const type of ['chat', 'image', 'classifier']) {
    for (const provider of providers) {
      const source = models.getModelsOfType(type, provider);
      const rows = modelRows(source);
      assert.deepEqual(rows.map(row => row.id), source.map(model => model.id), `${provider}/${type}`);
      for (const row of rows) {
        assert.equal(row.provider, provider);
        assert.equal(row.type, type);
        assert.equal(typeof row.name, 'string');
        assert.notEqual(row.name, '');
      }
    }
  }
});

test('a model without a thinkingLevelMap reports no invented levels', () => {
  const models = withCatalog();
  const plain = models.getAllModels().find(model => model.thinkingLevelMap === undefined);
  assert.ok(plain, 'the catalog is expected to contain a model without an explicit level map');
  const [row] = modelRows([plain]);
  assert.equal(row.thinkingLevels, undefined);
  // Metadata the model genuinely lacks stays absent rather than becoming a zero.
  const image = modelRows(models.getModelsOfType('image', 'openrouter'))[0];
  assert.equal(typeof image.contextWindow, 'undefined');
  assert.deepEqual(image.output, ['image']);
  // …and a model that does carry it is not stripped by the same rule.
  const classifier = modelRows(models.getModelsOfType('classifier', 'openai'))[0];
  assert.equal(classifier.contextWindow > 0, true);
  assert.equal(classifier.type, 'classifier');
});
