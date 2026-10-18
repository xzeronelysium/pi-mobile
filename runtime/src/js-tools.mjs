// The agent's autonomous JS package registry and sandbox launcher.
//
// The agent is not given a hand-curated catalog. It is given sources:
//
//   npm registry search + metadata   https://registry.npmjs.org
//   jsDelivr (browser ESM/raw)       https://cdn.jsdelivr.net
//   esm.sh (browser ESM transform)   https://esm.sh
//   unpkg (raw files)                https://unpkg.com
//   cdnjs (classic browser libs)     https://cdnjs.cloudflare.com
//
// `js_search` and `js_info` discover packages; `js_resolve` turns a package name into an exact
// version plus an ESM URL; `js_run` executes code in the Kotlin-side sandbox. The sandbox has no
// Pi/Android bridge: only `piImports`, `piInputs`, `piWrite`, and `console` are provided.
//
// The lockfile is written automatically after a successful run, so a version that worked is reused
// and the agent can still resolve `latest` when there is no lock entry.

import { defineExtension, defineTool } from '@earendil-works/pi-durable';
import { resolve as resolvePath } from './path.mjs';

export const JS_SEARCH_TOOL_NAME = 'js_search';
export const JS_INFO_TOOL_NAME = 'js_info';
export const JS_RESOLVE_TOOL_NAME = 'js_resolve';
export const JS_RUN_TOOL_NAME = 'js_run';
export const JS_EXTENSION_NAME = 'js-sandbox';

export const NPM_REGISTRY = 'https://registry.npmjs.org';
export const NPM_SEARCH = 'https://registry.npmjs.org/-/v1/search';
export const JSDELIVR_CDN = 'https://cdn.jsdelivr.net/npm';
export const ESM_SH = 'https://esm.sh';
export const UNPKG = 'https://unpkg.com';
export const CDNJS_API = 'https://api.cdnjs.com/libraries';

/** The lockfile lives in the workspace; it is written by the host, not maintained by hand. */
export const JS_LOCK_PATH = '.pi-js/lock.json';
/** A JS run must not be allowed to return an unbounded result or log to the transcript. */
export const MAX_CODE_BYTES = 256 * 1024;
export const MAX_RESULT_BYTES = 256 * 1024;
export const MAX_IMPORTS = 12;
export const MAX_FILES = 20;
export const DEFAULT_RUN_TIMEOUT_MS = 60_000;
export const MAX_RUN_TIMEOUT_MS = 180_000;

/** A failure the model should read: `code` mirrors the host's, which is never secret material. */
export class JsToolError extends Error {
  constructor(code, message, cause) {
    super(message);
    this.name = 'JsToolError';
    this.code = code;
    if (cause !== undefined) this.cause = cause;
  }
}

const abortError = message => (typeof DOMException === 'function'
  ? new DOMException(message, 'AbortError')
  : Object.assign(new Error(message), { name: 'AbortError' }));

const textOf = value => (typeof value === 'string' ? value : '');

const byteLength = text => new TextEncoder().encode(text).length;

const truncate = (text, limit = MAX_RESULT_BYTES) =>
  (text.length > limit ? `${text.slice(0, limit)}\n…[truncated at ${limit} characters]…` : text);

/** JSON-safe, bounded result text. */
function safeJson(value) {
  let encoded;
  try {
    encoded = JSON.stringify(value ?? null);
  } catch {
    encoded = JSON.stringify(String(value));
  }
  return truncate(encoded ?? 'null');
}

function parseSemver(version) {
  const match = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/.exec(String(version ?? ''));
  if (match === null) return null;
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    prerelease: match[4] ?? null,
    raw: String(version),
  };
}

function compareSemver(left, right) {
  for (const part of ['major', 'minor', 'patch']) {
    if (left[part] !== right[part]) return left[part] - right[part];
  }
  if (left.prerelease === null && right.prerelease !== null) return 1;
  if (left.prerelease !== null && right.prerelease === null) return -1;
  if (left.prerelease === null) return 0;
  return String(left.prerelease).localeCompare(String(right.prerelease));
}

