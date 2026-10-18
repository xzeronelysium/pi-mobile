// The trusted sandbox page. It has the narrow AndroidSandbox bridge; third-party package code runs
// in /sandbox-worker.js, which cannot see that bridge. The page mediates only declared reads/writes.
(function () {
  const bridge = window.AndroidSandbox;
  if (!bridge) return;

  let worker = null;
  let logs = [];
  let reported = false;

  const report = value => {
    if (reported) return;
    reported = true;
    try { bridge.report(JSON.stringify(value)); } catch { /* the host is gone */ }
  };

  const abort = () => {
    if (worker) {
      try { worker.terminate(); } catch { /* ignore */ }
      worker = null;
    }
  };

  window.piSandboxAbort = abort;

  window.piSandboxStart = function () {
    abort();
    reported = false;
    logs = [];
    const raw = bridge.getConfig();
    if (typeof raw !== 'string') {
      report({ ok: false, error: 'The sandbox config is unavailable' });
      return;
    }
    let config;
    try { config = JSON.parse(raw); } catch {
      report({ ok: false, error: 'The sandbox config is not valid JSON' });
      return;
    }
    worker = new Worker('/sandbox-worker.js');
    worker.onmessage = event => {
      const message = event.data || {};
      if (message.type === 'log') {
        if (logs.length < 200) logs.push(`${message.level}: ${message.text}`);
        return;
      }
      if (message.type === 'read') {
        const value = bridge.readInput(message.path);
        worker.postMessage({
          type: 'response', id: message.id,
          ok: typeof value === 'string', value: value ?? null,
        });
        return;
      }
      if (message.type === 'write') {
        const ok = bridge.writeOutput(message.path, message.base64);
        worker.postMessage({ type: 'response', id: message.id, ok, value: ok });
        return;
      }
      if (message.type === 'result') {
        report({ ok: true, result: message.result, logs, outputs: message.outputs ?? [] });
        return;
      }
      if (message.type === 'error') {
        report({ ok: false, error: message.error, logs });
      }
    };
    worker.onerror = event => {
      report({ ok: false, error: String(event.message || event), logs });
    };
    worker.postMessage({ type: 'run', config });
  };
})();
