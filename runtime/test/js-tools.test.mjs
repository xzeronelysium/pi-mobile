// The autonomous JS package path: npm/cdnjs search, npm metadata, exact ESM resolution, the
// sandbox call, and the automatic lockfile.

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  JS_EXTENSION_NAME,
  JS_INFO_TOOL_NAME,
  JS_RESOLVE_TOOL_NAME,
  JS_RUN_TOOL_NAME,
  JS_SEARCH_TOOL_NAME,
  createJsTools,
} from '../src/js-tools.mjs';

const NPM_DOC = {
  name: 'pdfjs-dist',
  description: 'PDF rendering and text extraction',
  license: 'Apache-2.0',
  homepage: 'https://mozilla.github.io/pdf.js/',
  repository: { url: 'git+https://github.com/mozilla/pdf.js.git' },
  'dist-tags': { latest: '4.0.379' },
  versions: {
    '3.11.174': { version: '3.11.174', dist: { integrity: 'sha512-old', unpackedSize: 1000 } },
    '4.0.379': {
      version: '4.0.379',
      license: 'Apache-2.0',
      module: './build/pdf.mjs',
      type: 'module',
      dependencies: {},
      dist: { integrity: 'sha512-new', unpackedSize: 2000 },
    },
  },
  readme: 'PDF.js is a portable document format viewer.',
};

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function createHarness({ npm = NPM_DOC, npmStatus = 200, cdnjs = [], run } = {}) {
  const calls = [];
  const requests = [];
  const transport = async (url, init = {}) => {
    requests.push({ url, init });
    if (url.includes('/-/v1/search')) {
      return json({
        objects: [
          {
            package: {
              name: 'pdfjs-dist', version: '4.0.379', description: 'PDF viewer',
              links: { homepage: 'https://mozilla.github.io/pdf.js/', repository: 'https://github.com/mozilla/pdf.js' },
              publisher: { username: 'mozilla' },
            },
          },
          {
            package: {
              name: 'pdf-lib', version: '1.17.1', description: 'Create and modify PDFs',
              links: { homepage: 'https://pdf-lib.js.org/' }, publisher: { username: 'andrew' },
            },
          },
        ],
      });
    }
    if (url.includes('api.cdnjs.com')) {
      return json({
        results: [
          {
            name: 'pdf.js', version: '2.0.0', description: 'PDF.js classic', license: 'Apache-2.0',
            homepage: 'https://mozilla.github.io/pdf.js/', files: ['pdf.min.js', 'pdf.js'],
          },
          ...cdnjs,
        ],
      });
    }
    if (url.startsWith('https://registry.npmjs.org/')) {
      if (npmStatus !== 200) return json({ error: 'not found' }, npmStatus);
      return json(npm);
    }
    return json({ error: 'unexpected url' }, 500);
  };
  const bridge = async (op, args = {}) => {
    calls.push({ op, args });
    if (op === 'js.run') {
      return run === undefined ? { ok: true, result: { pages: 2 }, logs: ['log: ok'], outputs: args.outputs } : run;
    }
    if (op === 'js.cancel') return null;
    if (op === 'file.read') return '';
    if (op === 'file.write') return null;
    throw new Error(`Unexpected op ${op}`);
  };
  const extension = createJsTools({
    transport,
    call: bridge,
    workspaceRoot: () => '/workspace',
  });
  const tool = name => extension.tools.find(entry => entry.name === name);
  return { extension, tool, transport, requests, calls, bridge };
}

const api = () => ({ output() {}, diagnostic() {}, outputWindow: undefined });

test('js_search queries npm and cdnjs and returns candidates as content and details', async () => {
  const { extension, tool } = createHarness();
  assert.equal(extension.name, JS_EXTENSION_NAME);
  assert.deepEqual(extension.tools.map(entry => entry.name), [
    JS_SEARCH_TOOL_NAME, JS_INFO_TOOL_NAME, JS_RESOLVE_TOOL_NAME, JS_RUN_TOOL_NAME,
  ]);
  const result = await tool(JS_SEARCH_TOOL_NAME).execute({ query: 'pdf extraction' }, {}, {});
  assert.equal(result.details.results.length, 3);
  assert.deepEqual(result.details.results.map(entry => entry.source), ['npm', 'npm', 'cdnjs']);
  assert.match(result.content[0].text, /pdfjs-dist/);
  assert.match(result.content[0].text, /pdf\.js/);
});

test('js_info reports npm metadata, integrity, license and browser hints', async () => {
  const { tool } = createHarness();
  const result = await tool(JS_INFO_TOOL_NAME).execute({ name: 'pdfjs-dist' }, {}, {});
  assert.equal(result.details.name, 'pdfjs-dist');
  assert.equal(result.details.version, '4.0.379');
  assert.equal(result.details.license, 'Apache-2.0');
  assert.equal(result.details.integrity, 'sha512-new');
  assert.equal(result.details.browserCompatible, true);
  assert.match(result.content[0].text, /browserCompatible/);
});

