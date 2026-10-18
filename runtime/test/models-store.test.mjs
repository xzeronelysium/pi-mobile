import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createSqliteDatabase } from '../src/sqlite.mjs';
import { createModelsStore } from '../src/models-store.mjs';

// The same native sql.* protocol used by the Android storage dispatcher.
function open(filename) {
  const native = new DatabaseSync(filename);
  const database = createSqliteDatabase(async (op, args) => {
    if (op === 'sql.exec') return native.exec(args.sql);
    if (op === 'sql.close') return native.close();
    const statement = native.prepare(args.sql);
    if (op === 'sql.get') return statement.get(...args.params) ?? null;
    if (op === 'sql.run') { statement.run(...args.params); return null; }
    throw new Error(`Unexpected op: ${op}`);
  });
  return { database, store: createModelsStore(database) };
}

test('native catalog storage survives database reopen with all types and cache validators', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'pi-model-catalog-'));
  const filename = path.join(directory, 'native.db');
  let current = open(filename);
  try {
    const entry = {
      models: ['chat', 'image', 'classifier'].map(type => ({
        type, id: 'same-id', provider: 'dynamic', api: `test-${type}`,
        contextWindow: 123456, thinkingLevelMap: { high: 'high', max: null },
        cost: { input: 1, output: 2 }, input: ['text', 'image'], output: [type],
      })),
      etag: '"opaque-validator"', checkedAt: 1234567890, lastModified: 1234500000,
    };
    assert.equal(await current.store.read('dynamic'), undefined);
    await current.store.write('dynamic', entry);
    await current.store.write('other', { models: [] });
    await current.database.close();
    current = open(filename);
    assert.deepEqual(await current.store.read('dynamic'), entry);
    const read = await current.store.read('dynamic');
    read.models.length = 0;
    assert.deepEqual(await current.store.read('dynamic'), entry);
    assert.deepEqual(await current.store.read('other'), { models: [] });
    await current.store.delete('dynamic');
    assert.equal(await current.store.read('dynamic'), undefined);
    assert.deepEqual(await current.store.read('other'), { models: [] });
  } finally {
    await current.database.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test('aborted catalog operations leave the stored catalog intact', async () => {
  const { database, store } = open(':memory:');
  try {
    const entry = { models: [{ id: 'kept', provider: 'dynamic' }] };
    await store.write('dynamic', entry);
    const reason = new Error('cancelled');
    const options = { signal: AbortSignal.abort(reason) };
    await assert.rejects(store.read('dynamic', options), error => error === reason);
    await assert.rejects(store.write('dynamic', { models: [] }, options), error => error === reason);
    await assert.rejects(store.delete('dynamic', options), error => error === reason);
    assert.deepEqual(await store.read('dynamic'), entry);
  } finally { await database.close(); }
});
