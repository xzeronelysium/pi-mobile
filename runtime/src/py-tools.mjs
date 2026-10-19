// The agent's autonomous Python package path, mirroring runtime/src/js-tools.mjs.
//
// There is no PyPI search API (the XML-RPC search was retired), so this module has no py_search:
// discovery is the model's knowledge. `py_resolve` turns a known name into an exact version plus
// the wheel a pure-Python install would use, and `py_run` executes Python in the Kotlin-side sandbox
// on Pyodide. The sandbox is the same Worker the JS runs use: no Pi/Android bridge, only declared
// inputs/outputs, `console` and the installed packages resolved by micropip against PyPI.
//
// The lockfile is written automatically after a successful run, so a version that worked is reused
// and a later run can prove the same content was the last good one.

import { defineExtension, defineTool } from '@earendil-works/pi-durable';
import { resolve as resolvePath } from './path.mjs';

export const PY_RESOLVE_TOOL_NAME = 'py_resolve';
export const PY_RUN_TOOL_NAME = 'py_run';
export const PY_EXTENSION_NAME = 'py-sandbox';

export const PYPI_JSON = 'https://pypi.org/pypi';
/** Pinned, exactly the way the sandbox pins Mermaid and MathJax; a version bump is a source change. */
export const PYODIDE_VERSION = '0.26.2';
export const PYODIDE_CDN = `https://cdn.jsdelivr.net/pyodide/v${PYODIDE_VERSION}/full`;
/** The Python lockfile lives in the workspace; it is written by the host, not maintained by hand. */
export const PY_LOCK_PATH = '.pi-py/lock.json';
/** A Python run must not be allowed to return an unbounded result or log to the transcript. */
export const MAX_PY_CODE_BYTES = 256 * 1024;
export const MAX_PY_IMPORTS = 12;
export const MAX_FILES = 20;
export const DEFAULT_RUN_TIMEOUT_MS = 120_000;
export const MAX_RUN_TIMEOUT_MS = 180_000;

/** A failure the model should read: `code` mirrors the host's, which is never secret material. */
export class PyToolError extends Error {
  constructor(code, message, cause) {
    super(message);
    this.name = 'PyToolError';
    this.code = code;
    if (cause !== undefined) this.cause = cause;
  }
}

const abortError = message => (typeof DOMException === 'function'
  ? new DOMException(message, 'AbortError')
  : Object.assign(new Error(message), { name: 'AbortError' }));

const textOf = value => (typeof value === 'string' ? value : '');

const truncate = (text, limit = 256 * 1024) =>
  (text.length > limit ? `${text.slice(0, limit)}\n…[truncated at ${limit} characters]…` : text);

function resolveInsideWorkspace(root, target) {
  const base = typeof root === 'string' && root !== '' ? root : '/';
  const path = resolvePath(base, String(target));
  const inside = base === '/' || path === base || path.startsWith(`${base}/`);
  if (!inside) throw new PyToolError('workspace_escape', `${target} is outside the workspace (${base})`);
  return path;
}

/** One JSON request through Kotlin's transport, never the WebView's CORS-limited fetch. */
function createHttp(transport) {
  if (typeof transport !== 'function') throw new TypeError('createPyTools requires the runtime transport');
  return async function getJson(url, timeoutMs = 15_000) {
    let response;
    try {
      response = await transport(url, {
        method: 'GET',
        headers: { accept: 'application/json' },
        timeoutMs,
      });
    } catch (error) {
      throw new PyToolError('network_error', `Could not reach ${url}: ${error?.message ?? String(error)}`, error);
    }
    const body = await response.text();
    if (!response.ok) {
      const code = response.status === 404 ? 'not_found' : 'http_status';
      throw new PyToolError(code, `${url} answered HTTP ${response.status}${body ? `: ${truncate(body, 400)}` : ''}`);
    }
    try {
      return JSON.parse(body);
    } catch (error) {
      throw new PyToolError('invalid_response', `${url} did not return JSON`, error);
    }
  };
}

/**
 * Pick the file a pure-Python install would actually use out of one version's file list, and say
 * honestly what was chosen. A pure wheel installs everywhere Pyodide runs; a platform wheel belongs
 * to some other interpreter and will not; a source archive does not install in the sandbox at all.
 */
