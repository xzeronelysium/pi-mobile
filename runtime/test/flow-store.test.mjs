// The flow store: checkpoints in the app's own SQLite through the existing bridge SQL ops, plus an
// in-memory store for tests. The store treats the state and the awaiting marker as opaque payloads:
// it validates the envelope and the JSON, never the flow's own state shape.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createNativeHost } from './support/host-native.mjs';

const stores = () => import('../src/flow-store.mjs');

const checkpoint = (over = {}) => ({
  threadId: 'thread-a',
  flow: 'demo',
  seq: 0,
  status: 'running',
  steps: [],
  state: '{"counter":0}',
  awaiting: null,
  bytes: 13,
  createdAt: 1000,
  ...over,
});

/** node:sqlite hands rows back as null-prototype objects; the assertions compare plain ones. */
const plain = rows => rows.map(row => ({ ...row }));

test('the in-memory store behaves like the sqlite one: isolated threads, newest checkpoint wins', async () => {
  const { createMemoryFlowStore } = await stores();
  const store = createMemoryFlowStore();
  await store.ready();
  await store.save(checkpoint({ seq: 0, state: '{"counter":0}', createdAt: 10 }));
  await store.save(checkpoint({ seq: 1, status: 'paused', steps: ['ask'], state: '{"counter":1}', awaiting: '{"steps":["ask"],"values":[null],"next":[]}', createdAt: 11 }));
  await store.save(checkpoint({ threadId: 'thread-b', flow: 'demo', seq: 0, state: '{"counter":99}', steps: [], bytes: 14, createdAt: 20 }));

  const latest = await store.latest('thread-a');
  assert.equal(latest.seq, 1);
  assert.equal(latest.status, 'paused');
  assert.deepEqual(latest.steps, ['ask']);
  assert.equal(latest.state, '{"counter":1}');
  assert.equal(latest.awaiting, '{"steps":["ask"],"values":[null],"next":[]}');
  assert.equal(latest.bytes, 13);
  assert.equal(latest.createdAt, 11);
  assert.equal((await store.latest('thread-b')).state, '{"counter":99}', 'threads are isolated');
  assert.equal(await store.latest('never-written'), null);

  const history = await store.history('thread-a', { limit: 10 });
  assert.deepEqual(history.map(row => row.seq), [1, 0]);
  assert.deepEqual((await store.history('thread-a', { limit: 1 })).map(row => row.seq), [1]);

  const threads = await store.threads();
  assert.deepEqual(threads.map(row => row.threadId).sort(), ['thread-a', 'thread-b']);
  assert.equal(threads.find(row => row.threadId === 'thread-a').seq, 1);

  const removed = await store.remove('thread-a');
  assert.deepEqual(removed, { threadId: 'thread-a', checkpoints: 2, threads: 1 });
  assert.equal(await store.latest('thread-a'), null);
  assert.deepEqual((await store.threads()).map(row => row.threadId), ['thread-b']);
});

