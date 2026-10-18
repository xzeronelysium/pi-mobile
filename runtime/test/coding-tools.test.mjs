// Drives Pi Durable's own CodingTools (read/write/edit/bash) through AndroidExecutionEnv
// using pi-ai's faux provider, so the whole native tool path is verified offline.

import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BACKGROUND_CONTEXT } from '@earendil-works/chord/context';
import { createModels } from '@earendil-works/pi-ai/models';
import { fauxAssistantMessage, fauxProvider, fauxToolCall } from '@earendil-works/pi-ai/providers/faux';
import { Harness, MemoryStorage, createRegistry } from '@earendil-works/pi-durable';
import { CodingTools } from '@earendil-works/pi-durable/tools';
import { createAndroidExecutionEnv } from '../src/env.mjs';
import { createNodeBridge } from './support/node-bridge.mjs';

async function withHarness(run) {
  const context = BACKGROUND_CONTEXT;
  const dir = await mkdtemp(join(tmpdir(), 'pi-coding-'));
  const bridge = createNodeBridge({ root: dir, tempRoot: dir });
  const env = createAndroidExecutionEnv({ call: bridge.call, cwd: dir, subscribe: bridge.subscribe });
  const faux = fauxProvider();
  const models = createModels();
  models.setProvider(faux.provider);
  const registry = createRegistry();
  registry.install(CodingTools);
  const harness = await Harness.open(new MemoryStorage(), { models, registry, env: () => env }, context);
  try {
    await run({ context, dir, bridge, env, harness, faux });
  } finally {
    await harness.close(context).catch(() => {});
    await bridge.close();
    await rm(dir, { recursive: true, force: true });
  }
}

async function transcript(harness, context) {
  const root = await harness.root(context);
  const page = await root.entries({ order: 'ascending' }, 200, undefined, context);
  return page.items ?? page;
}

test('CodingTools read runs over AndroidExecutionEnv and its result is committed', async () => {
  await withHarness(async ({ context, dir, bridge, harness, faux }) => {
    await bridge.call('file.write', { path: join(dir, 'data.txt'), text: 'hello from android' });
    faux.setResponses([
      fauxAssistantMessage([fauxToolCall('read', { path: 'data.txt' })], { stopReason: 'toolUse' }),
      fauxAssistantMessage('The file says: hello from android'),
    ]);

    const root = await harness.root(context, {
      agent: { model: { provider: faux.provider.id, modelId: faux.getModel().id } },
    });
    const submission = await root.submit({ type: 'input', content: 'read data.txt', requestId: 'r1' }, context);
    const settled = await submission.wait(context);
    assert.equal(settled.status, 'done');

    const entries = await transcript(harness, context);
    const kinds = entries.map(entry => entry.kind);
    assert.ok(kinds.includes('pi.tool-result'), `expected a tool result, got ${JSON.stringify(kinds)}`);
    assert.ok(JSON.stringify(entries).includes('hello from android'), 'tool output reached the transcript');
    assert.ok(JSON.stringify(entries).includes('The file says'), 'final answer reached the transcript');
  });
});

test('CodingTools write and bash run against the native environment', async () => {
  await withHarness(async ({ context, dir, harness, faux }) => {
    faux.setResponses([
      fauxAssistantMessage([fauxToolCall('write', { path: 'notes/todo.md', content: 'buy milk' })], { stopReason: 'toolUse' }),
      // bash over /bin/sh -c in the reference bridge; the Kotlin adapter uses /system/bin/sh.
      fauxAssistantMessage([fauxToolCall('bash', { command: 'cat notes/todo.md' })], { stopReason: 'toolUse' }),
      fauxAssistantMessage('Done.'),
    ]);

    const root = await harness.root(context, {
      agent: { model: { provider: faux.provider.id, modelId: faux.getModel().id } },
    });
    const settled = await (await root.submit({ type: 'input', content: 'write a note then cat it', requestId: 'r2' }, context)).wait(context);
    assert.equal(settled.status, 'done');

    const entries = await transcript(harness, context);
    assert.ok(JSON.stringify(entries).includes('buy milk'), 'bash saw what write produced');
    assert.ok(entries.some(entry => entry.kind === 'pi.tool-result'), 'tools produced results');
  });
});

/**
 * The whole point, end to end: the model's own `bash` tool declares its timeout in SECONDS, and the
 * shell op takes MILLISECONDS. Passing the number straight through made `timeout: 12` a 12 ms
 * deadline — the command was killed on the spot and the tool reported "Command timed out after 12
 * seconds", which is what the user saw as bash being broken. This drives durable's real bash tool
 * through the real adapter over a recording bridge, so the assertion is on what the host would have
 * been given.
 */
test('a timeout the model asks for in seconds reaches the shell as milliseconds, end to end', async () => {
  const context = BACKGROUND_CONTEXT;
  const dir = await mkdtemp(join(tmpdir(), 'pi-bash-timeout-'));
  const bridge = createNodeBridge({ root: dir, tempRoot: dir });
  const calls = [];
  const call = async (op, args = {}) => { calls.push({ op, args }); return bridge.call(op, args); };
  const env = createAndroidExecutionEnv({ call, cwd: dir, subscribe: bridge.subscribe });
  const faux = fauxProvider();
  const models = createModels();
  models.setProvider(faux.provider);
  const registry = createRegistry();
  registry.install(CodingTools);
  const harness = await Harness.open(new MemoryStorage(), { models, registry, env: () => env }, context);
  try {
    faux.setResponses([
      fauxAssistantMessage([fauxToolCall('bash', { command: 'printf done', timeout: 12 })], { stopReason: 'toolUse' }),
      fauxAssistantMessage('ran'),
    ]);
    const root = await harness.root(context, {
      agent: { model: { provider: faux.provider.id, modelId: faux.getModel().id } },
    });
    const settled = await (await root.submit({ type: 'input', content: 'run it with a 12 second timeout', requestId: 'r-timeout' }, context)).wait(context);
    assert.equal(settled.status, 'done');

    const exec = calls.find(entry => entry.op === 'shell.exec');
    assert.equal(exec.args.timeoutMs, 12_000, 'twelve seconds is twelve thousand milliseconds, not twelve');
    const entries = await transcript(harness, context);
    assert.equal(JSON.stringify(entries).includes('Command timed out after'), false,
      'and nothing timed out: the deadline is twelve seconds away, not twelve milliseconds');
    assert.ok(JSON.stringify(entries).includes('done'), 'the command actually ran');
  } finally {
    await harness.close(context).catch(() => {});
    await bridge.close();
    await rm(dir, { recursive: true, force: true });
  }
});
