// Polling watcher for hosts without a native recursive watch (Android).
//
// Pi Durable's ExecutionEnv contract for `watch` is deliberately loose: a reported path
// covers everything at or below it, calls may be spurious, and a missed change is only a
// problem while the watcher is healthy. That lets one portable implementation satisfy the
// contract over cheap `file.stat` / `file.list` primitives:
//
//   * snapshot the watched region (respecting `recursive` and `exclude`),
//   * diff the snapshot on an interval, and
//   * report the target path itself, which covers its whole subtree.
//
// This keeps the Kotlin side free of watcher code. A future native inotify/FileObserver
// implementation may replace it (reporting `mode: "native"`) as long as it passes the same
// conformance suite.

import { join } from './path.mjs';

const MAX_DEPTH = 64;
const DEFAULT_INTERVAL_MS = 200;

function exclusionFor(target) {
  const hidden = target.exclude?.hidden === true;
  const names = new Set(target.exclude?.names ?? []);
  return name => (hidden && name.startsWith('.')) || names.has(name);
}

async function record(call, path, map) {
  let stat;
  try {
    stat = await call('file.stat', { path, follow: true });
  } catch {
    map.set(path, 'error');
    return undefined;
  }
  if (!stat.exists) {
    map.set(path, 'missing');
    return undefined;
  }
  map.set(path, `${stat.kind}:${stat.size}:${stat.mtimeMs}`);
  return stat;
}

async function walk(call, path, target, excluded, map, depth) {
  if (depth > MAX_DEPTH) { map.set(path, 'depth-limit'); return; }
  const stat = await record(call, path, map);
  if (!stat || stat.kind !== 'directory') return;
  let value;
  try {
    value = await call('file.list', { path });
  } catch {
    return;
  }
  for (const entry of value.entries ?? []) {
    if (excluded(entry.name)) continue;
    const child = join(path, entry.name);
    if (target.recursive === true) await walk(call, child, target, excluded, map, depth + 1);
    else await record(call, child, map);
  }
}

function sameSnapshot(previous, next) {
  if (previous.size !== next.size) return false;
  for (const [key, value] of previous) if (next.get(key) !== value) return false;
  return true;
}

export function createPollingWatch({ call, targets, onChange, intervalMs = DEFAULT_INTERVAL_MS }) {
  const excluded = targets.map(exclusionFor);
  const snapshots = new Array(targets.length).fill(undefined);
  let closed = false;
  let timer;

  const snapshot = async index => {
    const map = new Map();
    await walk(call, targets[index].path, targets[index], excluded[index], map, 0);
    return map;
  };

  async function tick() {
    if (closed) return;
    for (let index = 0; index < targets.length; index += 1) {
      if (closed) return;
      let map;
      try {
        map = await snapshot(index);
      } catch {
        if (!closed) onChange({ overflow: true });
        continue;
      }
      const previous = snapshots[index];
      snapshots[index] = map;
      if (previous !== undefined && !sameSnapshot(previous, map)) {
        if (closed) return;
        onChange({ paths: [targets[index].path] });
      }
    }
    if (!closed) timer = setTimeout(tick, intervalMs);
  }

  const primed = (async () => {
    for (let index = 0; index < targets.length; index += 1) snapshots[index] = await snapshot(index);
  })();

  return {
    mode: 'polling',
    async ready() { await primed; if (!closed) timer = setTimeout(tick, intervalMs); },
    async close() {
      if (closed) return;
      closed = true;
      if (timer !== undefined) clearTimeout(timer);
      timer = undefined;
    },
  };
}