test('the sqlite store creates its own tables idempotently and writes them through the sql ops', async () => {
  const { createSqliteFlowStore, FLOW_CHECKPOINT_TABLE, FLOW_THREAD_TABLE } = await stores();
  const native = createNativeHost();
  // A durable stand-in table that the flow layer must never touch.
  native.db.exec('CREATE TABLE durable_entries (id INTEGER PRIMARY KEY, record TEXT NOT NULL)');
  native.db.prepare('INSERT INTO durable_entries (id, record) VALUES (1, ?)').run('{"kind":"app.native.probe"}');

  const first = createSqliteFlowStore({ call: native.bridge.call });
  await first.save(checkpoint({ seq: 0 }));
  // A second store over the same database re-runs the schema statements: they must be idempotent.
  const second = createSqliteFlowStore({ call: native.bridge.call });
  await second.ready();
  await second.ready();
  await second.save(checkpoint({ seq: 1, status: 'done', steps: ['a'], state: '{"counter":1}' }));

  const latest = await first.latest('thread-a');
  assert.equal(latest.seq, 1, 'both stores see the same data');
  assert.deepEqual(latest.steps, ['a']);
  assert.deepEqual(await first.threads(), [{
    threadId: 'thread-a', flow: 'demo', status: 'done', seq: 1, steps: ['a'], error: null, createdAt: 1000, updatedAt: 1000,
  }]);

  const tables = native.db.prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").all().map(row => row.name);
  for (const table of [FLOW_CHECKPOINT_TABLE, FLOW_THREAD_TABLE]) assert.equal(tables.includes(table), true, table);
  assert.deepEqual(
    plain(native.db.prepare('SELECT id, record FROM durable_entries').all()),
    [{ id: 1, record: '{"kind":"app.native.probe"}' }],
    "durable's tables are untouched",
  );
  // Every statement the flow store issued went through the documented SQL ops, and none of them
  // names a durable table.
  const documented = ['sql.exec', 'sql.run', 'sql.get', 'sql.all', 'sql.begin', 'sql.commit', 'sql.rollback'];
  const ops = new Set(native.requests.filter(request => request.op.startsWith('sql.')).map(request => request.op));
  for (const op of ops) assert.equal(documented.includes(op), true, `${op} is a bridge SQL op the host implements`);
  for (const op of ['sql.exec', 'sql.run', 'sql.get', 'sql.all', 'sql.begin', 'sql.commit']) assert.equal(ops.has(op), true, op);
  for (const request of native.requests) {
    const text = String(request.args?.sql ?? '');
    for (const forbidden of ['durable_', 'conversations', 'entries', 'tasks', 'submissions', 'documents']) {
      assert.equal(text.includes(forbidden), false, `${request.op}: ${text}`);
    }
  }
  native.db.close();
});

test('the sqlite store keys checkpoints by thread and sequence, and remove clears both tables', async () => {
  const { createSqliteFlowStore } = await stores();
  const native = createNativeHost();
  const store = createSqliteFlowStore({ call: native.bridge.call });
  await store.save(checkpoint({ seq: 0, createdAt: 100 }));
  await store.save(checkpoint({ seq: 1, createdAt: 101, state: '{"counter":1}' }));
  await store.save(checkpoint({ threadId: 'thread-b', flow: 'demo', seq: 0, createdAt: 200 }));
  await store.save(checkpoint({ seq: 2, createdAt: 102, state: '{"counter":2}' }));

  assert.equal((await store.latest('thread-a')).state, '{"counter":2}');
  const rows = native.db.prepare('SELECT thread_id, seq, created_at FROM flow_checkpoints ORDER BY thread_id, seq').all();
  assert.deepEqual(plain(rows), [
    { thread_id: 'thread-a', seq: 0, created_at: 100 },
    { thread_id: 'thread-a', seq: 1, created_at: 101 },
    { thread_id: 'thread-a', seq: 2, created_at: 102 },
    { thread_id: 'thread-b', seq: 0, created_at: 200 },
  ]);
  // The thread row keeps the thread's own creation time, not the newest checkpoint's.
  const threads = await store.threads();
  assert.equal(threads.find(row => row.threadId === 'thread-a').createdAt, 100);
  assert.equal(threads.find(row => row.threadId === 'thread-a').updatedAt, 102);

  assert.deepEqual(await store.remove('thread-a'), { threadId: 'thread-a', checkpoints: 3, threads: 1 });
  assert.equal(native.db.prepare('SELECT COUNT(*) AS n FROM flow_checkpoints').get().n, 1);
  assert.equal(native.db.prepare('SELECT COUNT(*) AS n FROM flow_threads').get().n, 1);
  assert.equal(await store.latest('thread-a'), null);
  assert.equal((await store.latest('thread-b')).seq, 0);
  native.db.close();
});

