import test from 'node:test';
import assert from 'node:assert/strict';
import { basename, dirname, join, normalize, resolve } from '../src/path.mjs';
import { createAndroidExecutionEnv } from '../src/env.mjs';

// ---------------------------------------------------------------------------
// Fake AndroidNative bridge: in-memory filesystem + scripted shell.
// ---------------------------------------------------------------------------

function normalizePath(path) {
  return normalize(path);
}

function createFakeNative({ shell } = {}) {
  const files = new Map();
  const dirs = new Set(['/']);
  const handles = new Map();
  const subscribers = new Set();
  const requests = [];
  const encoder = new TextEncoder();
  let handleSeq = 0;

  const emit = event => { for (const handler of subscribers) handler(event); };

  const ensureParents = path => {
    const segments = normalizePath(path).split('/').slice(1, -1);
    let current = '';
    for (const segment of segments) {
      current += `/${segment}`;
      dirs.add(current);
    }
  };

  const statOf = path => {
    const target = normalizePath(path);
    if (dirs.has(target)) return { exists: true, kind: 'directory', size: 0, mtimeMs: 1 };
    if (files.has(target)) return { exists: true, kind: 'file', size: files.get(target).length, mtimeMs: 1 };
    return { exists: false, kind: 'unknown', size: 0, mtimeMs: 0 };
  };

  const call = async (op, args = {}) => {
    requests.push({ op, args });
    switch (op) {
      case 'file.stat':
        return statOf(args.path);
      case 'file.open': {
        const bytes = files.get(normalizePath(args.path));
        if (!bytes) throw Object.assign(new Error('not found'), { code: 'not_found' });
        const id = `h${++handleSeq}`;
        handles.set(id, bytes);
        return { id, kind: 'file', size: bytes.length, mtimeMs: 1 };
      }
      case 'file.readAt': {
        const bytes = handles.get(args.id);
        if (!bytes) throw Object.assign(new Error('closed'), { code: 'invalid' });
        return { base64: Buffer.from(bytes.subarray(args.offset, args.offset + args.length)).toString('base64') };
      }
      case 'file.close': {
        handles.delete(args.id);
        return null;
      }
      case 'file.readRange': {
        const bytes = files.get(normalizePath(args.path));
        if (!bytes) throw Object.assign(new Error('not found'), { code: 'not_found' });
        return { base64: Buffer.from(bytes.subarray(args.offset, args.offset + args.length)).toString('base64') };
      }
      case 'file.write': {
        const target = normalizePath(args.path);
        ensureParents(target);
        const incoming = args.base64 ? Buffer.from(args.base64, 'base64') : Buffer.from(args.text ?? '', 'utf8');
        if (typeof args.truncateTo === 'number') {
          const existing = files.get(target) ?? new Uint8Array(0);
          const next = new Uint8Array(args.truncateTo);
          next.set(existing.subarray(0, args.truncateTo));
          files.set(target, next);
          return null;
        }
        const existing = args.append ? (files.get(target) ?? new Uint8Array(0)) : new Uint8Array(0);
        const next = new Uint8Array(existing.length + incoming.length);
        next.set(existing, 0);
        next.set(incoming, existing.length);
        files.set(target, next);
        return null;
      }
      case 'file.flush':
        return null;
      case 'file.list': {
        const target = normalizePath(args.path);
        const prefix = target === '/' ? '/' : `${target}/`;
        const names = new Set();
        for (const dir of dirs) if (dir.startsWith(prefix) && dir !== target) names.add(dir.slice(prefix.length).split('/')[0]);
        for (const file of files.keys()) if (file.startsWith(prefix)) names.add(file.slice(prefix.length).split('/')[0]);
        return { entries: [...names].map(name => ({ name, ...statOf(join(target, name)) })) };
      }
      case 'file.mkdir':
        if (args.recursive) ensureParents(`${normalizePath(args.path)}/x`);
        dirs.add(normalizePath(args.path));
        return null;
      case 'file.remove': {
        const target = normalizePath(args.path);
        files.delete(target);
        for (const key of [...files.keys()]) if (key.startsWith(`${target}/`)) files.delete(key);
        for (const dir of [...dirs]) if (dir === target || dir.startsWith(`${target}/`)) dirs.delete(dir);
        return null;
      }
      case 'file.rename': {
        const from = normalizePath(args.source);
        const to = normalizePath(args.destination);
        const bytes = files.get(from);
        if (bytes) { files.delete(from); files.set(to, bytes); }
        return null;
      }
      case 'file.canonical':
        return { path: normalizePath(args.path) };
      case 'file.tempDir': {
        const path = normalizePath(`/tmp/${args.prefix ?? 'pi-'}${dirs.size}`);
        dirs.add(path);
        return { path };
      }
      case 'file.tempFile': {
        const path = normalizePath(`/tmp/${args.prefix ?? 'pi-'}${files.size}${args.suffix ?? ''}`);
        files.set(path, new Uint8Array(0));
        return { path };
      }
      case 'file.watch':
        return { mode: 'native' };
      case 'file.unwatch':
        return null;
      case 'file.cleanup':
        return null;
      case 'shell.exec':
        if (shell) return shell(args, chunk => emit({ event: 'shell.output', requestId: args.requestId, ...chunk }));
        return { exitCode: 0, timedOut: false, cancelled: false };
      case 'shell.cancel':
        return { cancelled: true };
      default:
        throw new Error(`Unsupported op ${op}`);
    }
  };

  return {
    call,
    emit,
    requests,
    files,
    encoder,
    subscribe: handler => { subscribers.add(handler); return () => subscribers.delete(handler); },
    seed(path, content) { ensureParents(normalizePath(path)); files.set(normalizePath(path), encoder.encode(content)); },
    text(path) { return new TextDecoder().decode(files.get(normalizePath(path))); },
  };
}