/**
 * A deliberately small semver resolver.
 *
 * It supports exact versions, `latest`, `*`, `^`, `~` and `>=`. That covers the normal "give me the
 * current browser build" case without shipping a second npm dependency into the runtime.
 */
export function chooseVersion(meta, requested, range) {
  const versions = Object.keys(meta?.versions ?? {});
  if (requested !== undefined && requested !== null && requested !== '') {
    const exact = String(requested);
    if (!versions.includes(exact)) {
      throw new JsToolError('version_not_found', `${meta?.name ?? 'package'} has no version ${exact}`);
    }
    return exact;
  }
  const latest = meta?.['dist-tags']?.latest;
  if (!range || range === 'latest' || range === '*') {
    if (typeof latest !== 'string') throw new JsToolError('no_latest', `${meta?.name ?? 'package'} has no latest dist-tag`);
    return latest;
  }
  const selector = String(range).trim();
  const parsed = versions.map(parseSemver).filter(Boolean).filter(version => version.prerelease === null);
  let candidates = parsed;
  if (selector.startsWith('^') || selector.startsWith('~')) {
    const base = parseSemver(selector.slice(1));
    if (base === null) throw new JsToolError('invalid_range', `Unsupported version range: ${selector}`);
    candidates = parsed.filter(version => {
      if (compareSemver(version, base) < 0) return false;
      if (selector.startsWith('~')) return version.major === base.major && version.minor === base.minor;
      // npm's caret rule: ^0.x is minor-compatible; everything else is major-compatible.
      return base.major === 0 ? version.major === 0 && version.minor === base.minor : version.major === base.major;
    });
  } else if (selector.startsWith('>=')) {
    const base = parseSemver(selector.slice(2));
    if (base === null) throw new JsToolError('invalid_range', `Unsupported version range: ${selector}`);
    candidates = parsed.filter(version => compareSemver(version, base) >= 0);
  } else {
    const exact = parseSemver(selector);
    if (exact === null) throw new JsToolError('invalid_range', `Unsupported version range: ${selector}`);
    candidates = parsed.filter(version => compareSemver(version, exact) === 0);
  }
  candidates.sort(compareSemver);
  const chosen = candidates[candidates.length - 1]?.raw ?? latest;
  if (typeof chosen !== 'string') throw new JsToolError('version_not_found', `${meta?.name ?? 'package'} has no version matching ${selector}`);
  return chosen;
}

const NATIVE_DEPENDENCIES = [
  /^sharp$/i,
  /^canvas$/i,
  /^node-gyp$/i,
  /^bindings$/i,
  /^ffi-napi$/i,
  /^ref-napi$/i,
  /^node-addon-api$/i,
  /^better-sqlite3$/i,
  /^sqlite3$/i,
  /^puppeteer$/i,
  /^playwright$/i,
];

/** A browser-compatibility hint, never a guarantee; the sandbox import is the real test. */
export function browserHints(meta) {
  const warnings = [];
  const dependencies = Object.keys(meta?.dependencies ?? {});
  if (meta?.gypfile === true) warnings.push('package has a gypfile (native build)');
  if (meta?.main && String(meta.main).endsWith('.node')) warnings.push('main entry is a native .node binary');
  if (Array.isArray(meta?.os) && meta.os.length > 0) warnings.push(`package declares os: ${meta.os.join(', ')}`);
  if (Array.isArray(meta?.cpu) && meta.cpu.length > 0) warnings.push(`package declares cpu: ${meta.cpu.join(', ')}`);
  for (const dependency of dependencies) {
    if (NATIVE_DEPENDENCIES.some(pattern => pattern.test(dependency))) warnings.push(`native dependency: ${dependency}`);
  }
  const exportsRoot = meta?.exports && typeof meta.exports === 'object' && !Array.isArray(meta.exports)
    ? meta.exports['.']
    : meta?.exports;
  const browserEntry = Boolean(
    meta?.browser
      || meta?.module
      || meta?.type === 'module'
      || (exportsRoot && typeof exportsRoot === 'object' && (exportsRoot.browser || exportsRoot.import || exportsRoot.default))
      || (typeof exportsRoot === 'string' && exportsRoot.length > 0),
  );
  return {
    browserCompatible: browserEntry && warnings.length === 0,
    browserEntry,
    warnings,
    entries: {
      main: meta?.main ?? null,
      module: meta?.module ?? null,
      browser: meta?.browser ?? null,
      type: meta?.type ?? null,
    },
  };
}

