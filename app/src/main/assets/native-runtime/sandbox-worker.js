// The sandbox Worker. It has no Android bridge: reads/writes are RPC messages to the trusted page,
// which checks them against the run's declared inputs and outputs. A JS run evaluates its body in
// a Function; a Python run boots Pyodide in this same Worker, installs the declared PyPI pins with
// micropip, and executes the body inside the interpreter. Neither can see the Android bridge.
let sequence = 0;
const pending = new Map();
let logs = [];
let outputs = [];

const post = (value, transfer) => self.postMessage(value, transfer);
const call = (type, payload) => new Promise((resolve, reject) => {
  const id = ++sequence;
  pending.set(id, { resolve, reject });
  post({ type, id, ...payload });
});

const log = (level, args) => {
  if (logs.length >= 200) return;
  const text = args.map(value => {
    if (typeof value === 'string') return value;
    if (value instanceof Error) return value.stack || value.message || String(value);
    try { return JSON.stringify(value); } catch { return String(value); }
  }).join(' ');
  logs.push(`${level}: ${text}`);
  post({ type: 'log', level, text });
};

const sandboxConsole = {
  log: (...args) => log('log', args),
  info: (...args) => log('info', args),
  debug: (...args) => log('debug', args),
  warn: (...args) => log('warn', args),
  error: (...args) => log('error', args),
};

try { self.console = sandboxConsole; } catch { /* ignore */ }

const decode = base64 => {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
};

const encode = bytes => {
  let binary = '';
  const windowSize = 0x8000;
  for (let index = 0; index < bytes.length; index += windowSize) {
    binary += String.fromCharCode.apply(null, bytes.subarray(index, index + windowSize));
  }
  return btoa(binary);
};

const bytesOf = async data => {
  if (typeof data === 'string') return new TextEncoder().encode(data);
  if (data instanceof Uint8Array) return data;
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  // A Python `bytes` can arrive as an unconverted PyProxy when it crosses the JS boundary inside
  // a Pyodide call; `toJs` applies the interpreter's own conversion table, so a bytes proxy
  // becomes a real Uint8Array here rather than being repr'd into the output file.
  if (data && typeof data === 'object' && typeof data.toJs === 'function') {
    const converted = data.toJs();
    if (converted instanceof Uint8Array) return converted;
  }
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  if (data && typeof data.arrayBuffer === 'function') return new Uint8Array(await data.arrayBuffer());
  throw new Error('piWrite data must be bytes, a typed array or an encoded string');
};

const safeResult = value => {
  if (value === undefined) return null;
  try {
    const encoded = JSON.stringify(value);
    return encoded === undefined ? String(value) : JSON.parse(encoded);
  } catch {
    return String(value);
  }
};

const response = (id, ok, value) => {
  const entry = pending.get(id);
  if (!entry) return;
  pending.delete(id);
  if (ok) entry.resolve(value);
  else entry.reject(new Error(typeof value === 'string' ? value : 'Sandbox RPC failed'));
};

// The failure shape the tools read: a phase (`boot` = interpreter/input staging, `install` =
// package or module loading, `execution` = the body), a user-facing type and message, and the
// user-code line when it can be proven. Every wrapper the run goes through adds exactly one line
// before the body's first line, so the user line is a frame's line minus that prefix.
const FUNCTION_PREFIX_LINES = 1;
const MAX_TRACE_CHARS = 4_000;

const traceOf = error => {
  const text = String((error && (error.stack || error.message)) || error);
  if (text.length <= MAX_TRACE_CHARS) return text;
  return `${text.slice(0, 500)}\n…[trace truncated]…\n${text.slice(text.length - 3_000)}`;
};

const pyErrorOf = trace => {
  // Chromium appends its own frames after the traceback; everything Python has to say lives
  // before the first ` at ` line.
  const pythonLines = String(trace).split('\n');
  const firstJsFrame = pythonLines.findIndex(line => /^\s+at /.test(line));
  const lines = (firstJsFrame === -1 ? pythonLines : pythonLines.slice(0, firstJsFrame));
  // The last `<exec>` frame inside the wrapped body is the user line; wrapper plumbing frames
  // (`_pyodide`, `<module>`, `__pi_result__`) carry no user code and are ignored.
  let userLine = null;
  for (const line of lines) {
    const match = /File "<exec>", line (\d+), in __pi_main__/.exec(line);
    if (match) userLine = Number(match[1]) - FUNCTION_PREFIX_LINES;
  }
  const last = [...lines].reverse().find(line => line.trim() !== '') ?? '';
  const typeMatch = /^([\w.]+(?:Error|Exception|Exit|Interrupt))\b:?\s*(.*)$/.exec(last);
  if (typeMatch) {
    return { errorType: typeMatch[1], message: typeMatch[2], userLine };
  }
  if (last.startsWith('pyodide.ffi.JsException')) {
    return { errorType: 'JsException', message: last.replace(/^pyodide\.ffi\.JsException: ?/, ''), userLine };
  }
  return { errorType: 'PythonError', message: last, userLine };
};

