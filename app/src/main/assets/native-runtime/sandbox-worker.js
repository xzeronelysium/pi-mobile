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

const runPython = async (config, piInputs, piWrite) => {
  const pyodideModule = await import(String(config.pyodide?.url ?? ''));
  const py = await pyodideModule.loadPyodide({ indexURL: String(config.pyodide?.indexUrl ?? '') });
  // print() and tracebacks are the Python run's window out; stdout is content, stderr is error-ish.
  py.setStdout({ batched: text => log('log', [text]) });
  py.setStderr({ batched: text => log('error', [text]) });
  const pins = config.imports ?? [];
  if (pins.length > 0) {
    // micropip is in the distribution but not preinstalled in 0.26's default set; loadPackage
    // fetches it from the pinned indexURL itself, before any package can ask for PyPI.
    await py.loadPackage('micropip');
    const micropip = py.pyimport('micropip');
    log('log', [`micropip installing: ${pins.join(', ')}`]);
    await micropip.install(pins);
  }
  // Declared state crosses as real Python values: input files become memoryviews (bytes via
  // .tobytes()), the paths and declared outputs are lists, and piWrite is the same narrow writer
  // the JS body uses — Python-side it is awaited like any async function and takes bytes.
  py.globals.set('piInputs', py.toPy(piInputs));
  py.globals.set('piInputPaths', py.toPy(config.inputs ?? []));
  py.globals.set('piOutputs', py.toPy(config.outputs ?? []));
  py.globals.set('piWrite', piWrite);
  // The body is dedented and wrapped, so `await`, `return` and correct indentation all work on a
  // body the model wrote flat; the result is forced through json.dumps, which is what makes any
  // Python value JSON-safe (falling back to str per value). The final expression is what Pyodide
  // hands back, and the worker reports it verbatim — already a JSON string.
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
    post({ type: 'error', error: String(error && (error.stack || error.message) || error), logs });
  }
};
