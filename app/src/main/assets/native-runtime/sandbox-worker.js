// The sandbox Worker. It has no Android bridge: reads/writes are RPC messages to the trusted page,
// which checks them against the run's declared inputs and outputs.
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
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  if (data && typeof data.arrayBuffer === 'function') return new Uint8Array(await data.arrayBuffer());
  return new TextEncoder().encode(String(data));
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

    const piImports = {};
    for (const [name, url] of Object.entries(config.imports ?? {})) {
      piImports[name] = await import(url);
    }

    const piWrite = async (path, data) => {
      if (!(config.outputs ?? []).includes(path)) throw new Error(`Output path was not declared: ${path}`);
      const bytes = await bytesOf(data);
      const ok = await call('write', { path, base64: encode(bytes) });
      if (!ok) throw new Error(`Output could not be written or exceeds the host limit: ${path}`);
      if (!outputs.includes(path)) outputs.push(path);
    };

    const body = `return (async () => {\n${config.code}\n})();`;
    const execute = new Function(
      'piImports', 'piInputs', 'piInputPaths', 'piWrite', 'piOutputs', 'console',
      'TextDecoder', 'TextEncoder', 'atob', 'btoa',
      body,
    );
    const result = await execute(
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