const runPython = async (config, piInputs, piWrite) => {
  let phase = 'boot';
  try {
    const pyodideModule = await import(String(config.pyodide?.url ?? ''));
    const py = await pyodideModule.loadPyodide({ indexURL: String(config.pyodide?.indexUrl ?? '') });
    // print() and tracebacks are the Python run's window out; stdout is content, stderr is error-ish.
    py.setStdout({ batched: text => log('log', [text]) });
    py.setStderr({ batched: text => log('error', [text]) });
    phase = 'install';
    const pins = config.imports ?? [];
    if (pins.length > 0) {
      // micropip is in the distribution but not preinstalled in 0.26's default set; loadPackage
      // fetches it from the pinned indexURL itself, before any package can ask for PyPI.
      await py.loadPackage('micropip');
      const micropip = py.pyimport('micropip');
      log('log', [`micropip installing: ${pins.join(', ')}`]);
      await micropip.install(pins);
    }
    phase = 'execution';
    // Declared state crosses as real Python values: input files become memoryviews (bytes via
    // .tobytes()), the paths and declared outputs are lists, and piWrite is the same narrow writer
    // the JS body uses — Python-side it is awaited like any async function and takes bytes.
    py.globals.set('piInputs', py.toPy(piInputs));
    py.globals.set('piInputPaths', py.toPy(config.inputs ?? []));
    py.globals.set('piOutputs', py.toPy(config.outputs ?? []));
    py.globals.set('piWrite', piWrite);
    // The body is dedented and wrapped, so `await`, `return` and correct indentation all work on a
    // body the model wrote flat; the result is forced through json.dumps, which is what makes any
    // Python value JSON-safe (falling back to str per value).
    const lines = String(config.code).replace(/\r\n?/g, '\n').split('\n');
    const indents = lines.filter(line => line.trim() !== '').map(line => line.match(/^ */)[0].length);
    const pad = indents.length > 0 ? Math.min(...indents) : 0;
    const python =
      `async def __pi_main__():\n`
      + lines.map(line => (line.trim() === '' ? '' : `    ${line.slice(pad)}`)).join('\n')
      + `\n__pi_result__ = await __pi_main__()\n`
      + `import json as __pi_json__\n`
      + `__pi_result_json__ = __pi_json__.dumps(__pi_result__, default=str)\n`
      + `__pi_result_json__\n`;
    const value = await py.runPythonAsync(python);
    // The interpreter hands back the dumps'd JSON as a string; parse it so the report carries the
    // real JSON value (a number, a string, an object), exactly like a JS run's result does.
    let parsed = value;
    if (typeof value === 'string' && value.trim() !== '') {
      try { parsed = JSON.parse(value); } catch { /* report the string as-is */ }
    }
    post({ type: 'result', result: parsed === undefined ? null : parsed, outputs });
  } catch (error) {
    const trace = traceOf(error);
    const parsed = pyErrorOf(trace);
    // A micropip failure reports as the phase it was in, with the resolver's own type; a user
    // error keeps the phase but names the user line if the traceback proves one.
    post({
      type: 'error',
      phase: phase === 'boot' ? 'boot' : phase,
      errorType: parsed.errorType,
      message: parsed.message,
      userLine: parsed.userLine,
      trace,
      logs,
    });
  }
};