function licenseOf(value) {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object' && typeof value.type === 'string') return value.type;
  return null;
}

function packageSummary(doc, requestedVersion) {
  const latest = requestedVersion ?? doc?.['dist-tags']?.latest ?? doc?.version ?? null;
  const versionDoc = latest !== null ? doc?.versions?.[latest] : undefined;
  const meta = { ...(doc ?? {}), ...(versionDoc ?? {}), version: latest ?? undefined };
  const hints = browserHints(meta);
  return {
    name: textOf(meta.name),
    version: latest,
    description: textOf(meta.description),
    license: licenseOf(meta.license),
    homepage: textOf(meta.homepage),
    repository: typeof meta.repository === 'string' ? meta.repository : textOf(meta.repository?.url),
    keywords: Array.isArray(meta.keywords) ? meta.keywords.slice(0, 40) : [],
    distTags: doc?.['dist-tags'] ?? {},
    versions: Object.keys(doc?.versions ?? {}).sort().slice(-30),
    unpackedSize: Number.isFinite(meta?.dist?.unpackedSize) ? meta.dist.unpackedSize : null,
    integrity: typeof meta?.dist?.integrity === 'string' ? meta.dist.integrity : null,
    dependencies: Object.keys(meta?.dependencies ?? {}).slice(0, 50),
    ...hints,
    readme: truncate(textOf(meta.readme), 4000),
  };
}

function cdnjsSummary(result) {
  return {
    name: textOf(result?.name),
    version: textOf(result?.version) || null,
    description: textOf(result?.description),
    license: licenseOf(result?.license),
    homepage: textOf(result?.homepage),
    repository: typeof result?.repository === 'string' ? result.repository : textOf(result?.repository?.url),
    source: 'cdnjs',
    files: Array.isArray(result?.files) ? result.files.slice(0, 40) : [],
    browserCompatible: true,
    warnings: [],
  };
}

/** One JSON request through Kotlin's transport, never the WebView's CORS-limited fetch. */
function createHttp(transport) {
  if (typeof transport !== 'function') throw new TypeError('createJsTools requires the runtime transport');
  return async function getJson(url, timeoutMs = 15_000) {
    let response;
    try {
      response = await transport(url, {
        method: 'GET',
        headers: { accept: 'application/json' },
        timeoutMs,
      });
    } catch (error) {
      throw new JsToolError('network_error', `Could not reach ${url}: ${error?.message ?? String(error)}`, error);
    }
    const body = await response.text();
    if (!response.ok) {
      const code = response.status === 404 ? 'not_found' : 'http_status';
      throw new JsToolError(code, `${url} answered HTTP ${response.status}${body ? `: ${truncate(body, 400)}` : ''}`);
    }
    try {
      return JSON.parse(body);
    } catch (error) {
      throw new JsToolError('invalid_response', `${url} did not return JSON`, error);
    }
  };
}

async function searchNpm(getJson, query, limit, timeoutMs) {
  const url = `${NPM_SEARCH}?text=${encodeURIComponent(query)}&size=${limit}`;
  const value = await getJson(url, timeoutMs);
  return (value?.objects ?? []).map(entry => ({
    name: textOf(entry?.package?.name),
    version: textOf(entry?.package?.version) || null,
    description: textOf(entry?.package?.description),
    homepage: textOf(entry?.package?.links?.homepage),
    repository: textOf(entry?.package?.links?.repository),
    npm: textOf(entry?.package?.links?.npm),
    publisher: textOf(entry?.package?.publisher?.username) || null,
    source: 'npm',
  })).filter(entry => entry.name !== '');
}

