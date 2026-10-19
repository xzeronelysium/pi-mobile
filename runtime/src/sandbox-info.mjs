// The sandbox capability report.
//
// These are sandbox runtimes — not desktop Python and not Node.js — and the model discovers that
// cheapest when it is TOLD, not when a package fails to install. Every number here is read from
// the same constants the tools and the host enforce, so the report cannot drift from enforcement;
// the network allowlist is the sandbox page's own CSP (`app/src/main/assets/native-runtime/
// sandbox.html`), which is the layer that actually decides egress for sandbox code.

import { defineExtension, defineTool } from '@earendil-works/pi-durable';
import { JS_LOCK_PATH, MAX_CODE_BYTES, MAX_FILES, MAX_IMPORTS, MAX_MODULES, MAX_RUN_TIMEOUT_MS, DEFAULT_RUN_TIMEOUT_MS } from './js-tools.mjs';
import { MAX_PY_CODE_BYTES, MAX_PY_IMPORTS, PY_LOCK_PATH, PYODIDE_VERSION } from './py-tools.mjs';

export const SANDBOX_CAPABILITIES_TOOL_NAME = 'sandbox_capabilities';
export const SANDBOX_INFO_EXTENSION_NAME = 'sandbox-info';

/** The hosts the sandbox Worker can reach: the sandbox page's CSP decides enforcement. */
export const SANDBOX_EGRESS_HOSTS = [
  'esm.sh',
  'cdn.jsdelivr.net',
  'unpkg.com',
  'cdnjs.cloudflare.com',
  'tessdata.projectnaptha.com',
  'pypi.org',
  'files.pythonhosted.org',
];

export function sandboxCapabilities() {
  return {
    javascript: {
      engine: 'Chromium (the Android System WebView) JavaScript, in the isolated Worker',
      realEsm: true,
      nodeBuiltIns: [],
      providedGlobals: ['console', 'TextEncoder', 'TextDecoder', 'atob', 'btoa'],
      note: 'There is no Node.js runtime, no Buffer, and no fs/process. Browser-compatible ESM packages from the CDNs work; anything needing Node built-ins or native binaries will not.',
    },
    python: {
      interpreter: `CPython on Pyodide v${PYODIDE_VERSION}`,
      nativeExtensionSupport: false,
      pureWheelsOnly: true,
      bundledWithInterpreter: ['micropip', 'numpy', 'pandas', 'pillow', 'and the Python stdlib'],
      note: 'Pure-Python wheels install from PyPI via micropip. C-extension wheels that are not prebuilt for Pyodide cannot install — prebuilt editions (numpy, pandas, pillow, ...) come from the pinned interpreter distribution instead.',
    },
    limits: {
      jsCodeBytes: MAX_CODE_BYTES,
      pythonCodeBytes: MAX_PY_CODE_BYTES,
      inputFileBytes: 33_554_432,
      outputFileBytes: 33_554_432,
      resultAndLogBytes: 262_144,
      importsPerRun: Math.max(MAX_IMPORTS, MAX_PY_IMPORTS),
      declaredFilesPerRun: MAX_FILES,
      workspaceModulesPerRun: MAX_MODULES,
      jsTimeout: { defaultMs: DEFAULT_RUN_TIMEOUT_MS, maxMs: MAX_RUN_TIMEOUT_MS },
      pythonTimeout: { defaultMs: 120_000, maxMs: 180_000 },
      memory: 'device-dependent; runs are terminated on their own deadline, not a fixed heap',
      filesystem: 'only the declared input/output paths, confined to the workspace; no shell, no Android bridge, no app storage',
      pyodideColdStart: 'each Python run boots the interpreter fresh (multi-second); there is no persistent session by design',
    },
    network: {
      sandboxEgressAllowlist: SANDBOX_EGRESS_HOSTS,
      note: 'Sandbox code reaches only the allowlisted hosts. Package resolution and downloads run in the tool layer and the worker respectively; anything else is refused.',
    },
    reproducibility: {
      lockfiles: [JS_LOCK_PATH, PY_LOCK_PATH],
      reuse: 'a plain package name resolves from the last good lock with no network round trip; an explicit version bypasses the lock',
      moduleHashes: 'workspace modules are SHA-256 recorded after each successful run',
      offline: 'locked runs avoid resolution traffic; first-time packages and Pyodide itself need the network',
    },
  };
}

export function createSandboxInfo() {
  const capabilitiesTool = defineTool({
    name: SANDBOX_CAPABILITIES_TOOL_NAME,
    description: 'Read the sandbox capability report before choosing packages for a run: what the '
      + 'JS and Python (Pyodide) runtimes support, their size/timeout/memory limits, the network '
      + 'allowlist and lockfile behavior. Use this to rule requests in or out — not to discover '
      + 'limitations by failure.',
    parameters: {
      type: 'object',
      properties: {},
      additionalProperties: false,
    },
    execute() {
      const report = sandboxCapabilities();
      return {
        content: [{ type: 'text', text: JSON.stringify(report, null, 2) }],
        details: report,
      };
    },
  });
  return defineExtension({
    name: SANDBOX_INFO_EXTENSION_NAME,
    tools: [capabilitiesTool],
  });
}