self.onmessage = async event => {
  const message = event.data || {};
  if (message.type === 'response') {
    response(message.id, message.ok === true, message.value);
    return;
  }
  if (message.type !== 'run') return;
  const config = message.config || {};
  logs = [];
  outputs = [];
  try {
    const piInputs = {};
    for (const path of config.inputs ?? []) {
      const base64 = await call('read', { path });
      if (typeof base64 !== 'string') throw new Error(`Input is not readable or exceeds the host limit: ${path}`);
      piInputs[path] = decode(base64);
    }

    const piWrite = async (path, data) => {
      if (!(config.outputs ?? []).includes(path)) {
        try { log('warn', [`piWrite miss type=${typeof path} repr=${JSON.stringify(path)} declared=${JSON.stringify(config.outputs)}`]); } catch { /* logging must not mask the refusal */ }
        throw new Error(`Output path was not declared: ${path}`);
      }
      const bytes = await bytesOf(data);
      const ok = await call('write', { path, base64: encode(bytes) });
      if (!ok) throw new Error(`Output could not be written or exceeds the host limit: ${path}`);
      if (!outputs.includes(path)) outputs.push(path);
    };

    if (config.kind === 'py') {
      await runPython(config, piInputs, piWrite);
      return;
    }

    let phase = 'install';
    const piImports = {};
    for (const [name, url] of Object.entries(config.imports ?? {})) {
      try {
        piImports[name] = await import(url);
      } catch (error) {
        throw new Error(`Package import failed: ${name}: ${error && (error.stack || error.message) || error}`);
      }
    }

    // Declared workspace modules, served by the host page as real ESM under this run's own URL
    // space. A module may import another declared one with a plain relative specifier; an undeclared
    // one 404s, which surfaces here as a named module failure rather than a bare syntax error.
    const piModules = {};
    for (const [modulePath, moduleUrl] of Object.entries(config.modules ?? {})) {
      try {
        piModules[modulePath] = await import(moduleUrl);
      } catch (error) {
        throw new Error(`Workspace module failed to load: ${modulePath}: ${error && (error.stack || error.message) || error}`);
      }
    }

    phase = 'execution';
    const body = `return (async () => {\n${config.code}\n})();`;
    const execute = new Function(
      'piModules', 'piImports', 'piInputs', 'piInputPaths', 'piWrite', 'piOutputs', 'console',
      'TextDecoder', 'TextEncoder', 'atob', 'btoa',
      body,
    );
    const result = await execute(
      piModules,
      piImports,
      piInputs,
      config.inputs ?? [],
      piWrite,
      config.outputs ?? [],
      sandboxConsole,
      TextDecoder,
      TextEncoder,
      atob,
      btoa,
    );
    post({ type: 'result', result: safeResult(result), outputs });
  } catch (error) {
    const trace = traceOf(error);
    const head = trace.split('\n', 1)[0] ?? 'The run failed';
    const typeMatch = /^([A-Za-z$][\w$]*)\b:?\s*(.*)$/.exec(head);
    // Input staging (the reads before any code) is boot, not execution; a name a package or module
    // load already attached is install and keeps the load's own message. The user line only means
    // something while the body itself was running.
    let phase = 'execution';
    let errorType = typeMatch?.[1] ?? 'Error';
    let message = typeMatch && typeMatch[2].trim() !== '' ? typeMatch[2].trim() : head;
    let userLine = null;
    if (message.startsWith('Input is not readable')) {
      phase = 'boot';
      errorType = 'InputError';
    } else if (/(?:Package import failed|Workspace module failed to load):/.test(message)) {
      phase = 'install';
      errorType = 'InstallError';
    } else {
      // Frames of the generated body read `at eval (eval at self.onmessage (…worker.js:…),
      // <anonymous>:LINE:COL)` — the constructor site and the true frame share the line; the
      // LAST number pair on the line is the frame that ran. This worker's own frames name
      // sandbox-worker.js as the caller with no trailing `<anonymous>`, and prove no user line.
      const bodyFrames = trace.split('\n')
        .filter(line => line.trim().startsWith('at '))
        .filter(line => line.includes('<anonymous>:'))
        .map(line => Number(/(\d+):(\d+)\s*\)?\s*$/.exec(line)?.[1] ?? 0));
      if (bodyFrames.length > 0) {
        // The wrapper's first line and V8's async prologue both sit before the user's line 1, and
        // their width varies with the WebView: measured once per Worker with a probe that throws
        // at a known user line (line 1), so the mapping is this engine's own, not a pinned guess.
        const offset = self.piEvalOffset ?? (self.piEvalOffset = await (async () => {
          const probe = new Function('return (async () => {\nthrow new Error("pi-calibrate");\n})();');
          const probeError = await probe().then(() => null, probeError => probeError);
          const probeFrame = String(probeError?.stack ?? '')
            .split('\n')
            .filter(line => line.includes('<anonymous>:'))
            .map(line => Number(/(\d+):(\d+)\s*\)?\s*$/.exec(line)?.[1] ?? 0))[0];
          // The probe throws at its own user line 1; whatever frame it earned IS that line's width.
          return Number.isFinite(probeFrame) && probeFrame > 0 ? probeFrame - 1 : 0;
        })());
        const frameLine = bodyFrames[0];
        userLine = frameLine - offset > 0 ? frameLine - offset : null;
      }
    }
    post({ type: 'error', phase, errorType, message, userLine, trace, logs });
  }
};
