// The capability report: assembled from enforced constants, so what it claims is what runs.

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  SANDBOX_CAPABILITIES_TOOL_NAME,
  createSandboxInfo,
  sandboxCapabilities,
} from '../src/sandbox-info.mjs';
import { JS_LOCK_PATH, MAX_CODE_BYTES, MAX_MODULES } from '../src/js-tools.mjs';
import { PYODIDE_VERSION, PY_LOCK_PATH } from '../src/py-tools.mjs';

const api = () => ({ output() {}, diagnostic() {}, outputWindow: undefined });

test('the capability tool is registered and returns the report as content and details', () => {
  const extension = createSandboxInfo();
  assert.equal(extension.name, 'sandbox-info');
  const tool = extension.tools.find(entry => entry.name === SANDBOX_CAPABILITIES_TOOL_NAME);
  assert.ok(tool, 'the capability tool is registered');
  const result = tool.execute({}, api(), {});
  assert.match(result.content[0].text, /"interpreter"/);
  assert.deepEqual(result.details, sandboxCapabilities());
});

test('the report reads the enforced constants, never a second copy', () => {
  const report = sandboxCapabilities();
  assert.equal(report.limits.jsCodeBytes, MAX_CODE_BYTES);
  assert.equal(report.limits.workspaceModulesPerRun, MAX_MODULES);
  assert.equal(report.python.interpreter, `CPython on Pyodide v${PYODIDE_VERSION}`);
  assert.ok(report.reproducibility.lockfiles.includes(JS_LOCK_PATH));
  assert.ok(report.reproducibility.lockfiles.includes(PY_LOCK_PATH));
  assert.ok(report.network.sandboxEgressAllowlist.includes('files.pythonhosted.org'));
  assert.equal(report.javascript.nodeBuiltIns.length, 0);
  assert.equal(report.python.nativeExtensionSupport, false);
});