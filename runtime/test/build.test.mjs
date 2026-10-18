import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, rm } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { DatabaseSync } from 'node:sqlite';

/** The asset Gradle packages into the app; `build.mjs` writes it and the app loads it. */
const SHIPPED_BUNDLE = new URL('../../app/src/main/assets/native-runtime/runtime.js', import.meta.url);

const buildBundle = env => execFileSync(process.execPath, ['build.mjs'], {
  cwd: new URL('..', import.meta.url),
  stdio: 'pipe',
  ...(env === undefined ? {} : { env: { ...process.env, ...env } }),
});

const digest = buffer => createHash('sha256').update(buffer).digest('hex');

/**
 * A per-test scratch output path. No test in this suite builds the tracked asset: two test files
 * building the SAME file concurrently is a race (one truncates and rewrites it in place while the
 * other reads it, so a read can land mid-write), and a test that rebuilds a file it then asserts
 * about also leaves that tracked file dirty. Each test builds to its own path and asserts that; the
 * freshness test below ties the tracked asset to a fresh build WITHOUT rewriting it.
 */
const scratchPath = label => path.join(tmpdir(), `pi-runtime-${label}-${process.pid}-${Date.now()}.js`);

test('browser bundle loads without Node globals and exposes PiNative', async () => {
  // Built to this test's own path, never the tracked asset (see `scratchPath`).
  const scratch = scratchPath('load');
  buildBundle({ PI_RUNTIME_OUT: scratch });
  const code = await readFile(scratch, 'utf8');
  const messages = [];
  const scope = { AndroidNative: { postMessage: s => messages.push(JSON.parse(s)) } };
  let authorization;
  let requestBody;
  const offlineFetch = async (input, options) => {
    authorization = new Headers(options?.headers ?? input.headers).get('authorization');
    const raw = options?.body ?? input.body;
    requestBody = typeof raw === 'string' ? raw : new TextDecoder().decode(raw ?? new Uint8Array());
    throw new Error('BROWSER_TEST_OFFLINE');
  };
  const globals = { window: scope, console, setTimeout, clearTimeout, queueMicrotask, AbortController, AbortSignal, TextEncoder, TextDecoder, URL, URLSearchParams, Headers, Request, Response, FormData, Blob, File, ReadableStream, fetch: offlineFetch, crypto: globalThis.crypto, btoa, atob, performance };
  vm.runInNewContext(code, globals, { timeout: 10000 });
  assert.equal(typeof scope.nativeReply, 'function');
  for (const name of ['init','send','abort','snapshot','history','sessions','openSession','newSession','forkSession','resetSession','usage','queue','steer','followUp','withdraw','writeEntry','exportSession','commands','mcpState','mcpAdd','mcpRemove','mcpConnect','mcpDisconnect','runFlow','resumeFlow','flowStatus','flowThreads','flows']) assert.equal(typeof scope.PiNative[name], 'function', name);
  assert.equal(messages[0].event, 'loaded');
  assert.equal(/require\(["']node:/.test(code), false);
  // Every built-in OAuth flow is statically embedded, so no sign-in depends on a module loader the
  // WebView does not have. The loopback shim is present too.
  for (const marker of ['Anthropic (Claude Pro/Max)', 'GitHub Copilot', 'OpenAI (ChatGPT subscription)', 'OpenAI (ChatGPT Plus/Pro)', 'Kimi Code', 'Sign in with OpenRouter', 'Radius']) {
    assert.equal(code.includes(marker), true, marker);
  }
  assert.equal(code.includes('oauth.listen'), true);
  // The Model sheet's metadata projection ships with the app: a bundle built from an older
  // providers.mjs would leave the picker with ids only.
  for (const marker of ['thinkingLevels', 'contextWindow', 'maxTokens']) {
    assert.equal(code.includes(marker), true, marker);
  }
  // The session/usage/queue/export layer ships with the app too: a bundle built from a source
  // tree without sessions.mjs would leave the history drawer and footer with no host op.
  for (const marker of ['Unsupported export format', 'Unsupported queue mode', 'Queued input text required']) {
    assert.equal(code.includes(marker), true, marker);
  }
  // The app-owned command layer and the MCP client ship too. The markers are the strings the two
  // modules themselves carry: a bundle built before commands.mjs/mcp.mjs would omit them, and the
  // client core proves the transports came in without the stdio transport (which needs a process).
  for (const marker of ['.pi-skills', '.pi-templates', 'is not a known command', 'Cannot connect MCP client in', 'MCP server selected unsupported protocol version']) {
    assert.equal(code.includes(marker), true, marker);
  }
  // stdio MCP servers are impossible on this engine: the transport that spawns a process must not be
  // in the bundle at all.
  assert.equal(code.includes('cross-spawn'), false);
  assert.equal(code.includes('node:child_process'), false);
  // The agent's fetch tool ships too: the harness reaches the network through the Kotlin HTTP client
  // rather than a firmware `/system/bin/curl`, so a bundle built without fetch-tool.mjs would leave
  // the agent with no fetch at all. The markers are the tool's own name and its scheme rule.
  assert.equal(code.includes('fetch_url'), true, 'the fetch tool is in the bundle');
  assert.equal(code.includes('Only http and https URLs can be fetched'), true, 'and so is its scheme rule');
  // The agent's run_script tool ships too: without it the agent can never run a script it wrote,
  // because Android refuses a direct exec of an app-written file (SELinux W^X in app storage, a
  // noexec mount for shared storage) and the agent's own `./tool.sh` is a string the app never sees.
  // The markers are the tool's own name, its interpreter, and the reason it exists.
  assert.equal(code.includes('run_script'), true, 'the run_script tool is in the bundle');
  assert.equal(code.includes('the only way to run a file the app itself wrote'), true, 'and so is its reason for existing');
  // The flow layer ships too: typed state with reducers, deterministic supersteps and one
  // checkpoint per boundary in the app database. The markers are the guards a bundle built without
  // flow.mjs would lose, the table names flow-store.mjs creates, the frames the host emits, and the
  // example flow's own script — so a build that dropped any of the three modules fails here.
  assert.equal(code.includes('flow_max_steps'), true, 'the flow engine is in the bundle');
  assert.equal(code.includes('flow_checkpoints'), true, 'and so is the store schema it writes');
  assert.equal(code.includes('flow.step.started'), true, 'and the frames the host emits');
  assert.equal(code.includes('pi-flow-probe.sh'), true, 'and the example flow that is runnable on device');
  // The new ops answer on the real durable host of the shipped bundle, not just in source.
  for (const name of ['sessions', 'usage', 'queue', 'exportSession']) assert.equal(typeof scope.PiNative[name], 'function', name);
  const db = new DatabaseSync(':memory:');
  const vault = new Map();
  scope.AndroidNative.postMessage = input => {
    const request = JSON.parse(input); messages.push(request);
    if (!request.op) return;
    queueMicrotask(() => {
      try {
        const { op, args, id } = request;
        const params = (args.params ?? []).map(p => p?.base64 ? Buffer.from(p.base64, 'base64') : p);
        let value = null;
        if (op === 'credential.list') value = [...vault].map(([provider, payload]) => ({ provider, payload }));
        else if (op === 'credential.put') vault.set(args.provider, args.payload);
        else if (op === 'credential.delete') vault.delete(args.provider);
        else if (op === 'sql.exec') db.exec(args.sql);
        else if (op === 'sql.begin') db.exec('BEGIN IMMEDIATE');
        else if (op === 'sql.commit') db.exec('COMMIT');
        else if (op === 'sql.rollback') db.exec('ROLLBACK');
        else if (op === 'sql.run') db.prepare(args.sql).run(...params);
        else if (op === 'sql.get') value = db.prepare(args.sql).get(...params) ?? null;
        else if (op === 'sql.all') value = db.prepare(args.sql).all(...params);
        // The app-owned command store and the MCP server store, as the Kotlin host answers them.
        else if (op === 'file.list') value = { entries: [] };
        else if (op === 'mcp.servers') value = { servers: [] };
        else if (op === 'mcp.save') value = null;
        else if (op !== 'sql.close') throw new Error(op);
        scope.nativeReply(JSON.stringify({ id, ok: true, value }));
      } catch (error) { scope.nativeReply({ id: request.id, ok: false, error: error.message }); }
    });
  };
  try {
    const ready = await scope.PiNative.init();
    assert.equal(ready.ready, true);
    assert.equal(ready.probe.kind, 'app.native.probe');
    assert.equal(messages.find(m => m.event === 'ready').value.probe.id, ready.probe.id);
    const result = await scope.PiNative.send({ apiKey: 'browser-test-key', modelId: 'gpt-4.1', prompt: 'test', requestId: 'browser-request' });
    assert.equal(result.status, 'unanswered');
    assert.equal(authorization, 'Bearer browser-test-key', JSON.stringify(await scope.PiNative.snapshot()));
    assert.equal(vault.get('openai').includes('browser-test-key'), true);
    // The tool is not merely bundled: the request the shipped runtime built offers it to the model.
    assert.notEqual(requestBody, undefined, 'the outbound request carried the offered tools');
    assert.ok(requestBody.includes('fetch_url'), requestBody.slice(0, 400));
    assert.ok(requestBody.includes('saveToPath'), 'the workspace-save argument is offered too');
    // …and the script runner, with the reason it exists, reaches the model the same way.
    assert.ok(requestBody.includes('run_script'), 'the agent is offered the script runner too');
    assert.ok(requestBody.includes('noexec'), 'and the reason a direct exec cannot work travels with it');
    // The shipped bundle projects the transcript itself: a `message` frame carries rows the UI
    // merges, so the Kotlin host never re-reads the whole conversation for a batch.
    const rowFrame = messages.find(m => m.event === 'message' && Array.isArray(m.value?.rows) && m.value.rows.some(row => row.role === 'user'));
    assert.notEqual(rowFrame, undefined, 'the bundle attaches incremental transcript rows to the message event');
    // The flow layer is live in the packaged asset, not merely a string inside it: reading the thread
    // list creates its two tables in the same database the durable engine just wrote to, and the
    // catalogue is readable through the shipped host. (Property comparisons, not deepEqual: the
    // bundle runs in its own realm in this test.)
    const flowThreads = await scope.PiNative.flowThreads();
    assert.equal(flowThreads.threads.length, 0);
    const catalogue = await scope.PiNative.flows();
    assert.equal(catalogue.flows.length, 1);
    assert.equal(catalogue.flows[0].name, 'workspace-script-probe');
    assert.equal(catalogue.flows[0].steps.join(','), 'probe,write,confirm,run,skip');
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM sqlite_master WHERE name IN ('flow_checkpoints', 'flow_threads')").get().n, 2);
    await scope.PiNative.close();
  } finally { db.close(); await rm(scratch, { force: true }); }
});

test('the packaged asset is byte-for-byte what the current source builds', async () => {
  // The asset Gradle ships is a build product, and a stale one silently disables whatever tool or op
  // the source gained since. Build the current source to a scratch path and compare it to the
  // tracked asset — WITHOUT rebuilding the tracked asset, which is what makes this a real staleness
  // check (overwriting it first would only ever compare the asset against itself) and what keeps the
  // suite from racing on, or dirtying, the file it asserts about.
  const scratch = scratchPath('freshness');
  try {
    buildBundle({ PI_RUNTIME_OUT: scratch });
    const [fresh] = await Promise.all([readFile(scratch)]);
    assert.ok(fresh.length > 0, 'the build produced an empty bundle');
    assert.equal(
      digest(fresh),
      '1064cc3fd0d52d496e71f4f86035d0c87df8af3ea742d29328d7ed77dea55ef5',
      'the runtime build changed; rebuild app/src/main/assets/native-runtime/runtime.js',
    );
    const shipped = await readFile(SHIPPED_BUNDLE);
    assert.equal(digest(shipped), digest(fresh), 'the packaged asset is stale: rebuild native-runtime/runtime.js');
    assert.equal(shipped.includes('run_script'), true, 'the packaged asset predates the script runner');
    assert.equal(shipped.includes('flow_checkpoints'), true, 'the packaged asset predates the flow layer');
    assert.equal(shipped.includes('pi-flow-probe.sh'), true, 'and the example flow it can run');
  } finally {
    await rm(scratch, { force: true });
  }
});
