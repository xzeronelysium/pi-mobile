// The agent-facing flow tools: list_flows, run_flow, resume_flow, flow_status.
//
// These are the whole authoring UX. A model writes a spec into its workspace, and from these four
// tools' DESCRIPTIONS alone it must be able to write the next one: the shape, the operator list, the
// reserved `last` field, the `$field` substitution rule, and the fact that an `ask` step pauses the
// run. And when a spec does not compile, the compiler's own message comes back unchanged — fixing
// the spec from that message is the loop, so nothing here may paraphrase it.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createRegistry } from '@earendil-works/pi-durable';
import { fauxAssistantMessage, fauxToolCall } from '@earendil-works/pi-ai/providers/faux';
import { createFlowRunner } from '../src/flow.mjs';
import { createMemoryFlowStore } from '../src/flow-store.mjs';
import { compileFlowSpec, FLOW_SPEC_EXAMPLE } from '../src/flow-spec.mjs';
import {
  FLOW_EXTENSION_NAME,
  FLOW_STATUS_TOOL_NAME,
  FLOW_TOOL_NAMES,
  FlowToolError,
  LIST_FLOWS_TOOL_NAME,
  RESUME_FLOW_TOOL_NAME,
  RUN_FLOW_TOOL_NAME,
  createFlowTools,
} from '../src/flow-tool.mjs';
import { withFauxHarness } from './support/durable.mjs';

const textOf = result => (result.content ?? []).filter(part => part.type === 'text').map(part => part.text).join('');

const ASK_SPEC = {
  version: 1,
  name: 'ask-then-write',
  state: { wrote: { initial: null } },
  entry: 'ask',
  steps: {
    ask: { capability: 'ask', args: { prompt: 'Write it?' }, next: { if: { field: 'last.result.answer', eq: true }, then: 'yes', else: 'no' } },
    yes: { capability: 'write_file', args: { path: 'approved.txt', text: 'yes' }, save: { wrote: 'path' } },
    no: { capability: 'write_file', args: { path: 'declined.txt', text: 'no' }, save: { wrote: 'path' } },
  },
};

/** The bridge ops a compiled spec reaches, with the values a device would return. */
function fakeBridge() {
  const requests = [];
  const files = new Map();
  const call = async (op, args = {}) => {
    requests.push({ op, args });
    if (op === 'file.write') { files.set(args.path, args.text ?? ''); return null; }
    if (op === 'file.read') {
      if (!files.has(args.path)) throw Object.assign(new Error(`No such file: ${args.path}`), { code: 'not_found' });
      return files.get(args.path);
    }
    if (op === 'file.stat') return { exists: files.has(args.path), kind: 'file', size: String(files.get(args.path) ?? '').length, mtimeMs: 0 };
    if (op === 'shell.exec') return { stdout: 'ran', stderr: '', exitCode: 0, timedOut: false, cancelled: false };
    throw new Error(`Unexpected op ${op}`);
  };
  const fetch = async (url, init = {}) => {
    requests.push({ op: 'fetch', url, init });
    return { status: 200, statusText: 'OK', ok: true, headers: {}, async text() { return 'remote body'; } };
  };
  return { call, fetch, requests, files };
}

/**
 * Build the extension over a fixed set of spec files, exactly as the host builds it: one runner over
 * a real store, plus a `refresh` that reports the workspace's specs (and their errors) verbatim.
 */
function build({ specs = { check: { name: 'check-endpoint', spec: FLOW_SPEC_EXAMPLE } }, errors = [] } = {}) {
  const bridge = fakeBridge();
  const store = createMemoryFlowStore();
  const discovery = {
    dir: '/workspace/.pi-flows',
    specs: Object.entries(specs).map(([file, entry]) => ({ name: entry.name, path: `/workspace/.pi-flows/${file}.json`, definition: compileFlowSpec(entry.spec) })),
    errors,
  };
  const runner = () => createFlowRunner({ store, flows: discovery.specs.map(entry => entry.definition), context: { call: bridge.call, workspace: '/workspace', fetch: bridge.fetch } });
  const extension = createFlowTools({ runner, refresh: async () => discovery });
  const tool = name => extension.tools.find(entry => entry.name === name);
  const exec = (name, args = {}) => tool(name).execute(args, {}, {});
  return { bridge, store, discovery, extension, tool, exec };
}

/** The exact message the compiler produces for a bad spec — what "verbatim" must mean. */
function compilerMessage(spec) {
  try {
    compileFlowSpec(spec);
  } catch (error) {
    return error;
  }
  throw new Error('the fixture spec was supposed to be invalid');
}

