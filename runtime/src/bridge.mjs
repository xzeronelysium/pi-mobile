export function createBridge(scope = globalThis.window) {
  const pending = new Map();
  const listeners = new Set();
  let sequence = 0;
  const post = value => {
    if (!scope?.AndroidNative?.postMessage) throw new Error('AndroidNative bridge unavailable');
    scope.AndroidNative.postMessage(JSON.stringify(value));
  };
  // Host-to-JS events (for example streamed `shell.output` chunks). The Kotlin host calls
  // `window.nativeEvent({...})`; one bad listener must not stop the others.
  scope.nativeEvent = input => {
    let event;
    try { event = typeof input === 'string' ? JSON.parse(input) : input; } catch { return false; }
    for (const listener of [...listeners]) {
      try { listener(event); } catch { /* ignore listener failure */ }
    }
    return true;
  };
  scope.nativeReply = input => {
    let reply;
    try { reply = typeof input === 'string' ? JSON.parse(input) : input; } catch { return false; }
    if (!reply || typeof reply.ok !== 'boolean' || !pending.has(reply.id)) return false;
    const { resolve, reject } = pending.get(reply.id);
    pending.delete(reply.id);
    if (reply.ok) resolve(reply.value);
    else {
      const error = new Error(typeof reply.error === 'string' ? reply.error : reply.error?.message ?? 'Native operation failed');
      if (reply.error?.code) error.code = reply.error.code;
      reject(error);
    }
    return true;
  };
  return {
    call(op, args = {}) {
      const id = `js-${++sequence}`;
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        try { post({ id, op, args }); } catch (error) { pending.delete(id); reject(error); }
      });
    },
    emit({ type, ...fields }) { post({ event: type, ...fields }); },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