function chooseFile(entries) {
  const files = (Array.isArray(entries) ? entries : []).filter(entry => typeof entry?.filename === 'string');
  const pure = files.find(entry => /py3-none-any\.whl$/i.test(entry.filename))
    ?? files.find(entry => /py2\.py3-none-any\.whl$/i.test(entry.filename));
  if (pure !== undefined) return { file: pure, pure: true, warnings: [] };
  const platform = files.find(entry => entry.filename.endsWith('.whl'));
  if (platform !== undefined) {
    return { file: platform, pure: false, warnings: [`non-pure wheel: ${platform.filename} will not install in the sandbox`] };
  }
  const sdist = files.find(entry => entry.filename.endsWith('.tar.gz')) ?? files[0];
  if (sdist !== undefined) {
    return { file: sdist, pure: false, warnings: [`no wheel for this version: ${sdist.filename} cannot install in the sandbox`] };
  }
  return null;
}

async function pypiDoc(getJson, name, timeoutMs) {
  // Always the full document: `info.version` is the latest dist, `urls` describes that version's
  // files, and exact-version queries answer the whole document for the one version asked for.
  return getJson(`${PYPI_JSON}/${name}/json`, timeoutMs);
}

function packageSummary(doc, chosenVersion, warn) {
  const info = doc?.info ?? {};
  return {
    name: textOf(info.name),
    version: chosenVersion,
    summary: textOf(info.summary),
    license: textOf(info.license) || null,
    homepage: textOf(info.home_page) || textOf(info.project_urls?.Homepage) || null,
    requiresPython: textOf(info.requires_python) || null,
    ...warn ?? { pure: null, warnings: [] },
  };
}