test('a failed run is recorded on the boundary it failed at, with the reason kept', async () => {
  const { createSqliteFlowStore } = await stores();
  const native = createNativeHost();
  const store = createSqliteFlowStore({ call: native.bridge.call });
  await store.save(checkpoint({ seq: 0, createdAt: 100 }));
  const marked = await store.fail({
    threadId: 'thread-a',
    seq: 0,
    error: '{"step":"bad","code":"flow_step_failed","message":"boom"}',
    updatedAt: 300,
  });
  assert.deepEqual(marked, { threadId: 'thread-a', seq: 0, marked: true });
  const latest = await store.latest('thread-a');
  assert.equal(latest.status, 'failed');
  assert.equal(latest.awaiting, null);
  assert.equal(latest.error, '{"step":"bad","code":"flow_step_failed","message":"boom"}');
  assert.equal((await store.threads())[0].error, '{"step":"bad","code":"flow_step_failed","message":"boom"}');
  assert.equal((await store.threads())[0].updatedAt, 300);
  // A boundary that was never written is reported as not marked, never invented.
  assert.deepEqual(await store.fail({ threadId: 'thread-a', seq: 7, error: '{"step":"x"}' }), { threadId: 'thread-a', seq: 7, marked: false });
  native.db.close();
});

test('a corrupt checkpoint is surfaced with its thread and sequence, never silently ignored', async () => {
  const { createSqliteFlowStore } = await stores();
  const native = createNativeHost();
  const store = createSqliteFlowStore({ call: native.bridge.call });
  await store.save(checkpoint({ seq: 0 }));
  native.db.prepare("UPDATE flow_checkpoints SET state = '{not json' WHERE thread_id = 'thread-a'").run();
  await assert.rejects(store.latest('thread-a'), error => {
    assert.equal(error.code, 'flow_corrupt_checkpoint');
    assert.equal(error.details.threadId, 'thread-a');
    assert.equal(error.details.field, 'state');
    return true;
  });

  await store.save(checkpoint({ seq: 1, state: '{"counter":1}' }));
  native.db.prepare("UPDATE flow_checkpoints SET steps = 'nonsense' WHERE thread_id = 'thread-a' AND seq = 1").run();
  await assert.rejects(store.latest('thread-a'), error => error.code === 'flow_corrupt_checkpoint' && error.details.field === 'steps');

  native.db.prepare("UPDATE flow_checkpoints SET steps = '[]', status = 'finished' WHERE thread_id = 'thread-a' AND seq = 1").run();
  await assert.rejects(store.latest('thread-a'), error => error.code === 'flow_corrupt_checkpoint' && error.details.field === 'status');

  native.db.prepare("UPDATE flow_checkpoints SET status = 'done' WHERE thread_id = 'thread-a' AND seq = 1").run();
  assert.equal((await store.latest('thread-a')).status, 'done');

  native.db.prepare("INSERT INTO flow_threads (thread_id, flow, status, seq, steps, created_at, updated_at, error) VALUES ('thread-z', 'demo', 'done', 0, 'not json', 1, 1, NULL)").run();
  await assert.rejects(store.threads(), error => error.code === 'flow_corrupt_checkpoint' && error.details.threadId === 'thread-z');

  native.db.prepare('DELETE FROM flow_threads WHERE thread_id = ?').run('thread-z');
  await assert.rejects(store.save(checkpoint({ seq: -1 })), error => error.code === 'flow_invalid_checkpoint');
  await assert.rejects(store.save(checkpoint({ state: '' })), error => error.code === 'flow_invalid_checkpoint');
  native.db.close();
});

test('a bridge failure reaches the caller as a coded store error with its cause', async () => {
  const { createSqliteFlowStore } = await stores();
  const failure = new Error('no such table');
  failure.code = 'sqlite_error';
  const store = createSqliteFlowStore({ call: async (op, args) => {
    if (op === 'sql.exec') return null;
    if (op === 'sql.get') return null;
    throw failure;
  } });
  await assert.rejects(store.save(checkpoint()), error => {
    assert.equal(error.code, 'flow_store_failed');
    assert.equal(error.cause, failure);
    assert.equal(error.details.threadId, 'thread-a');
    return true;
  });
});
