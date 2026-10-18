// Reference AndroidNative bridge for tests, implemented with Node APIs.
//
// This mirrors the contract the Kotlin `NativeBridge` must satisfy: it is the
// specification-in-code for the Android side, and it lets the upstream
// ExecutionEnv conformance suite run against runtime/src/env.mjs on CI.

import { spawn } from 'node:child_process';
import { appendFile, lstat, mkdir, mkdtemp, open, readdir, realpath, rename, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve as resolvePath } from 'node:path';

function kindOf(stats) {
  if (stats.isDirectory()) return 'directory';
  if (stats.isSymbolicLink()) return 'symlink';
  return 'file';
}

function fileError(error) {
  const code = error?.code === 'ENOENT' ? 'not_found'
    : error?.code === 'EACCES' || error?.code === 'EPERM' ? 'permission_denied'
      : error?.code === 'ENOTDIR' ? 'not_directory'
        : error?.code === 'EISDIR' ? 'is_directory'
          : error?.code === 'EINVAL' || error?.code === 'ELOOP' ? 'invalid'
            : error?.code === 'ENOTSUP' ? 'not_supported'
              : 'unknown';
  const failure = new Error(error?.message ?? String(error));
  failure.code = code;
  return failure;
}

export function createNodeBridge({ root, tempRoot } = {}) {
  const subscribers = new Set();
  const runs = new Map();
  const handles = new Map();
  let tempCounter = 0;
  let handleCounter = 0;

  const emit = event => { for (const handler of [...subscribers]) handler(event); };

  const confined = path => {
    const target = resolvePath(path);
    if (root && target !== root && !target.startsWith(`${root}/`)) {
      const failure = new Error('Path escapes workspace');
      failure.code = 'permission_denied';
      throw failure;
    }
    return target;
  };

  async function statInfo(path, follow = true) {
    try {
      const stats = follow ? await stat(path) : await lstat(path);
      return { exists: true, kind: kindOf(stats), size: stats.isDirectory() ? 0 : stats.size, mtimeMs: stats.mtimeMs };
    } catch (error) {
      if (error?.code === 'ENOENT') return { exists: false, kind: 'unknown', size: 0, mtimeMs: 0 };
      throw error;
    }
  }

  async function call(op, args = {}) {
    switch (op) {
      case 'file.stat':
        return statInfo(confined(args.path), args.follow !== false);
      case 'file.open': {
        const path = confined(args.path);
        const noFollow = args.noFollow === true;
        const stats = await (noFollow ? lstat(path) : stat(path)).catch(error => { throw fileError(error); });
        if (stats.isDirectory()) throw fileError(Object.assign(new Error(`Is a directory: ${path}`), { code: 'EISDIR' }));
        if (noFollow && stats.isSymbolicLink()) throw Object.assign(new Error(`Symbolic link: ${path}`), { code: 'invalid' });
        if (!stats.isFile()) throw Object.assign(new Error(`Not a regular file: ${path}`), { code: 'invalid' });
        const handle = await open(path, 'r');
        const id = `h${++handleCounter}`;
        handles.set(id, handle);
        return { id, kind: 'file', size: stats.size, mtimeMs: stats.mtimeMs };
      }
      case 'file.readAt': {
        const handle = handles.get(args.id);
        if (!handle) throw Object.assign(new Error('Closed reader'), { code: 'invalid' });
        const buffer = Buffer.alloc(args.length);
        const { bytesRead } = await handle.read(buffer, 0, args.length, args.offset);
        return { base64: buffer.subarray(0, bytesRead).toString('base64') };
      }
      case 'file.close': {
        const handle = handles.get(args.id);
        if (handle) { await handle.close(); handles.delete(args.id); }
        return null;
      }
      case 'file.readRange': {
        const handle = await open(confined(args.path), 'r');
        try {
          const buffer = Buffer.alloc(args.length);
          const { bytesRead } = await handle.read(buffer, 0, args.length, args.offset);
          return { base64: buffer.subarray(0, bytesRead).toString('base64') };
        } finally { await handle.close(); }
      }
      case 'file.write': {
        const path = confined(args.path);
        if (typeof args.truncateTo === 'number') {
          const handle = await open(path, 'a+');
          try { await handle.truncate(args.truncateTo); } finally { await handle.close(); }
          return null;
        }
        const payload = args.base64 ? Buffer.from(args.base64, 'base64') : (args.text ?? '');
        // Writing creates missing parents; the Kotlin adapter does the same with
        // File.parentFile?.mkdirs(), and the conformance suite depends on it.
        await mkdir(dirname(path), { recursive: true });
        if (args.append) await appendFile(path, payload);
        else await writeFile(path, payload);
        return null;
      }
      case 'file.flush': {
        const handle = await open(confined(args.path), 'r');
        try { await handle.sync(); } finally { await handle.close(); }
        return null;
      }
      case 'file.list': {
        const path = confined(args.path);
        const entries = [];
        for (const name of await readdir(path)) {
          const info = await statInfo(join(path, name));
          if (info.exists) entries.push({ name, kind: info.kind, size: info.size, mtimeMs: info.mtimeMs });
        }
        return { entries };
      }
      case 'file.mkdir':
        await mkdir(confined(args.path), { recursive: args.recursive === true });
        return null;
      case 'file.remove':
        await rm(confined(args.path), { recursive: args.recursive === true, force: args.force === true });
        return null;
      case 'file.rename':
        await rename(confined(args.source), confined(args.destination));
        return null;
      case 'file.canonical': {
        const path = confined(args.path);
        try { return { path: await realpath(path) }; }
        catch { return { path }; }
      }
      case 'file.tempDir':
        return { path: await mkdtemp(join(tempRoot ?? tmpdir(), args.prefix ?? 'pi-')) };
      case 'file.tempFile': {
        const path = join(tempRoot ?? tmpdir(), `${args.prefix ?? 'pi-'}${++tempCounter}${args.suffix ?? ''}`);
        await writeFile(path, '');
        return { path };
      }
      case 'file.cleanup':
        return null;
      case 'shell.exec':
        return runShell(args, emit);
      case 'shell.cancel': {
        runs.get(args.requestId)?.cancel();
        return { cancelled: true };
      }
      default:
        throw new Error(`Unsupported op ${op}`);
    }
  }

  function runShell(args, emitEvent) {
    return new Promise((resolveRequest, rejectRequest) => {
      const isArgv = Array.isArray(args.command);
      if (isArgv && args.command.length === 0) {
        rejectRequest(Object.assign(new Error('Empty argv'), { code: 'spawn_error' }));
        return;
      }
      const command = isArgv ? args.command[0] : '/bin/sh';
      const argv = isArgv ? args.command.slice(1) : ['-c', String(args.command)];
      let timedOut = false;
      let cancelled = false;

      let child;
      try {
        child = spawn(command, argv, {
          cwd: args.cwd,
          env: args.inheritEnv ? { ...process.env, ...(args.env ?? {}) } : (args.env ?? {}),
        });
      } catch (error) {
        rejectRequest(error);
        return;
      }

      runs.set(args.requestId, {
        child,
        cancel() { cancelled = true; child.kill('SIGKILL'); },
      });

      const forward = (stream, data) => emitEvent({
        event: 'shell.output', requestId: args.requestId, stream, base64: data.toString('base64'),
      });
      child.stdout.on('data', data => forward('stdout', data));
      child.stderr.on('data', data => forward('stderr', data));

      const timer = typeof args.timeoutMs === 'number' && args.timeoutMs > 0
        ? setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, args.timeoutMs)
        : undefined;

      const settle = exitCode => {
        clearTimeout(timer);
        runs.delete(args.requestId);
        resolveRequest({ exitCode, timedOut, cancelled });
      };
      child.on('error', error => { clearTimeout(timer); runs.delete(args.requestId); rejectRequest(error); });
      child.on('close', code => settle(timedOut || cancelled ? null : (code ?? 0)));
    });
  }

  return {
    call,
    root,
    subscribe: handler => { subscribers.add(handler); return () => subscribers.delete(handler); },
    close: async () => {
      for (const run of runs.values()) run.cancel();
      runs.clear();
      for (const handle of handles.values()) await handle.close().catch(() => {});
      handles.clear();
    },
  };
}