test('the flow tools are one extension of four named tools with precise schemas', () => {
  const { extension, tool } = build();
  assert.equal(extension.name, FLOW_EXTENSION_NAME);
  assert.deepEqual(extension.tools.map(entry => entry.name), [...FLOW_TOOL_NAMES]);
  assert.deepEqual([...FLOW_TOOL_NAMES], [LIST_FLOWS_TOOL_NAME, RUN_FLOW_TOOL_NAME, RESUME_FLOW_TOOL_NAME, FLOW_STATUS_TOOL_NAME]);
  assert.deepEqual(tool(RUN_FLOW_TOOL_NAME).parameters.required, ['flow']);
  assert.deepEqual(tool(RESUME_FLOW_TOOL_NAME).parameters.required, ['threadId']);
  assert.deepEqual(tool(FLOW_STATUS_TOOL_NAME).parameters.required, ['threadId']);
  for (const name of FLOW_TOOL_NAMES) {
    assert.equal(tool(name).parameters.type, 'object');
    assert.equal(tool(name).parameters.additionalProperties, false);
  }
});

test('the descriptions alone teach the format: shape, operators, last, $field, and that ask pauses', () => {
  const { tool } = build();
  // The two tools whose job is authoring and discovery must carry the whole format.
  for (const name of [RUN_FLOW_TOOL_NAME, LIST_FLOWS_TOOL_NAME]) {
    const text = tool(name).description;
    // The spec shape and where it lives.
    assert.match(text, /\.pi-flows/, name);
    assert.match(text, /"version":\s*1/, `${name}: version`);
    assert.match(text, /"steps"/, `${name}: steps`);
    assert.match(text, /"entry"/, `${name}: entry`);
    assert.match(text, /"capability"/, `${name}: capability`);
    // The reserved outcome field and the substitution rule.
    assert.ok(text.includes('"last"'), `${name}: the reserved last field`);
    assert.ok(text.includes('$field.path'), `${name}: the $field substitution rule`);
    // The whole operator vocabulary.
    for (const operator of ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'exists', 'contains', 'startsWith', 'in', 'all', 'any', 'not']) {
      assert.ok(text.includes(operator), `${name}: operator ${operator}`);
    }
    // Every capability, by name.
    for (const capability of ['shell', 'run_script', 'read_file', 'write_file', 'fetch_url', 'ask']) {
      assert.ok(text.includes(capability), `${name}: capability ${capability}`);
    }
    // And the pause, which is the one runtime behaviour a model cannot infer from the shape.
    assert.match(text, /ask/i, `${name}: ask`);
    assert.match(text, /pause/i, `${name}: pauses`);
  }
  // The two continuation tools point at the format without repeating it.
  for (const name of [RESUME_FLOW_TOOL_NAME, FLOW_STATUS_TOOL_NAME]) {
    assert.match(tool(name).description, /\.pi-flows/, name);
  }
});

test('list_flows names what can run and where each spec came from', async () => {
  const { exec } = build({ specs: { check: { name: 'check-endpoint', spec: FLOW_SPEC_EXAMPLE }, ask: { name: 'ask-then-write', spec: ASK_SPEC } } });
  const result = await exec(LIST_FLOWS_TOOL_NAME);
  const text = textOf(result);
  assert.ok(text.includes('check-endpoint'), text);
  assert.ok(text.includes('ask-then-write'), text);
  assert.ok(text.includes('/workspace/.pi-flows/check.json'), text);
  assert.equal(result.isError, undefined);
  assert.deepEqual(result.details.flows, ['check-endpoint', 'ask-then-write']);
  assert.deepEqual(result.details.specs.map(spec => spec.name), ['check-endpoint', 'ask-then-write']);
});

test('run_flow runs a spec to completion and reports its state', async () => {
  const { exec } = build();
  const result = await exec(RUN_FLOW_TOOL_NAME, { flow: 'check-endpoint', threadId: 'tools-1' });
  assert.equal(result.isError, undefined);
  const text = textOf(result);
  assert.ok(text.includes('check-endpoint'), text);
  assert.ok(text.includes('done'), text);
  assert.equal(result.details.status, 'done');
  assert.equal(result.details.threadId, 'tools-1');
  assert.equal(result.details.state.body, 'remote body');
});

