// A reference AndroidNative bridge for host-level tests: SQLite over node:sqlite, the Keystore
// vault stand-in, a shell probe and the file primitives export needs. Mirrors runtime/src/
// bridge.mjs (the envelope key is `event`) and test/support/node-bridge.mjs (file.* contract).
import { DatabaseSync } from 'node:sqlite';

export function createNativeHost({ db = new DatabaseSync(':memory:') } = {}) {
  const events = [];
  const requests = [];
  const vault = new Map();
  const files = new Map();
  /** The app-side MCP server store, as `runtime/src/mcp.mjs` persists it. */
  const mcp = { servers: [] };
  const call = async (op, args = {}) => {
    requests.push({ op, args });
    const params = (args.params ?? []).map(p => (p?.base64 ? Buffer.from(p.base64, 'base64') : p));
    if (op === 'credential.list') return [...vault].map(([provider, payload]) => ({ provider, payload }));
    if (op === 'credential.put') { vault.set(args.provider, args.payload); return null; }
    if (op === 'credential.delete') { vault.delete(args.provider); return null; }
    if (op === 'sql.exec') return db.exec(args.sql);
    if (op === 'sql.begin') return db.exec('BEGIN IMMEDIATE');
    if (op === 'sql.commit') return db.exec('COMMIT');
    if (op === 'sql.rollback') return db.exec('ROLLBACK');
    if (op === 'sql.close') return;
    if (op === 'sql.run') { db.prepare(args.sql).run(...params); return null; }
    if (op === 'sql.get') return db.prepare(args.sql).get(...params) ?? null;
    if (op === 'sql.all') return db.prepare(args.sql).all(...params);
    if (op === 'shell.exec') return { stdout: args.command === 'printf native-shell-ok' ? 'native-shell-ok' : 'pi-native-probe', stderr: '', exitCode: 0 };
    // The shell's limits, as the Kotlin host advertises them (`backend/ShellLimits.kt`): the runtime's
    // `run_script` tool reads them through this op instead of carrying a second copy.
    if (op === 'shell.capabilities') return { maxOutputBytes: 256 * 1024, defaultTimeoutMs: 30_000, maxTimeoutMs: 120_000 };
    if (op === 'file.write') { files.set(args.path, args.text ?? ''); return null; }
    if (op === 'file.read') {
      if (!files.has(args.path)) throw new Error(`No such file: ${args.path}`);
      return files.get(args.path);
    }
    if (op === 'file.stat') return { exists: files.has(args.path), kind: 'file', size: (files.get(args.path) ?? '').length, mtimeMs: 0 };
    if (op === 'file.list') {
      const prefix = `${String(args.path).replace(/\/+$/, '')}/`;
      const entries = [...files.keys()]
        .filter(path => path.startsWith(prefix))
        .map(path => ({ name: path.slice(prefix.length), kind: 'file', size: files.get(path).length, mtimeMs: 0 }));
      if (entries.length === 0 && ![...files.keys()].some(path => path.startsWith(prefix.slice(0, -1)))) {
        throw new Error(`No such file: ${args.path}`);
      }
      return { entries };
    }
    if (op === 'mcp.servers') return { servers: mcp.servers };
    if (op === 'mcp.save') { mcp.servers = args.servers ?? []; return null; }
    throw new Error(`Unexpected op ${op}`);
  };
  return {
    db,
    events,
    requests,
    vault,
    files,
    mcp,
    bridge: { call, emit: ({ type, ...fields }) => events.push({ event: type, ...fields }), subscribe: () => () => {} },
  };
}
