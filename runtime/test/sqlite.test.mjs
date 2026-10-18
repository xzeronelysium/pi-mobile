import test from 'node:test';
import assert from 'node:assert/strict';

test('sqlite serializes transactions, encodes typed bindings and invalidates handles', async () => {
  const { createSqliteDatabase } = await import('../src/sqlite.mjs');
  const calls = [];
  const db = createSqliteDatabase(async (op, args) => { calls.push({ op, args }); return op === 'sql.get' ? { bytes: { base64: 'AP8=' } } : null; });
  let release;
  const gate = new Promise(r => { release = r; });
  let handle;
  const tx = db.transaction(async t => { handle = t; await t.run('insert', new Uint8Array([0,255]), 42n, 9007199254740993n); await gate; return 7; });
  const outside = db.exec('outside');
  await new Promise(r => setImmediate(r));
  assert.deepEqual(calls.map(c => c.op), ['sql.begin','sql.run']);
  assert.deepEqual(calls[1].args.params, [{ base64: 'AP8=' },42,'9007199254740993']);
  release();
  assert.equal(await tx, 7); await outside;
  assert.deepEqual(calls.map(c => c.op), ['sql.begin','sql.run','sql.commit','sql.exec']);
  await assert.rejects(handle.run('late'), /inactive/);
  assert.deepEqual((await db.get('select')).bytes, new Uint8Array([0,255]));
  await db.close(); await assert.rejects(db.exec('late'), /closed/);
});

test('sqlite preserves callback error and reports uncertain rollback distinctly', async () => {
  const { createSqliteDatabase } = await import('../src/sqlite.mjs');
  const original = new Error('callback'); const rollback = new Error('rollback');
  const ops = [];
  const db = createSqliteDatabase(async op => { ops.push(op); });
  await assert.rejects(db.transaction(async () => { throw original; }), e => e === original);
  assert.deepEqual(ops, ['sql.begin','sql.rollback']);
  const broken = createSqliteDatabase(async op => { if (op === 'sql.rollback') throw rollback; });
  await assert.rejects(broken.transaction(async () => { throw original; }), e => e instanceof AggregateError && e.errors[0] === original && e.errors[1] === rollback);
});