test('a paused run says so, and resume_flow continues it', async () => {
  const { exec } = build({ specs: { ask: { name: 'ask-then-write', spec: ASK_SPEC } } });
  const paused = await exec(RUN_FLOW_TOOL_NAME, { flow: 'ask-then-write', threadId: 'tools-ask' });
  assert.equal(paused.details.status, 'paused');
  assert.deepEqual(paused.details.awaiting.steps, ['ask']);
  // The model has to be told how to continue, and with which thread.
  assert.ok(textOf(paused).includes(RESUME_FLOW_TOOL_NAME), textOf(paused));
  assert.ok(textOf(paused).includes('tools-ask'), textOf(paused));

  const done = await exec(RESUME_FLOW_TOOL_NAME, { threadId: 'tools-ask', value: true });
  assert.equal(done.details.status, 'done');
  assert.equal(done.details.state.wrote, '/workspace/approved.txt');
});

test('flow_status reads a thread back, including what it awaits', async () => {
  const { exec } = build({ specs: { ask: { name: 'ask-then-write', spec: ASK_SPEC } } });
  await exec(RUN_FLOW_TOOL_NAME, { flow: 'ask-then-write', threadId: 'tools-status' });
  const status = await exec(FLOW_STATUS_TOOL_NAME, { threadId: 'tools-status' });
  assert.equal(status.details.status, 'paused');
  assert.deepEqual(status.details.awaiting.steps, ['ask']);
  assert.ok(textOf(status).includes('checkpoints'), textOf(status));
});

test('a spec that does not compile comes back VERBATIM, so the model can fix it', async () => {
  const broken = { version: 1, name: 'typo', state: { n: { initial: 0 } }, entry: 'a', steps: { a: { capability: 'shell', args: { command: 'true' }, next: 'ghost' } } };
  const error = compilerMessage(broken);
  const verbatim = `/workspace/.pi-flows/broken.json: ${error.code}: ${error.message}`;
  const { exec } = build({ errors: [{ path: '/workspace/.pi-flows/broken.json', name: 'broken.json', code: error.code, message: verbatim }] });

  const run = await exec(RUN_FLOW_TOOL_NAME, { flow: 'typo' });
  assert.equal(run.isError, true, 'an unresolvable flow is a fixable refusal, not a success');
  // The compiler's own words, unchanged, reach the model.
  assert.ok(textOf(run).includes(error.message), textOf(run));
  assert.ok(textOf(run).includes(error.code), textOf(run));
  assert.ok(textOf(run).includes('/workspace/.pi-flows/broken.json'), textOf(run));
  assert.deepEqual(run.details.errors, [{ path: '/workspace/.pi-flows/broken.json', code: error.code, message: verbatim }]);

  const list = await exec(LIST_FLOWS_TOOL_NAME);
  assert.ok(textOf(list).includes(error.message), textOf(list));
});

test('argument mistakes are refused with a coded reason, never guessed at', async () => {
  const { exec } = build();
  for (const flow of [undefined, '', '   ', 7]) {
    await assert.rejects(exec(RUN_FLOW_TOOL_NAME, { flow }), error => {
      assert.ok(error instanceof FlowToolError);
      assert.equal(error.code, 'invalid_flow');
      return true;
    });
  }
  for (const threadId of [undefined, '', '   ']) {
    await assert.rejects(exec(RESUME_FLOW_TOOL_NAME, { threadId }), error => error.code === 'invalid_thread');
    await assert.rejects(exec(FLOW_STATUS_TOOL_NAME, { threadId }), error => error.code === 'invalid_thread');
  }
});

test('the flow tools are offered to the model and a call really runs a spec', async () => {
  const { extension } = build();
  const registry = createRegistry();
  registry.install(extension);
  await withFauxHarness(async ({ context, faux, root }) => {
    const agent = await root.agent(context);
    for (const name of FLOW_TOOL_NAMES) assert.equal(agent.tools.some(tool => tool.name === name), true, `offered: ${agent.tools.map(tool => tool.name).join(', ')}`);
    assert.equal(agent.extensions.some(entry => entry.name === FLOW_EXTENSION_NAME), true);

    faux.setResponses([
      fauxAssistantMessage([fauxToolCall(RUN_FLOW_TOOL_NAME, { flow: 'check-endpoint', threadId: 'faux-1' })], { stopReason: 'toolUse' }),
      fauxAssistantMessage('ran it'),
    ]);
    const settled = await (await root.submit({ type: 'input', content: 'run check-endpoint', requestId: 'r1' }, context)).wait(context);
    assert.equal(settled.status, 'done');
    const page = await root.entries({ order: 'ascending' }, 200, undefined, context);
    const result = page.items.find(item => item.kind === 'pi.tool-result');
    assert.notEqual(result, undefined, JSON.stringify(page.items.map(item => item.kind)));
    assert.ok(JSON.stringify(result).includes('check-endpoint'), 'the flow really ran and its result reached the transcript');
  }, { registry });
});