function envFor(native, cwd = '/workspace') {
  return createAndroidExecutionEnv({ call: native.call, cwd, subscribe: native.subscribe });
}

const base64 = text => Buffer.from(text, 'utf8').toString('base64');

// ---------------------------------------------------------------------------

test('posix path helpers cover the cases the environment relies on', () => {
  assert.equal(normalize('/a//b/./c/../d'), '/a/b/d');
  assert.equal(normalize('a/../../b'), '../b');
  assert.equal(join('/workspace', 'src', 'main.ts'), '/workspace/src/main.ts');
  assert.equal(resolve('/workspace', 'src/x.ts'), '/workspace/src/x.ts');
  assert.equal(resolve('/workspace', '/etc/hosts'), '/etc/hosts');
  assert.equal(dirname('/workspace/src/x.ts'), '/workspace/src');
  assert.equal(basename('/workspace/src/x.ts'), 'x.ts');
});

test('file reads decode text, strip a leading BOM, and honour maxLines', async () => {
  const native = createFakeNative();
  native.seed('/workspace/note.md', '\uFEFFline one\nline two\nline three\n');
  const env = envFor(native);

  const text = await env.readTextFile('note.md');
  assert.equal(text.ok, true);
  assert.equal(text.value, 'line one\nline two\nline three\n');

  const lines = await env.readTextLines('note.md', { maxLines: 2 });
  assert.deepEqual(lines.value, ['line one', 'line two']);

  const missing = await env.readTextFile('absent.md');
  assert.equal(missing.ok, false);
  assert.equal(missing.error.code, 'not_found');
});

test('openTextLineReader reports terminators and an unterminated last line', async () => {
  const native = createFakeNative();
  native.seed('/workspace/lines.txt', 'a\nb\nlast');
  const env = envFor(native);

  const reader = await env.openTextLineReader('lines.txt');
  assert.equal(reader.ok, true);
  const seen = [];
  for (;;) {
    const line = await reader.value.readLine({});
    if (!line.value) break;
    seen.push(line.value);
  }
  assert.deepEqual(seen, [
    { text: 'a', terminated: true },
    { text: 'b', terminated: true },
    { text: 'last', terminated: false },
  ]);
});

test('writes, appends, truncates, renames, lists and pages directories', async () => {
  const native = createFakeNative();
  const env = envFor(native);

  assert.equal((await env.writeFile('out/a.txt', 'one')).ok, true);
  assert.equal((await env.appendFile('out/a.txt', '-two')).ok, true);
  assert.equal((await env.readTextFile('out/a.txt')).value, 'one-two');

  assert.equal((await env.truncateFile('out/a.txt', 3)).ok, true);
  assert.equal((await env.readTextFile('out/a.txt')).value, 'one');

  assert.equal((await env.renameFile('out/a.txt', 'out/b.txt')).ok, true);
  assert.equal((await env.exists('out/b.txt')).value, true);
  assert.equal((await env.exists('out/a.txt')).value, false);

  assert.equal((await env.createDir('out/nested/deep', { recursive: true })).ok, true);
  assert.equal((await env.fileInfo('out/b.txt')).value.name, 'b.txt');

  const listing = await env.listDir('out');
  assert.deepEqual(listing.value.map(entry => entry.name).sort(), ['b.txt', 'nested']);

  const reader = await env.openDirReader('out');
  const first = await reader.value.next(1);
  assert.equal(first.value.entries.length, 1);
  assert.equal(first.value.done, false);
  const rest = await reader.value.next(10);
  assert.equal(rest.value.done, true);

  const info = await env.fileInfo('out/nope.txt');
  assert.equal(info.ok, false);
  assert.equal(info.error.code, 'not_found');
});