async function searchCdnjs(getJson, query, limit, timeoutMs) {
  const url = `${CDNJS_API}?search=${encodeURIComponent(query)}&fields=name,description,version,homepage,license,repository,files`;
  const value = await getJson(url, timeoutMs);
  return (value?.results ?? []).slice(0, limit).map(cdnjsSummary);
}

async function npmDoc(getJson, name, timeoutMs) {
  // Always fetch the full document: the version list and dist-tags are what make `chooseVersion`
  // possible. The chosen version's own metadata is merged from `versions[chosen]`.
  const target = `${NPM_REGISTRY}/${name}`;
  return getJson(target, timeoutMs);
}

async function cdnjsDoc(getJson, name, timeoutMs) {
  const url = `${CDNJS_API}?search=${encodeURIComponent(name)}&fields=name,description,version,homepage,license,repository,files`;
  const value = await getJson(url, timeoutMs);
  const exact = (value?.results ?? []).find(result => result?.name === name);
  if (exact === undefined) throw new JsToolError('not_found', `No cdnjs library named ${name}`);
  return cdnjsSummary(exact);
}

function parseImport(spec) {
  if (typeof spec === 'string') {
    const trimmed = spec.trim();
    if (trimmed === '') throw new JsToolError('invalid_import', 'An import name must not be empty');
    const at = trimmed.lastIndexOf('@');
    if (at > 0) return { name: trimmed.slice(0, at), version: trimmed.slice(at + 1) };
    return { name: trimmed, version: undefined };
  }
  if (spec && typeof spec === 'object' && typeof spec.name === 'string' && spec.name.trim() !== '') {
    return { name: spec.name.trim(), version: spec.version === undefined ? undefined : String(spec.version) };
  }
  throw new JsToolError('invalid_import', `An import must be a package name or { name, version }: ${JSON.stringify(spec)}`);
}

function resolveInsideWorkspace(root, target) {
  const base = typeof root === 'string' && root !== '' ? root : '/';
  const path = resolvePath(base, String(target));
  const inside = base === '/' || path === base || path.startsWith(`${base}/`);
  if (!inside) throw new JsToolError('workspace_escape', `${target} is outside the workspace (${base})`);
  return path;
}

function stringArray(value, field, max) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw new JsToolError('invalid_arguments', `${field} must be an array`);
  if (value.length > max) throw new JsToolError('invalid_arguments', `${field} may hold at most ${max} entries`);
  return value.map(entry => {
    if (typeof entry !== 'string' || entry.trim() === '') throw new JsToolError('invalid_arguments', `${field} entries must be non-empty strings`);
    return entry.trim();
  });
}