export function createPyTools({ transport, call, workspaceRoot } = {}) {
  if (typeof call !== 'function') throw new TypeError('createPyTools requires the bridge call');
  const getJson = createHttp(transport);
  const rootOf = typeof workspaceRoot === 'function' ? workspaceRoot : () => (typeof workspaceRoot === 'string' ? workspaceRoot : '/');
  let nextRun = 0;

  async function resolvePackage(name, version, timeoutMs) {
    if (typeof name !== 'string' || name.trim() === '') {
      throw new PyToolError('invalid_name', 'name is required');
    }
    const doc = await pypiDoc(getJson, name.trim(), timeoutMs);
    const chosen = version === undefined || version === '' ? doc?.info?.version : String(version);
    let versioned = doc?.urls ?? [];
    if ((doc?.info?.version ?? '') !== chosen) {
      if (!doc?.releases?.[chosen]) throw new PyToolError('version_not_found', `${name} has no version ${chosen}`);
      versioned = doc.releases[chosen];
    }
    const pick = chooseFile(versioned);
    if (pick === null) throw new PyToolError('no_files', `${name} ${chosen} has no installable file entry`);
    return {
      ...packageSummary(doc, chosen),
      resolved: `${name.trim()}==${chosen}`,
      filename: pick.file.filename,
      bytes: Number.isFinite(pick.file.size) ? pick.file.size : null,
      sha256: typeof pick.file.digests?.sha256 === 'string' ? pick.file.digests.sha256 : null,
      url: pick.file.url,
      pure: pick.pure,
      warnings: pick.warnings,
    };
  }

  async function writeLock(resolved, context) {
    const path = resolveInsideWorkspace(rootOf(), PY_LOCK_PATH);
    let existing = {};
    try {
      const raw = await call('file.read', { path });
      if (typeof raw === 'string' && raw.trim() !== '') existing = JSON.parse(raw);
    } catch { /* a missing or unreadable lock is replaced by a fresh one */ }
    const packages = { ...(existing.packages ?? {}) };
    for (const entry of resolved) {
      packages[entry.name] = {
        name: entry.name,
        version: entry.version,
        source: entry.source,
        resolved: entry.resolved,
        integrity: entry.sha256,
        pure: entry.pure,
      };
    }
    const value = {
      version: 1,
      updatedAt: new Date().toISOString(),
      packages,
    };
    try {
      await call('file.write', { path, text: `${JSON.stringify(value, null, 2)}\n` });
      return path;
    } catch (error) {
      context?.api?.diagnostic?.({
        severity: 'warn',
        code: 'lock_write_failed',
        message: `The Python lockfile could not be written: ${error?.message ?? String(error)}`,
      });
      return null;
    }
  }

  const resolveTool = defineTool({
    name: PY_RESOLVE_TOOL_NAME,
    description: `Resolve a PyPI package name to an exact version, the wheel a pure-Python install `
      + `would use, its SHA-256 and a purity verdict. This is also the metadata lookup (there is no `
      + `PyPI search endpoint; discovery is your own knowledge): ${PY_RUN_TOOL_NAME} pins what this `
      + `returns, reaching it as the resolved package inside the Pyodide interpreter.`,
    parameters: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Package name, e.g. "pypdf".' },
        version: { type: 'string', description: 'Exact version. Omit for latest. Ranges are not supported by design.' },
        timeoutMs: { type: 'integer', description: 'Abort the metadata request after this many milliseconds.' },
      },
      required: ['name'],
      additionalProperties: false,
    },
    async execute(args, api) {
      const info = await resolvePackage(args?.name, args?.version, args?.timeoutMs);
      const blocks = [JSON.stringify(info, null, 2)];
      if (info.pure !== true) blocks.push(`⚠ ${info.warnings.join('; ')}`);
      api?.diagnostic?.({
        severity: info.pure === true ? 'info' : 'warn',
        code: 'py_purity',
        message: `${info.name} ${info.version}: ${info.pure === true ? 'pure wheel' : info.warnings.join('; ')}`,
      });
      return {
        content: [{ type: 'text', text: truncate(blocks.join('\n\n')) }],
        details: info,
      };
    },
  });

  const runTool = defineTool({
    name: PY_RUN_TOOL_NAME,
    description: `Run Python in the isolated sandbox on Pyodide (CPython compiled to WebAssembly). The `
      + `sandbox has no app bridge: it exposes piInputs (declared input files as memoryview, keyed by `
      + `absolute path — use .tobytes().decode() for text), piInputPaths (those absolute paths as a list), `
      + `piWrite(path, bytes) for declared output files, piOutputs (those absolute paths as a list), and `
      + `print/stdout (which becomes the run's log). Declared packages are installed with micropip from `
      + `PyPI — pure-Python wheels only; numpy, pandas, pillow and the like ship inside Pyodide itself. `
      + `The body is indented function code dedented automatically and runs inside \`async def __pi_main__():\`, `
      + `so await and return both work and the return value must be JSON-safe (use .dumps if in doubt). `
      + `A successful run pins every resolved package into .pi-py/lock.json. Known-good packages: pypdf, `
      + `pdfminer.six, python-docx, openpyxl, pytz, six.`,
    parameters: {
      type: 'object',
      properties: {
        code: { type: 'string', description: 'Python body. Use piInputs, piInputPaths, piWrite, piOutputs, print. return the result.' },
        imports: {
          type: 'array',
          items: { type: 'string' },
          description: 'PyPI package names to resolve to an exact version and install with micropip.',
        },
        inputs: { type: 'array', items: { type: 'string' }, description: 'Workspace files to expose in piInputs.' },
        outputs: { type: 'array', items: { type: 'string' }, description: 'Workspace files piWrite may create.' },
        timeoutMs: { type: 'integer', description: 'Kill the sandbox after this many milliseconds. Default 120000; max 180000.' },
      },
      required: ['code'],
      additionalProperties: false,
    },
    async execute(args, api, context) {
      const code = args?.code;
      if (typeof code !== 'string' || code.trim() === '') throw new PyToolError('invalid_code', 'code is required');
      if (new TextEncoder().encode(code).length > MAX_PY_CODE_BYTES) {
        throw new PyToolError('code_too_large', `code exceeds ${MAX_PY_CODE_BYTES} bytes`);
      }
      const imports = stringArray(args?.imports, 'imports', MAX_PY_IMPORTS);
      const inputs = stringArray(args?.inputs, 'inputs', MAX_FILES);
      const outputs = stringArray(args?.outputs, 'outputs', MAX_FILES);
      const root = rootOf();
      const inputPaths = inputs.map(path => resolveInsideWorkspace(root, path));
      const outputPaths = outputs.map(path => resolveInsideWorkspace(root, path));
      const timeoutMs = args?.timeoutMs === undefined
        ? DEFAULT_RUN_TIMEOUT_MS
        : Math.min(Math.max(Number(args.timeoutMs), 1), MAX_RUN_TIMEOUT_MS);

      // Resolve every import before starting the sandbox; a failed resolution must not leave a
      // WebView running. The body pins exactly what the resolver chose — never the model's own range.
      const resolved = [];
      for (const name of imports) {
        resolved.push(await resolvePackage(name, undefined, undefined));
      }

      const requestId = `py-run-${++nextRun}`;
      const signal = context?.abortSignal;
      if (signal?.aborted === true) throw abortError('The Python run was aborted before it started');
      const onAbort = () => { call('py.cancel', { requestId }).catch(() => {}); };
      if (typeof signal?.addEventListener === 'function') signal.addEventListener('abort', onAbort, { once: true });
      let value;
      try {
        value = await call('py.run', {
          code,
          imports: resolved.map(entry => entry.resolved),
          pyodide: { url: `${PYODIDE_CDN}/pyodide.mjs`, indexUrl: `${PYODIDE_CDN}/` },
          inputs: inputPaths,
          outputs: outputPaths,
          timeoutMs,
          requestId,
        });
      } catch (error) {
        if (signal?.aborted === true) throw abortError('The Python run was aborted');
        throw new PyToolError('sandbox_error', `The Python sandbox refused the run: ${error?.message ?? String(error)}`, error);
      } finally {
        if (typeof signal?.removeEventListener === 'function') signal.removeEventListener('abort', onAbort);
      }

      if (value?.cancelled === true || signal?.aborted === true) throw abortError('The Python run was aborted');
      if (value?.timedOut === true) throw new PyToolError('timeout', `The Python run timed out after ${timeoutMs} ms`);
      if (value?.ok !== true) {
        throw new PyToolError('sandbox_error', textOf(value?.error) || 'The Python sandbox failed without an error');
      }

      const lockPath = await writeLock(resolved, { api });
      const logs = Array.isArray(value.logs) ? value.logs.map(entry => String(entry)).slice(0, 200) : [];
      const resultText = value.result === undefined ? '' : truncate(safeText(value.result));
      const blocks = [];
      if (logs.length > 0) blocks.push(`[logs]\n${truncate(logs.join('\n'))}`);
      if (resultText !== '' && resultText !== 'null') blocks.push(`[result]\n${resultText}`);
      if (Array.isArray(value.outputs) && value.outputs.length > 0) blocks.push(`[outputs]\n${value.outputs.join('\n')}`);
      if (blocks.length === 0) blocks.push('The Python run completed without a result.');
      if (lockPath !== null) blocks.push(`[lock]\n${lockPath}`);

      return {
        content: [{ type: 'text', text: blocks.join('\n\n') }],
        details: {
          imports: resolved.map(entry => ({
            name: entry.name,
            version: entry.version,
            source: entry.source,
            resolved: entry.resolved,
            integrity: entry.sha256,
            pure: entry.pure,
          })),
          inputs: inputPaths,
          outputs: outputPaths,
          logs,
          result: resultText,
          lockPath,
        },
      };
    },
  });

  return defineExtension({
    name: PY_EXTENSION_NAME,
    tools: [resolveTool, runTool],
  });
}

/** The report carries the result as a raw JSON value already; keep it one safe, bounded text. */
function safeText(result) {
  if (result === undefined) return '';
  if (typeof result === 'string') {
    try {
      return JSON.stringify(JSON.parse(result));
    } catch {
      return result;
    }
  }
  try {
    return JSON.stringify(result);
  } catch {
    return String(result);
  }
}

function stringArray(value, field, max) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw new PyToolError('invalid_arguments', `${field} must be an array`);
  if (value.length > max) throw new PyToolError('invalid_arguments', `${field} may hold at most ${max} entries`);
  return value.map(entry => {
    if (typeof entry !== 'string' || entry.trim() === '') throw new PyToolError('invalid_arguments', `${field} entries must be non-empty strings`);
    return entry.trim();
  });
}