test('binary readers expose positional reads and scanLines', async () => {
  const native = createFakeNative();
  native.seed('/workspace/data.txt', 'alpha\nbeta\ngamma\n');
  const env = envFor(native);

  const reader = await env.openBinaryReader('data.txt');
  assert.equal(reader.ok, true);
  const head = await reader.value.read(0, 5);
  assert.equal(new TextDecoder().decode(head.value), 'alpha');
  const info = await reader.value.info();
  assert.equal(info.value.size, 17);

  const scan = await reader.value.scanLines({ startLine: 1, endLine: 2 });
  assert.equal(scan.ok, true);
  assert.equal(scan.value.newlines, 3);
  assert.equal(new TextDecoder().decode((await env.readBinaryFile('data.txt')).value.subarray(scan.value.start, scan.value.end)), 'beta');
});

test('exec streams decoded output in arrival order and returns the exit code', async () => {
  const native = createFakeNative({
    shell: async (args, send) => {
      // Split a multi-byte character across two bridge chunks.
      const bytes = Buffer.from('héllo\n', 'utf8');
      send({ stream: 'stdout', base64: bytes.subarray(0, 2).toString('base64') });
      send({ stream: 'stdout', base64: bytes.subarray(2).toString('base64') });
      send({ stream: 'stderr', base64: base64('warn\n') });
      return { exitCode: 7, timedOut: false, cancelled: false };
    },
  });
  const env = envFor(native);

  const chunks = [];
  const result = await env.exec('echo hi', { onOutput: (text, _ctx, info) => chunks.push({ text, stream: info.stream }) }, {});
  assert.equal(result.ok, true);
  assert.equal(result.value.exitCode, 7);
  // Output arrives in decoded pieces; a character split across bridge chunks is never
  // replaced with U+FFFD, and per-stream order is preserved.
  const stdout = chunks.filter(chunk => chunk.stream === 'stdout').map(chunk => chunk.text).join('');
  const stderr = chunks.filter(chunk => chunk.stream === 'stderr').map(chunk => chunk.text).join('');
  assert.equal(stdout, 'héllo\n');
  assert.equal(stderr, 'warn\n');
  assert.ok(!stdout.includes('\uFFFD'));
  assert.ok(chunks.some(chunk => chunk.text === 'h'));
});

test('exec maps timeout and cancellation to ExecutionError codes', async () => {
  const native = createFakeNative({ shell: async () => ({ exitCode: null, timedOut: true, cancelled: false }) });
  const env = envFor(native);
  const timedOut = await env.exec('sleep 99', {}, {});
  assert.equal(timedOut.ok, false);
  assert.equal(timedOut.error.code, 'timeout');

  const cancelling = createFakeNative({
    shell: (args) => new Promise(resolve => {
      setTimeout(() => resolve({ exitCode: 1, timedOut: false, cancelled: true }), 20);
    }),
  });
  const cancelEnv = envFor(cancelling);
  const controller = new AbortController();
  const pending = cancelEnv.exec('sleep 99', {}, { abortSignal: controller.signal });
  controller.abort();
  const aborted = await pending;
  assert.equal(aborted.ok, false);
  assert.equal(aborted.error.code, 'aborted');
  assert.ok(cancelling.requests.some(request => request.op === 'shell.cancel'));
});

// --------------------------------------------------------------------------- seconds -> milliseconds
//
// Pi Durable's `bash`/`powershell` tools declare their timeout in SECONDS ("Timeout in seconds
// (optional, no default timeout)") and report a kill as "Command timed out after <n> seconds", while
// `shell.exec` takes MILLISECONDS. The adapter used to pass the number through unchanged, so
// `timeout: 12` reached the host as a 12 ms deadline and the command was killed on the spot — the
// device report "bash prints output and then the command times out". These tests pin both ends of the
// conversion, and `coding-tools.test.mjs` pins it end to end through the real bash tool.

