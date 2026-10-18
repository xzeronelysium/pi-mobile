// Where a flow's checkpoints live.
//
// Two implementations behind one small interface: an in-memory store for tests, and a SQLite store
// that writes the SAME database the durable engine already uses, through the existing bridge SQL ops
// (`sql.exec`/`sql.run`/`sql.get`/`sql.all` + `sql.begin`/`sql.commit`/`sql.rollback`, implemented in
// `backend/NativeStorage.kt`). The store reuses the exact call shape the durable storage uses — the
// same `createSqliteDatabase` facade — because the host has ONE SQLite connection and ONE serial
// database thread: `sql.begin` refuses a second transaction, so every statement has to be ordered
// through the same queue. Sharing the facade is what makes a checkpoint write and a durable commit
// interleave safely instead of colliding.
//
// The store owns two tables of its own, created idempotently, and never touches durable's. It treats
// the serialized state and the awaiting marker as opaque payloads: it validates the envelope (a
// thread, a sequence, a known status, JSON text) and never the flow's own state shape, which only
// the engine can judge. What it refuses to do is hand back a row it cannot vouch for: a corrupt
// checkpoint is a coded error, never a silently skipped one.

import { FLOW_STATUSES } from './flow.mjs';
import { createSqliteDatabase } from './sqlite.mjs';

export const FLOW_CHECKPOINT_TABLE = 'flow_checkpoints';
export const FLOW_THREAD_TABLE = 'flow_threads';

/**
 * The schema, one statement per call. `NativeStorage.executeStatements` splits on `;` and refuses
 * quoted multi-statement batches, and Android 7 (this app's minSdk) ships a SQLite that predates
 * both `STRICT` and upsert (`ON CONFLICT … DO UPDATE`), so every statement here is plain and
 * semicolon-free. `CREATE TABLE IF NOT EXISTS` is what makes running them more than once harmless.
 */
