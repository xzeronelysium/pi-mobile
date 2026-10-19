// The autonomous Python package path: exact PyPI resolution and the Pyodide sandbox call, with
// the automatic .pi-py/lock.json.

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PY_EXTENSION_NAME,
  PY_RESOLVE_TOOL_NAME,
  PY_RUN_TOOL_NAME,
  PYODIDE_VERSION,
  createPyTools,
} from '../src/py-tools.mjs';

const PYPI_DOC = {
  info: {
    name: 'pypdf',
    version: '4.2.0',
    summary: 'A Pure-Python PDF library',
    license: 'BSD-3-Clause',
    home_page: 'https://pypdf.readthedocs.io/',
    requires_python: '>=3.6',
  },
  releases: {
    '3.0.0': [{ filename: 'pypdf-3.0.0-py3-none-any.whl', size: 200_000, digests: { sha256: 'old' } }],
    '4.2.0': [
      { filename: 'pypdf-4.2.0-py3-none-any.whl', size: 291_872, digests: { sha256: 'pure-sha' } },
      { filename: 'pypdf-4.2.0.tar.gz', size: 320_000, digests: { sha256: 'src-sha' } },
    ],
  },
  urls: [{ filename: 'pypdf-4.2.0-py3-none-any.whl', size: 291_872, url: 'https://files.pythonhosted.org/pypdf/whl', digests: { sha256: 'pure-sha' } }],
};

const PYPI_PLATFORM_DOC = {
  info: { name: 'lxml', version: '5.1.0', summary: 'XML', license: 'BSD' },
  releases: {},
  urls: [{ filename: 'lxml-5.1.0-cp312-cp312-manylinux.whl', size: 5_000_000, digests: { sha256: 'plat-sha' } }],
};

function createHarness({ pypi = PYPI_DOC, run } = {}) {
  const calls = [];
  const requests = [];
  const transport = async (url, init = {}) => {
    requests.push({ url, init });
    if (url.startsWith('https://pypi.org/pypi/')) {
      const name = url.replace('https://pypi.org/pypi/', '').replace('/json', '');
      if (name === 'lxml') return new Response(JSON.stringify(pypi), { status: 200 });
      return new Response(JSON.stringify(pypi), { status: pypi === PYPI_DOC ? 200 : 404 });
    }
    return new Response(JSON.stringify({ error: 'unexpected url' }), { status: 500 });
  };
  const bridge = async (op, args = {}) => {
    calls.push({ op, args });
    if (op === 'py.run') {
      return run === undefined
        ? { ok: true, result: '"42"', logs: ['log: hello'], outputs: args.outputs }
        : run;
    }
    if (op === 'py.cancel') return null;
    if (op === 'file.read') return '';
    if (op === 'file.write') return null;
    throw new Error(`Unexpected op ${op}`);
  };
  const extension = createPyTools({
    transport,
    call: bridge,
    workspaceRoot: () => '/workspace',
  });
  const tool = name => extension.tools.find(entry => entry.name === name);
  return { extension, tool, transport, requests, calls, bridge };
}

const api = () => ({ output() {}, diagnostic() {}, outputWindow: undefined });

test('py_resolve pins the exact version, the pure wheel, its hash and its purity', async () => {
  const { extension, tool } = createHarness();
  assert.equal(extension.name, PY_EXTENSION_NAME);
  assert.deepEqual(extension.tools.map(entry => entry.name), [PY_RESOLVE_TOOL_NAME, PY_RUN_TOOL_NAME]);
  const result = await tool(PY_RESOLVE_TOOL_NAME).execute({ name: 'pypdf' }, api(), {});
  assert.equal(result.details.version, '4.2.0');
  assert.equal(result.details.resolved, 'pypdf==4.2.0');
  assert.equal(result.details.sha256, 'pure-sha');
  assert.equal(result.details.pure, true);
  assert.equal(result.details.url, 'https://files.pythonhosted.org/pypdf/whl');
  assert.equal(result.details.license, 'BSD-3-Clause');
});

test('py_resolve asks for an exact version and flags a non-pure wheel honestly', async () => {
  const exact = await createHarness().tool(PY_RESOLVE_TOOL_NAME).execute({ name: 'pypdf', version: '3.0.0' }, api(), {});
  assert.equal(exact.details.version, '3.0.0');
  assert.equal(exact.details.resolved, 'pypdf==3.0.0');
  assert.equal(exact.details.pure, true);

  const impure = await createHarness({ pypi: PYPI_PLATFORM_DOC }).tool(PY_RESOLVE_TOOL_NAME).execute({ name: 'lxml', version: '5.1.0' }, api(), {});
  assert.equal(impure.details.pure, false);
  assert.ok(impure.details.warnings.some(warning => warning.includes('will not install')));
  assert.match(impure.content[0].text, /will not install/);
});