test('a timeout asked for in seconds reaches the shell as milliseconds', async () => {
  const native = createFakeNative({ shell: async () => ({ exitCode: 0, timedOut: false, cancelled: false }) });
  const env = envFor(native);
  const result = await env.exec('echo hi', { timeout: 12 }, {});
  assert.equal(result.ok, true);
  const exec = native.requests.find(request => request.op === 'shell.exec');
  assert.equal(exec.args.timeoutMs, 12_000, 'twelve seconds is twelve thousand milliseconds');
  // And the same value in the other direction: what the host would be given as milliseconds stays
  // under the ceiling `shell.exec` applies (120 s) for every sane request.
  assert.ok(exec.args.timeoutMs <= 120_000);
});

test('a fractional timeout converts exactly instead of truncating to the wrong deadline', async () => {
  const native = createFakeNative({ shell: async () => ({ exitCode: 0, timedOut: false, cancelled: false }) });
  const env = envFor(native);
  await env.exec('echo one', { timeout: 1.5 }, {});
  await env.exec('echo two', { timeout: 0.25 }, {});
  await env.exec('echo three', { timeout: 0.0004 }, {});
  const deadlines = native.requests.filter(request => request.op === 'shell.exec').map(request => request.args.timeoutMs);
  assert.deepEqual(deadlines, [1_500, 250, 1],
    'a fraction of a second is whole milliseconds, and never a zero deadline the host would read as 1 ms');
});

test('no timeout asked for means the host default, not a tiny deadline', async () => {
  const native = createFakeNative({ shell: async () => ({ exitCode: 0, timedOut: false, cancelled: false }) });
  const env = envFor(native);
  await env.exec('echo hi', {}, {});
  const exec = native.requests.find(request => request.op === 'shell.exec');
  // What the bridge actually sends is `JSON.stringify({id, op, args})` (`src/bridge.mjs`), so an
  // undefined value is an absent key — the host then applies `ShellLimits.DEFAULT_TIMEOUT_MILLIS`.
  const wire = JSON.parse(JSON.stringify(exec.args));
  assert.equal('timeoutMs' in wire, false, 'no deadline is sent, so the host applies its own default');
});

test('a timeout the host could not use is omitted, never guessed at', async () => {
  for (const timeout of [0, -5, Number.NaN, Number.POSITIVE_INFINITY, '12', null]) {
    const native = createFakeNative({ shell: async () => ({ exitCode: 0, timedOut: false, cancelled: false }) });
    const env = envFor(native);
    await env.exec('echo hi', { timeout }, {});
    const exec = native.requests.find(request => request.op === 'shell.exec');
    const wire = JSON.parse(JSON.stringify(exec.args));
    assert.equal('timeoutMs' in wire, false, `timeout ${String(timeout)} must not become a deadline`);
  }
});


test('exec spills complete output to a temp file past the thresholds', async () => {
  const native = createFakeNative({
    shell: async (_args, send) => {
      send({ stream: 'stdout', base64: base64('one\n') });
      send({ stream: 'stdout', base64: base64('two\n') });
      send({ stream: 'stdout', base64: base64('three\n') });
      return { exitCode: 0, timedOut: false, cancelled: false };
    },
  });
  const env = envFor(native);

  const result = await env.exec('emit', { spill: { afterBytes: 1, afterLines: 1 } }, {});
  assert.equal(result.ok, true);
  assert.ok(result.value.spillPath);
  assert.equal(native.text(result.value.spillPath), 'one\ntwo\nthree\n');
});

test('watch reports changes by polling and stops after close', async () => {
  const native = createFakeNative();
  const env = envFor(native);

  const changes = [];
  const watcher = await env.watch([{ path: 'watch.txt' }], change => changes.push(change));
  assert.equal(watcher.ok, true);
  assert.equal(watcher.value.mode, 'polling');

  await env.writeFile('watch.txt', 'x');
  const deadline = Date.now() + 3000;
  while (changes.length === 0 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(changes.length > 0, 'watch reported the created file');
  assert.ok(changes[0].paths[0].endsWith('/watch.txt'));

  await watcher.value.close();
  await watcher.value.close();
  const settled = changes.length;
  await env.writeFile('watch.txt', 'much longer');
  await new Promise(resolve => setTimeout(resolve, 500));
  assert.equal(changes.length, settled, 'no reports after close');
});