export const FLOW_SCHEMA = Object.freeze([
  `CREATE TABLE IF NOT EXISTS ${FLOW_CHECKPOINT_TABLE} (thread_id TEXT NOT NULL, seq INTEGER NOT NULL, flow TEXT NOT NULL, status TEXT NOT NULL, steps TEXT NOT NULL, state TEXT NOT NULL, awaiting TEXT, bytes INTEGER NOT NULL, error TEXT, created_at INTEGER NOT NULL, PRIMARY KEY (thread_id, seq))`,
  `CREATE TABLE IF NOT EXISTS ${FLOW_THREAD_TABLE} (thread_id TEXT PRIMARY KEY NOT NULL, flow TEXT NOT NULL, status TEXT NOT NULL, seq INTEGER NOT NULL, steps TEXT NOT NULL, error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
]);

export class FlowStoreError extends Error {
  constructor(code, message, details = {}, cause) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'FlowStoreError';
    this.code = code;
    this.details = details;
  }
}

const jsonText = (value, refuse) => {
  if (typeof value !== 'string' || value.trim() === '') throw refuse();
  try {
    JSON.parse(value);
  } catch (cause) {
    throw Object.assign(refuse(), { cause });
  }
  return value;
};

const stepsOf = (value, refuse) => {
  let list = value;
  if (typeof value === 'string') {
    try {
      list = JSON.parse(value);
    } catch (cause) {
      throw Object.assign(refuse(), { cause });
    }
  }
  if (!Array.isArray(list) || list.some(entry => typeof entry !== 'string' || entry === '')) throw refuse();
  return [...list];
};

/**
 * The one place a checkpoint's envelope is validated, for both implementations. `code` is
 * `flow_invalid_checkpoint` on the way in (the caller is wrong) and `flow_corrupt_checkpoint` on the
 * way out (the row is).
 */
function normalizeCheckpoint(raw, code) {
  const threadId = typeof raw?.threadId === 'string' && raw.threadId.trim() !== '' ? raw.threadId.trim() : null;
  const seq = Number.isSafeInteger(raw?.seq) ? raw.seq : null;
  const refuse = (field, reason) => new FlowStoreError(code, `flow checkpoint ${threadId ?? '(no thread id)'}@${seq ?? '(no sequence)'}: ${reason}`, { threadId, seq, field });
  if (threadId === null) throw refuse('threadId', 'a checkpoint needs a non-empty thread id');
  if (seq === null || seq < 0) throw refuse('seq', 'the sequence must be a whole number of completed supersteps, at least 0');
  const flow = typeof raw.flow === 'string' && raw.flow.trim() !== '' ? raw.flow.trim() : null;
  if (flow === null) throw refuse('flow', 'a checkpoint needs the name of the flow it belongs to');
  if (!FLOW_STATUSES.includes(raw.status)) throw refuse('status', `the status ${JSON.stringify(raw.status ?? null)} is not one of ${FLOW_STATUSES.join(', ')}`);
  const steps = stepsOf(raw.steps, () => refuse('steps', 'the steps of a boundary are a JSON array of step names'));
  const state = jsonText(raw.state, () => refuse('state', 'the state must be the JSON text of the run state'));
  const awaiting = raw.awaiting === null || raw.awaiting === undefined ? null : jsonText(raw.awaiting, () => refuse('awaiting', 'the awaiting marker must be JSON text or null'));
  const error = raw.error === null || raw.error === undefined
    ? null
    : (typeof raw.error === 'string' && raw.error !== '' ? raw.error : refuse('error', 'the failure marker must be non-empty text or null'));
  if (!Number.isSafeInteger(raw.bytes) || raw.bytes < 0) throw refuse('bytes', 'the serialized state needs a non-negative byte count');
  if (!Number.isSafeInteger(raw.createdAt) || raw.createdAt < 0) throw refuse('createdAt', 'a checkpoint needs a timestamp');
  return { threadId, flow, seq, status: raw.status, steps, state, awaiting, bytes: raw.bytes, error, createdAt: raw.createdAt };
}

const copy = row => (row === null ? null : { ...row, steps: [...row.steps] });

/** A store that keeps everything in process memory; for tests, and for a caller that wants no disk. */
export function createMemoryFlowStore() {
  const threads = new Map();
  const checkpoints = new Map();

  const fail = (request = {}) => {
    const threadId = typeof request.threadId === 'string' && request.threadId.trim() !== '' ? request.threadId.trim() : null;
    if (threadId === null || !Number.isSafeInteger(request.seq) || typeof request.error !== 'string') {
      throw new FlowStoreError('flow_invalid_checkpoint', 'a failure marker needs a thread id, a sequence and the error text', { threadId, seq: Number.isSafeInteger(request.seq) ? request.seq : null, field: 'fail' });
    }
    const byThread = checkpoints.get(threadId);
    const row = byThread?.get(request.seq);
    if (row !== undefined) {
      row.status = 'failed';
      row.awaiting = null;
      row.error = request.error;
    }
    const thread = threads.get(threadId);
    if (thread !== undefined && row !== undefined) {
      thread.status = 'failed';
      thread.error = request.error;
      thread.updatedAt = Number.isSafeInteger(request.updatedAt) ? request.updatedAt : thread.updatedAt;
    }
    return { threadId, seq: request.seq, marked: row !== undefined };
  };

  return {
    kind: 'memory',
    async ready() {},
    async save(checkpoint) {
      const row = normalizeCheckpoint(checkpoint, 'flow_invalid_checkpoint');
      const existing = threads.get(row.threadId);
      threads.set(row.threadId, {
        threadId: row.threadId,
        flow: row.flow,
        status: row.status,
        seq: row.seq,
        steps: [...row.steps],
        error: row.error,
        createdAt: existing?.createdAt ?? row.createdAt,
        updatedAt: row.createdAt,
      });
      const byThread = checkpoints.get(row.threadId) ?? new Map();
      byThread.set(row.seq, { ...row });
      checkpoints.set(row.threadId, byThread);
      return copy(row);
    },
    async latest(threadId) {
      const byThread = checkpoints.get(threadId);
      if (byThread === undefined || byThread.size === 0) return null;
      return copy(byThread.get(Math.max(...byThread.keys())));
    },
    async history(threadId, options = {}) {
      const byThread = checkpoints.get(threadId);
      if (byThread === undefined) return [];
      const limit = Number.isSafeInteger(options.limit) && options.limit > 0 ? options.limit : 20;
      return [...byThread.keys()].sort((left, right) => right - left).slice(0, limit).map(seq => copy(byThread.get(seq)));
    },
    async threads() {
      return [...threads.values()]
        .map(row => ({ ...row, steps: [...row.steps] }))
        .sort((left, right) => (right.updatedAt - left.updatedAt) || (left.threadId < right.threadId ? -1 : 1));
    },
    async fail(request) { return fail(request); },
    async remove(threadId) {
      const byThread = checkpoints.get(threadId);
      const removed = { threadId, checkpoints: byThread?.size ?? 0, threads: threads.has(threadId) ? 1 : 0 };
      checkpoints.delete(threadId);
      threads.delete(threadId);
      return removed;
    },
  };
}

const SELECT_CHECKPOINT = `SELECT thread_id, seq, flow, status, steps, state, awaiting, bytes, error, created_at FROM ${FLOW_CHECKPOINT_TABLE} WHERE thread_id = ? ORDER BY seq DESC LIMIT 1`;
const SELECT_HISTORY = `SELECT thread_id, seq, flow, status, steps, state, awaiting, bytes, error, created_at FROM ${FLOW_CHECKPOINT_TABLE} WHERE thread_id = ? ORDER BY seq DESC LIMIT ?`;
const INSERT_CHECKPOINT = `INSERT OR REPLACE INTO ${FLOW_CHECKPOINT_TABLE} (thread_id, seq, flow, status, steps, state, awaiting, bytes, error, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;
const INSERT_THREAD = `INSERT OR IGNORE INTO ${FLOW_THREAD_TABLE} (thread_id, flow, status, seq, steps, error, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`;
const UPDATE_THREAD = `UPDATE ${FLOW_THREAD_TABLE} SET flow = ?, status = ?, seq = ?, steps = ?, error = ?, updated_at = ? WHERE thread_id = ?`;
const SELECT_THREADS = `SELECT thread_id, flow, status, seq, steps, error, created_at, updated_at FROM ${FLOW_THREAD_TABLE} ORDER BY updated_at DESC, thread_id ASC`;
const MARK_CHECKPOINT = `UPDATE ${FLOW_CHECKPOINT_TABLE} SET status = ?, awaiting = NULL, error = ? WHERE thread_id = ? AND seq = ?`;
const MARK_THREAD = `UPDATE ${FLOW_THREAD_TABLE} SET status = ?, error = ?, updated_at = ? WHERE thread_id = ?`;
const DELETE_CHECKPOINTS = `DELETE FROM ${FLOW_CHECKPOINT_TABLE} WHERE thread_id = ?`;
const DELETE_THREAD = `DELETE FROM ${FLOW_THREAD_TABLE} WHERE thread_id = ?`;
const CHANGES = 'SELECT changes() AS n';

/**
 * The sqlite-backed store.
 *
 * Pass `db` (a `createSqliteDatabase` facade) to share the host's serialized queue — which is what
 * the app does, because there is one connection — or `call` to build a facade of its own, which is
 * what a store-level test does.
 */
export function createSqliteFlowStore({ db, call, now = Date.now } = {}) {
  const database = db ?? (typeof call === 'function' ? createSqliteDatabase(call) : null);
  if (database === null) throw new TypeError('createSqliteFlowStore requires a database facade or a bridge call');
  let schema;

  // A bridge failure must not read like a checkpoint problem: it is wrapped once, here, with the
  // original error as the cause. `createSqliteDatabase.transaction` reports a failed BEGIN whose
  // rollback also failed as an AggregateError carrying the real cause, so unwrap that one level. An
  // error this module raised itself is already the right diagnosis and is passed through untouched.
  const wrap = (error, details) => {
    if (error instanceof FlowStoreError) return error;
    return new FlowStoreError('flow_store_failed', `Flow checkpoints could not be ${details.action}: ${error?.message ?? String(error)}`, details, error instanceof AggregateError && error.cause instanceof Error ? error.cause : error);
  };

  const ready = () => {
    if (schema === undefined) {
      schema = (async () => {
        for (const statement of FLOW_SCHEMA) await database.exec(statement);
      })().catch(error => {
        schema = undefined;
        throw wrap(error, { action: 'prepared' });
      });
    }
    return schema;
  };

  const rowOf = raw => (raw === null || raw === undefined ? null : copy(normalizeCheckpoint({
    threadId: raw.thread_id,
    flow: raw.flow,
    seq: raw.seq,
    status: raw.status,
    steps: raw.steps,
    state: raw.state,
    awaiting: raw.awaiting ?? null,
    bytes: raw.bytes,
    error: raw.error ?? null,
    createdAt: raw.created_at,
  }, 'flow_corrupt_checkpoint')));

  const threadOf = raw => ({
    threadId: raw.thread_id,
    flow: raw.flow,
    status: raw.status,
    seq: raw.seq,
    steps: stepsOf(raw.steps, () => new FlowStoreError('flow_corrupt_checkpoint', `Flow thread ${raw.thread_id} holds boundary steps that are not a JSON array of names`, { threadId: raw.thread_id, seq: raw.seq, field: 'steps' })),
    error: raw.error ?? null,
    createdAt: raw.created_at,
    updatedAt: raw.updated_at,
  });

  const changes = async transaction => {
    const value = await transaction.get(CHANGES);
    return Number.isSafeInteger(value?.n) ? value.n : 0;
  };

  return {
    kind: 'sqlite',
    ready,
    async save(checkpoint) {
      const row = normalizeCheckpoint(checkpoint, 'flow_invalid_checkpoint');
      await ready();
      try {
        // The boundary and the thread's own row move together: a thread is never left pointing at a
        // sequence that was not written. `INSERT OR IGNORE` + `UPDATE` keeps the thread's own
        // creation time, which `INSERT OR REPLACE` would overwrite on every boundary.
        await database.transaction(async transaction => {
          await transaction.run(INSERT_CHECKPOINT, row.threadId, row.seq, row.flow, row.status, JSON.stringify(row.steps), row.state, row.awaiting, row.bytes, row.error, row.createdAt);
          await transaction.run(INSERT_THREAD, row.threadId, row.flow, row.status, row.seq, JSON.stringify(row.steps), row.error, row.createdAt, row.createdAt);
          await transaction.run(UPDATE_THREAD, row.flow, row.status, row.seq, JSON.stringify(row.steps), row.error, row.createdAt, row.threadId);
        });
      } catch (error) {
        throw wrap(error, { action: 'written', threadId: row.threadId, seq: row.seq });
      }
      return copy(row);
    },
    async latest(threadId) {
      await ready();
      try {
        return rowOf(await database.get(SELECT_CHECKPOINT, threadId));
      } catch (error) {
        throw wrap(error, { action: 'read', threadId });
      }
    },
    async history(threadId, options = {}) {
      await ready();
      const limit = Number.isSafeInteger(options.limit) && options.limit > 0 ? options.limit : 20;
      try {
        const rows = await database.all(SELECT_HISTORY, threadId, limit);
        return rows.map(row => rowOf(row));
      } catch (error) {
        throw wrap(error, { action: 'listed', threadId });
      }
    },
    async threads() {
      await ready();
      try {
        return (await database.all(SELECT_THREADS)).map(threadOf);
      } catch (error) {
        throw wrap(error, { action: 'listed' });
      }
    },
    async fail(request = {}) {
      await ready();
      try {
        return await database.transaction(async transaction => {
          await transaction.run(MARK_CHECKPOINT, 'failed', request.error, request.threadId, request.seq);
          const marked = (await changes(transaction)) > 0;
          await transaction.run(MARK_THREAD, 'failed', request.error, Number.isSafeInteger(request.updatedAt) ? request.updatedAt : now(), request.threadId);
          return { threadId: request.threadId, seq: request.seq, marked };
        });
      } catch (error) {
        throw wrap(error, { action: 'marked as failed', threadId: request.threadId, seq: request.seq });
      }
    },
    async remove(threadId) {
      await ready();
      try {
        return await database.transaction(async transaction => {
          await transaction.run(DELETE_CHECKPOINTS, threadId);
          const removed = await changes(transaction);
          await transaction.run(DELETE_THREAD, threadId);
          return { threadId, checkpoints: removed, threads: await changes(transaction) };
        });
      } catch (error) {
        throw wrap(error, { action: 'removed', threadId });
      }
    },
  };
}