test('js_info flags a native package as not browser-compatible', async () => {
  const native = { ...NPM_DOC, name: 'sharp', gypfile: true, versions: { '0.33.0': { version: '0.33.0', gypfile: true } }, 'dist-tags': { latest: '0.33.0' } };
  const { tool } = createHarness({ npm: native });
  const result = await tool(JS_INFO_TOOL_NAME).execute({ name: 'sharp' }, {}, {});
  assert.equal(result.details.browserCompatible, false);
  assert.ok(result.details.warnings.some(warning => warning.includes('gypfile')));
});

test('js_resolve picks latest or a caret range and returns ESM URLs plus integrity', async () => {
  const { tool } = createHarness();
  const latest = await tool(JS_RESOLVE_TOOL_NAME).execute({ name: 'pdfjs-dist' }, {}, {});
  assert.equal(latest.details.version, '4.0.379');
  assert.equal(latest.details.source, 'npm');
  assert.match(latest.details.esm, /^https:\/\/esm\.sh\/pdfjs-dist@4\.0\.379$/);
  assert.match(latest.details.jsdelivr, /cdn\.jsdelivr\.net/);
  assert.equal(latest.details.integrity, 'sha512-new');

  const ranged = await tool(JS_RESOLVE_TOOL_NAME).execute({ name: 'pdfjs-dist', range: '^3.0.0' }, {}, {});
  assert.equal(ranged.details.version, '3.11.174');
  assert.match(ranged.details.esm, /pdfjs-dist@3\.11\.174/);
});

test('a cdnjs-only package falls back to the cdnjs index', async () => {
  const { tool } = createHarness({ npmStatus: 404, cdnjs: [] });
  const result = await tool(JS_RESOLVE_TOOL_NAME).execute({ name: 'pdf.js' }, {}, {});
  assert.equal(result.details.source, 'cdnjs');
  assert.match(result.details.esm, /cdnjs\.cloudflare\.com\/ajax\/libs\/pdf\.js\/2\.0\.0\/pdf\.min\.js/);
});

test('js_run resolves imports, passes declared paths to the sandbox and writes the lockfile', async () => {
  const { tool, calls } = createHarness();
  const result = await tool(JS_RUN_TOOL_NAME).execute({
    code: 'return piImports["pdfjs-dist"].version;',
    imports: ['pdfjs-dist'],
    inputs: ['doc.pdf'],
    outputs: ['out/page-1.png'],
    timeoutMs: 12_000,
  }, api(), {});
  const run = calls.find(call => call.op === 'js.run');
  assert.equal(run.args.code.includes('piImports'), true);
  assert.equal(run.args.imports['pdfjs-dist'], 'https://esm.sh/pdfjs-dist@4.0.379');
  assert.deepEqual(run.args.inputs, ['/workspace/doc.pdf']);
  assert.deepEqual(run.args.outputs, ['/workspace/out/page-1.png']);
  assert.equal(run.args.timeoutMs, 12_000);
  assert.match(run.args.requestId, /^js-run-/);
  const lock = calls.find(call => call.op === 'file.write');
  assert.equal(lock.args.path, '/workspace/.pi-js/lock.json');
  const parsed = JSON.parse(lock.args.text);
  assert.equal(parsed.packages['pdfjs-dist'].version, '4.0.379');
  assert.equal(parsed.packages['pdfjs-dist'].integrity, 'sha512-new');
  assert.match(result.content[0].text, /pages/);
  assert.equal(result.details.lockPath, '/workspace/.pi-js/lock.json');
});

test('an already-aborted context never starts the sandbox', async () => {
  const { tool, calls } = createHarness();
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(tool(JS_RUN_TOOL_NAME).execute(
    { code: 'return 1;' }, api(), { abortSignal: controller.signal },
  ), error => {
    assert.equal(error.name, 'AbortError');
    return true;
  });
  assert.equal(calls.some(call => call.op === 'js.run'), false);
});

test('a timeout from the sandbox is a timeout error, not an empty success', async () => {
  const { tool } = createHarness({ run: { ok: false, timedOut: true } });
  await assert.rejects(tool(JS_RUN_TOOL_NAME).execute({ code: 'while(true){}' }, api(), {}), error => {
    assert.equal(error.code, 'timeout');
    assert.match(error.message, /timed out/);
    return true;
  });
});

test('an invalid import or oversized code is refused before the sandbox starts', async () => {
  const { tool, calls } = createHarness();
  await assert.rejects(tool(JS_RUN_TOOL_NAME).execute({ code: 'return 1;', imports: [''] }, api(), {}), error => {
    assert.equal(error.code, 'invalid_arguments');
    return true;
  });
  await assert.rejects(tool(JS_RUN_TOOL_NAME).execute({ code: 'x'.repeat(300_000) }, api(), {}), error => {
    assert.equal(error.code, 'code_too_large');
    return true;
  });
  assert.equal(calls.some(call => call.op === 'js.run'), false);
});
