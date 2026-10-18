function encode(value) {
  if (value instanceof Uint8Array) {
    let text = ''; for (const byte of value) text += String.fromCharCode(byte);
    return { base64: btoa(text) };
  }
  if (typeof value === 'bigint') return value >= BigInt(Number.MIN_SAFE_INTEGER) && value <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(value) : value.toString();
  if (value === null || typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value))) return value;
  throw new TypeError('Unsupported SQLite binding');
}
function decode(value) {
  if (value && typeof value === 'object' && typeof value.base64 === 'string' && Object.keys(value).length === 1) return Uint8Array.from(atob(value.base64), c => c.charCodeAt(0));
  if (Array.isArray(value)) return value.map(decode);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k,v]) => [k, decode(v)]));
  return value;
}
export function createSqliteDatabase(call) {
  let tail = Promise.resolve(); let closed = false;
  const queue = fn => {
    if (closed) return Promise.reject(new Error('Database closed'));
    const result = tail.then(fn); tail = result.catch(() => {}); return result;
  };
  const operation = async (method, sql, params = []) => {
    const value = decode(await call(`sql.${method}`, { sql, params: params.map(encode) }));
    return method === 'get' ? value ?? undefined : method === 'all' ? value : undefined;
  };
  const executor = schedule => Object.fromEntries(['exec','run','get','all'].map(method => [method, (sql, ...params) => schedule(() => operation(method, sql, params))]));
  return {
    ...executor(queue),
    transaction(callback) {
      return queue(async () => {
        await call('sql.begin', {});
        let active = true; let txTail = Promise.resolve();
        const tx = executor(fn => {
          if (!active) return Promise.reject(new Error('Transaction inactive'));
          const result = txTail.then(fn); txTail = result; return result;
        });
        try {
          const result = await callback(tx); active = false;
          await txTail;
          await call('sql.commit', {}); return result;
        } catch (error) {
          active = false; await txTail.catch(() => {});
          try { await call('sql.rollback', {}); } catch (rollback) { throw new AggregateError([error, rollback], 'Transaction failed; rollback failed', { cause: error }); }
          throw error;
        }
      });
    },
    close() {
      if (closed) return tail;
      const result = queue(() => call('sql.close', {})); closed = true; return result;
    },
  };
}