export function createJsTools({ transport, call, workspaceRoot } = {}) {
  if (typeof call !== 'function') throw new TypeError('createJsTools requires the bridge call');
  const getJson = createHttp(transport);
  const rootOf = typeof workspaceRoot === 'function' ? workspaceRoot : () => (typeof workspaceRoot === 'string' ? workspaceRoot : '/');
  let nextRun = 0;

  async function resolvePackage(name, version, range, timeoutMs) {
    try {
      const doc = await npmDoc(getJson, name, timeoutMs);
      const chosen = chooseVersion(doc, version, range);
      const summary = packageSummary(doc, chosen);
      return {
        ...summary,
        version: chosen,
        source: 'npm',
        esm: `${ESM_SH}/${name}@${chosen}`,
        jsdelivr: `${JSDELIVR_CDN}/${name}@${chosen}/+esm`,
        unpkg: `${UNPKG}/${name}@${chosen}/package.json`,
      };
    } catch (npmError) {
      // A cdnjs-only classic library is a legitimate source too. Only fall through for "not found";
      // a network/HTTP failure is reported as itself.
      if (npmError?.code !== 'not_found') throw npmError;
      const info = await cdnjsDoc(getJson, name, timeoutMs);
      const chosen = version ?? info.version;
      if (chosen === null) throw new JsToolError('not_found', `${name} has no resolvable cdnjs version`);
      const file = info.files.find(entry => entry.endsWith('.min.js')) ?? info.files[0];
      if (file === undefined) throw new JsToolError('not_found', `${name} has no cdnjs file entry`);
      return {
        ...info,
        version: chosen,
        source: 'cdnjs',
        esm: `https://cdnjs.cloudflare.com/ajax/libs/${name}/${chosen}/${file}`,
        jsdelivr: null,
        unpkg: null,
      };
    }
  }

  async function writeLock(imports, context) {
    const path = resolveInsideWorkspace(rootOf(), JS_LOCK_PATH);
    let existing = {};
    try {
      const raw = await call('file.read', { path });
      if (typeof raw === 'string' && raw.trim() !== '') existing = JSON.parse(raw);
    } catch { /* a missing or unreadable lock is replaced by a fresh one */ }
    const packages = { ...(existing.packages ?? {}) };
    for (const entry of imports) {
      packages[entry.name] = {
        name: entry.name,
        version: entry.version,
        source: entry.source,
        resolved: entry.esm,
        integrity: entry.integrity,
        license: entry.license,
        browserCompatible: entry.browserCompatible,
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
        message: `The JS lockfile could not be written: ${error?.message ?? String(error)}`,
      });
      return null;
    }
  }

  const searchTool = defineTool({
    name: JS_SEARCH_TOOL_NAME,
    description: `Search the public JS package indexes the way npm does: npm registry and cdnjs. `
      + `Use it to discover a browser library for a task (PDF, OCR, DOCX, XLSX, archives, etc.). `
      + `Results are candidates, not guarantees: inspect one with ${JS_INFO_TOOL_NAME} before running it. `
      + `npm packages that need Node built-ins or native binaries will not work in the sandbox. `
      + `Known-good browser packages include pdfjs-dist (PDF text/render), pdf-lib (PDF create/edit), `
      + `tesseract.js (OCR), mammoth (DOCX), SheetJS (XLSX) and jszip (archives); searching for the `
      + `package name directly is often better than a broad capability phrase.`,
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'What the package should do, e.g. "pdf text extraction" or "ocr browser".' },
        source: { type: 'string', enum: ['all', 'npm', 'cdnjs'], description: 'Which index to search. Default all.' },
        limit: { type: 'integer', description: 'Maximum results per source (1–20). Default 8.' },
        timeoutMs: { type: 'integer', description: 'Abort the search after this many milliseconds.' },
      },
      required: ['query'],
      additionalProperties: false,
    },
    async execute(args, api, context) {
      const query = args?.query;
      if (typeof query !== 'string' || query.trim() === '') throw new JsToolError('invalid_query', 'query is required');
      const limit = args?.limit === undefined ? 8 : Math.min(Math.max(Number(args.limit), 1), 20);
      const source = args?.source ?? 'all';
      if (!['all', 'npm', 'cdnjs'].includes(source)) throw new JsToolError('invalid_source', `Unknown source ${source}`);
      const timeoutMs = args?.timeoutMs;
      const results = [];
      if (source === 'all' || source === 'npm') {
        try {
          results.push(...await searchNpm(getJson, query, limit, timeoutMs));
        } catch (error) {
          api.diagnostic({ severity: 'warn', code: 'npm_search_failed', message: String(error?.message ?? error) });
        }
      }
      if (source === 'all' || source === 'cdnjs') {
        try {
          results.push(...await searchCdnjs(getJson, query, limit, timeoutMs));
        } catch (error) {
          api.diagnostic({ severity: 'warn', code: 'cdnjs_search_failed', message: String(error?.message ?? error) });
        }
      }
      const unique = new Map();
      for (const result of results) {
        const key = `${result.source}:${result.name}`;
        if (!unique.has(key)) unique.set(key, result);
      }
      const values = [...unique.values()];
      return {
        content: [{ type: 'text', text: truncate(JSON.stringify({ results: values }, null, 2)) }],
        details: { results: values },
      };
    },
  });

  const infoTool = defineTool({
    name: JS_INFO_TOOL_NAME,
    description: `Inspect one npm or cdnjs package: version, license, size, browser entry points, `
      + `dependencies, integrity and a browser-compatibility warning list. This is the check before `
      + `${JS_RESOLVE_TOOL_NAME} and ${JS_RUN_TOOL_NAME}.`,
    parameters: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Package name, e.g. "pdfjs-dist" or "@scope/pkg".' },
        version: { type: 'string', description: 'Exact version to inspect. Omit for latest.' },
        timeoutMs: { type: 'integer', description: 'Abort the metadata request after this many milliseconds.' },
      },
      required: ['name'],
      additionalProperties: false,
    },
    async execute(args) {
      const name = args?.name;
      if (typeof name !== 'string' || name.trim() === '') throw new JsToolError('invalid_name', 'name is required');
      const requested = args?.version === undefined ? undefined : String(args.version);
      try {
        const doc = await npmDoc(getJson, name.trim(), args?.timeoutMs);
        if (requested !== undefined && doc?.versions?.[requested] === undefined) {
          throw new JsToolError('version_not_found', `${name} has no version ${requested}`);
        }
        const summary = packageSummary(doc, requested);
        return {
          content: [{ type: 'text', text: truncate(JSON.stringify({ ...summary, source: 'npm' }, null, 2)) }],
          details: { ...summary, source: 'npm' },
        };
      } catch (error) {
        if (error?.code !== 'not_found') throw error;
        const info = await cdnjsDoc(getJson, name.trim(), args?.timeoutMs);
        return {
          content: [{ type: 'text', text: truncate(JSON.stringify(info, null, 2)) }],
          details: info,
        };
      }
    },
  });

  const resolveTool = defineTool({
    name: JS_RESOLVE_TOOL_NAME,
    description: `Resolve a package name/range to an exact version and browser ESM URLs. Prefers `
      + `esm.sh, with jsDelivr as a fallback, and cdnjs for classic libraries. Returns the integrity `
      + `and browser warnings; ${JS_RUN_TOOL_NAME} uses the resolved URL.`,
    parameters: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Package name.' },
        version: { type: 'string', description: 'Exact version. Takes precedence over range.' },
        range: { type: 'string', description: 'latest, *, ^1.2.3, ~1.2.3 or >=1.2.3. Default latest.' },
        timeoutMs: { type: 'integer', description: 'Abort metadata resolution after this many milliseconds.' },
      },
      required: ['name'],
      additionalProperties: false,
    },
    async execute(args) {
      const name = args?.name;
      if (typeof name !== 'string' || name.trim() === '') throw new JsToolError('invalid_name', 'name is required');
      const info = await resolvePackage(name.trim(), args?.version, args?.range, args?.timeoutMs);
      return {
        content: [{ type: 'text', text: truncate(JSON.stringify(info, null, 2)) }],
        details: info,
      };
    },
  });

  const runTool = defineTool({
    name: JS_RUN_TOOL_NAME,
    description: `Run JavaScript in the isolated JS sandbox. The sandbox has no app bridge: it exposes `
      + `piImports (resolved packages), piInputs (declared input files as Uint8Array, keyed by absolute path), `
      + `piInputPaths (the same absolute paths as an array), piWrite(path, data) for declared output files, `
      + `piOutputs (declared output absolute paths as an array), and console. Code is an async body, so await `
      + `works. Imports are resolved from npm/cdnjs and loaded from esm.sh/jsDelivr. Declare every input and `
      + `output; the host only allows those paths and writes a .pi-js/lock.json entry for every resolved package.`,
    parameters: {
      type: 'object',
      properties: {
        code: { type: 'string', description: 'JavaScript async body. Use piImports, piInputs, piWrite, console.' },
        imports: {
          type: 'array',
          items: { type: 'string' },
          description: 'Package names (or name@version) to resolve and expose as piImports[name].',
        },
        inputs: { type: 'array', items: { type: 'string' }, description: 'Workspace files to expose in piInputs.' },
        outputs: { type: 'array', items: { type: 'string' }, description: 'Workspace files piWrite may create.' },
        timeoutMs: { type: 'integer', description: 'Kill the sandbox after this many milliseconds. Default 60000; max 180000.' },
      },
      required: ['code'],
      additionalProperties: false,
    },
    async execute(args, api, context) {
      const code = args?.code;
      if (typeof code !== 'string' || code.trim() === '') throw new JsToolError('invalid_code', 'code is required');
      if (byteLength(code) > MAX_CODE_BYTES) throw new JsToolError('code_too_large', `code exceeds ${MAX_CODE_BYTES} bytes`);
      const imports = stringArray(args?.imports, 'imports', MAX_IMPORTS);
      const inputs = stringArray(args?.inputs, 'inputs', MAX_FILES);
      const outputs = stringArray(args?.outputs, 'outputs', MAX_FILES);
      const root = rootOf();
      const inputPaths = inputs.map(path => resolveInsideWorkspace(root, path));
      const outputPaths = outputs.map(path => resolveInsideWorkspace(root, path));
      const timeoutMs = args?.timeoutMs === undefined
        ? DEFAULT_RUN_TIMEOUT_MS
        : Math.min(Math.max(Number(args.timeoutMs), 1), MAX_RUN_TIMEOUT_MS);

      // Resolve every import before starting the sandbox; a failed resolution must not leave a
      // WebView running.
      const resolved = [];
      const importMap = {};
      for (const spec of imports) {
        const parsed = parseImport(spec);
        const info = await resolvePackage(parsed.name, parsed.version, undefined, undefined);
        resolved.push(info);
        importMap[parsed.name] = info.esm;
      }

      const requestId = `js-run-${++nextRun}`;
      const signal = context?.abortSignal;
      if (signal?.aborted === true) throw abortError('The JS run was aborted before it started');
      const onAbort = () => { call('js.cancel', { requestId }).catch(() => {}); };
      if (typeof signal?.addEventListener === 'function') signal.addEventListener('abort', onAbort, { once: true });
      let value;
      try {
        value = await call('js.run', {
          code,
          imports: importMap,
          inputs: inputPaths,
          outputs: outputPaths,
          timeoutMs,
          requestId,
        });
      } catch (error) {
        if (signal?.aborted === true) throw abortError('The JS run was aborted');
        throw new JsToolError('sandbox_error', `The JS sandbox refused the run: ${error?.message ?? String(error)}`, error);
      } finally {
        if (typeof signal?.removeEventListener === 'function') signal.removeEventListener('abort', onAbort);
      }

      if (value?.cancelled === true || signal?.aborted === true) throw abortError('The JS run was aborted');
      if (value?.timedOut === true) throw new JsToolError('timeout', `The JS run timed out after ${timeoutMs} ms`);
      if (value?.ok !== true) {
        throw new JsToolError('sandbox_error', textOf(value?.error) || 'The JS sandbox failed without an error');
      }

      const lockPath = await writeLock(resolved, { api });
      const logs = Array.isArray(value.logs) ? value.logs.map(entry => String(entry)).slice(0, 200) : [];
      const resultText = value.result === undefined ? '' : safeJson(value.result);
      const blocks = [];
      if (logs.length > 0) blocks.push(`[logs]\n${truncate(logs.join('\n'))}`);
      if (resultText !== '' && resultText !== 'null') blocks.push(`[result]\n${resultText}`);
      if (Array.isArray(value.outputs) && value.outputs.length > 0) blocks.push(`[outputs]\n${value.outputs.join('\n')}`);
      if (blocks.length === 0) blocks.push('The JS run completed without a result.');
      if (lockPath !== null) blocks.push(`[lock]\n${lockPath}`);

      return {
        content: [{ type: 'text', text: blocks.join('\n\n') }],
        details: {
          imports: resolved.map(entry => ({
            name: entry.name,
            version: entry.version,
            source: entry.source,
            resolved: entry.esm,
            integrity: entry.integrity,
            license: entry.license,
            browserCompatible: entry.browserCompatible,
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
    name: JS_EXTENSION_NAME,
    tools: [searchTool, infoTool, resolveTool, runTool],
  });
}