test('py_resolve refuses an unknown version before the sandbox starts', async () => {
  const { tool, calls } = createHarness();
  await assert.rejects(tool(PY_RESOLVE_TOOL_NAME).execute({ name: 'pypdf', version: '9.9.9' }, api(), {}), error => {
    assert.equal(error.code, 'version_not_found');
    return true;
  });
  assert.equal(calls.some(call => call.op === 'py.run'), false);
});

test('py_run passes the pinned specs, declared paths and interpreter pin to the sandbox', async () => {
  const { tool, calls } = createHarness();
  const result = await tool(PY_RUN_TOOL_NAME).execute({
    code: 'return 21 * 2;',
    imports: ['pypdf'],
    inputs: ['doc.pdf'],
    outputs: ['out/text.txt'],
    timeoutMs: 90_000,
  }, api(), {});
  const run = calls.find(call => call.op === 'py.run');
  assert.deepEqual(run.args.imports, ['pypdf==4.2.0']);
  assert.equal(run.args.pyodide.url, `https://cdn.jsdelivr.net/pyodide/v${PYODIDE_VERSION}/full/pyodide.mjs`);
  assert.equal(run.args.pyodide.indexUrl, `https://cdn.jsdelivr.net/pyodide/v${PYODIDE_VERSION}/full/`);
  assert.deepEqual(run.args.inputs, ['/workspace/doc.pdf']);
  assert.deepEqual(run.args.outputs, ['/workspace/out/text.txt']);
  assert.equal(run.args.timeoutMs, 90_000);
  assert.match(run.args.requestId, /^py-run-/);
  const lock = calls.find(call => call.op === 'file.write');
  assert.equal(lock.args.path, '/workspace/.pi-py/lock.json');
  const parsed = JSON.parse(lock.args.text);
  assert.equal(parsed.packages['pypdf'].version, '4.2.0');
  assert.equal(parsed.packages['pypdf'].integrity, 'pure-sha');
  assert.equal(parsed.packages['pypdf'].pure, true);
  assert.match(result.content[0].text, /pypdf==|42|logs/);
  assert.equal(result.details.lockPath, '/workspace/.pi-py/lock.json');
});

test('a failed resolution never starts the Python sandbox', async () => {
  const { tool, calls } = createHarness({ pypi: null });
  await assert.rejects(tool(PY_RUN_TOOL_NAME).execute({ code: 'return 1;', imports: ['nope'] }, api(), {}), error => {
    assert.equal(error.code, 'not_found');
    return true;
  });
  assert.equal(calls.some(call => call.op === 'py.run'), false);
});

test('a timeout or cancellation from the sandbox reads like the JS path', async () => {
  const { tool } = createHarness({ run: { ok: false, timedOut: true } });
  await assert.rejects(tool(PY_RUN_TOOL_NAME).execute({ code: 'while True: pass' }, api(), {}), error => {
    assert.equal(error.code, 'timeout');
    assert.match(error.message, /timed out/);
    return true;
  });
  const cancelled = createHarness({ run: { ok: false, cancelled: true } });
  const controller = new AbortController();
  const promise = cancelled.tool(PY_RUN_TOOL_NAME).execute(
    { code: 'while True: pass' }, api(), { abortSignal: controller.signal },
  );
  controller.abort();
  await assert.rejects(promise, error => {
    assert.equal(error.name, 'AbortError');
    return true;
  });
});

test('oversized code or too many imports are refused before the sandbox starts', async () => {
  const { tool, calls } = createHarness();
  await assert.rejects(tool(PY_RUN_TOOL_NAME).execute({ code: 'x = ' + '1 '.repeat(200_000) }, api(), {}), error => {
    assert.equal(error.code, 'code_too_large');
    return true;
  });
  await assert.rejects(tool(PY_RUN_TOOL_NAME).execute({ code: 'return 1;', imports: Array.from({ length: 13 }, (_, i) => `p${i}`) }, api(), {}), error => {
    assert.equal(error.code, 'invalid_arguments');
    return true;
  });
  assert.equal(calls.some(call => call.op === 'py.run'), false);
});