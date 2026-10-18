// The agent-authorable flow layer, end to end under the REAL host.
//
// A spec the agent writes into `<workspace>/.pi-flows/<name>.json` is compiled into the SAME runner
// the host already built, so it is runnable, listed by `flows()` and — the reason registration and
// not on-the-fly compilation matters — RESUMABLE by name, because `resumeFlow` resolves a thread's
// flow from the runner's registry. A broken spec is loud (a named, coded error frame) but never
// fatal: the host still starts and the specs beside it still register. And a `fetch_url` step works
// because the host hands the flow its native transport, which the WebView's own fetch cannot be.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createNativeHost } from './support/host-native.mjs';
import { FLOW_SPEC_EXAMPLE } from '../src/flow-spec.mjs';

const DIR = '/workspace/.pi-flows';

const NOTE_SPEC = {
  version: 1,
  name: 'note-check',
  state: { note: { initial: '' } },
  entry: 'write',
  steps: {
    write: { capability: 'write_file', args: { path: 'note.txt', text: 'from a spec' }, next: 'read' },
    read: { capability: 'read_file', args: { path: 'note.txt' }, save: { note: 'text' } },
  },
};

const ASK_SPEC = {
  version: 1,
  name: 'ask-then-write',
  state: { wrote: { initial: null } },
  entry: 'ask',
  steps: {
    ask: {
      capability: 'ask',
      args: { prompt: 'Write the file?' },
      next: { if: { field: 'last.result.answer', eq: true }, then: 'yes', else: 'no' },
    },
    yes: { capability: 'write_file', args: { path: 'approved.txt', text: 'yes' }, save: { wrote: 'path' } },
    no: { capability: 'write_file', args: { path: 'declined.txt', text: 'no' }, save: { wrote: 'path' } },
  },
};

/** Open the real native host with the given spec files already in the workspace. */
async function openHost(files = {}, options = {}) {
  const { createPiNative } = await import('../src/host.mjs');
  const native = createNativeHost();
  for (const [path, text] of Object.entries(files)) native.files.set(path, text);
  const host = createPiNative(native.bridge, options);
  await host.init({ workspace: '/workspace' });
  return { native, host };
}

/** Spec files as the agent would leave them: an object is serialized, a string is already raw text. */
const specFiles = specs => Object.fromEntries(Object.entries(specs).map(([file, spec]) => [`${DIR}/${file}`, typeof spec === 'string' ? spec : JSON.stringify(spec)]));

test('a spec written into .pi-flows is registered, listed and runnable end to end', async () => {
  const { native, host } = await openHost(specFiles({ 'note.json': NOTE_SPEC }));
  try {
    const catalogue = await host.flows();
    assert.deepEqual(catalogue.flows.map(flow => flow.name).sort(), ['note-check', 'workspace-script-probe']);
    const spec = catalogue.flows.find(flow => flow.name === 'note-check');
    assert.deepEqual(spec.steps, ['write', 'read'], 'the spec is described exactly like a shipped flow');

    const result = await host.runFlow({ flow: 'note-check', threadId: 'spec-1' });
    assert.equal(result.status, 'done');
    assert.equal(result.state.note, 'from a spec');
    assert.deepEqual(result.steps, ['write', 'read']);
    // The step really reached the platform the way every other part of this runtime does: the bridge.
    assert.deepEqual(native.requests.filter(request => request.op === 'file.write').map(request => request.args.path), ['/workspace/note.txt']);
    assert.equal(native.files.get('/workspace/note.txt'), 'from a spec');
  } finally { await host.close(); native.db.close(); }
});

test('an ask step pauses and the SAME thread resumes by name through the host', async () => {
  // This is the registration payoff: `resumeFlow` finds the flow by the name the checkpoint recorded,
  // which only works because the spec is in the runner's registry and not compiled per run.
  const { native, host } = await openHost(specFiles({ 'ask.json': ASK_SPEC }));
  try {
    const paused = await host.runFlow({ flow: 'ask-then-write', threadId: 'spec-ask' });
    assert.equal(paused.status, 'paused');
    assert.deepEqual(paused.awaiting.steps, ['ask']);
    assert.deepEqual(paused.awaiting.values[0], { prompt: 'Write the file?' });

    const done = await host.resumeFlow({ threadId: 'spec-ask', value: true });
    assert.equal(done.status, 'done');
    assert.deepEqual(done.steps, ['ask', 'yes']);
    assert.equal(done.state.wrote, '/workspace/approved.txt');
    assert.equal(native.files.get('/workspace/approved.txt'), 'yes');

    const status = await host.flowStatus({ threadId: 'spec-ask' });
    assert.equal(status.status, 'done');
    assert.equal(status.flow, 'ask-then-write');
  } finally { await host.close(); native.db.close(); }
});

test('a fetch_url step works because the host hands the flow its native transport', async () => {
  const seen = [];
  const transport = async (url, init = {}) => {
    seen.push({ url, method: init.method });
    return { status: 200, statusText: 'OK', ok: true, headers: { 'content-type': 'text/plain' }, async text() { return 'remote body'; } };
  };
  const { native, host } = await openHost(specFiles({ 'net.json': FLOW_SPEC_EXAMPLE }), { fetch: transport });
  try {
    const result = await host.runFlow({ flow: 'check-endpoint', threadId: 'spec-net' });
    assert.equal(result.status, 'done');
    assert.equal(result.state.body, 'remote body');
    assert.equal(result.state.status, 200);
    assert.deepEqual(seen, [{ url: 'https://example.com/health', method: 'GET' }], 'the request left through the host transport');
    // The step still reaches the platform only through the bridge, exactly like the shipped flow.
    assert.equal(native.requests.some(request => request.op === 'shell.exec' && Array.isArray(request.args.command)), true);
  } finally { await host.close(); native.db.close(); }
});

test('a broken spec is loud and specific but never stops the host or hides the good spec', async () => {
  const { native, host } = await openHost(specFiles({
    'good.json': NOTE_SPEC,
    'broken.json': { version: 1, name: 'typo', state: { n: { initial: 0 } }, entry: 'a', steps: { a: { capability: 'shell', args: { command: 'true' }, next: 'ghost' } } },
    'not-json.json': '{ "name": ',
  }));
  try {
    // The host started: a bad spec file is not a fatal init error.
    assert.deepEqual((await host.flows()).flows.map(flow => flow.name).sort(), ['note-check', 'workspace-script-probe']);

    const errors = native.events.filter(event => event.event === 'error' && event.operation === 'flow-specs');
    assert.equal(errors.length, 2, 'one error frame per broken file');
    const byFile = Object.fromEntries(errors.map(error => [error.file, error]));
    assert.equal(byFile[`${DIR}/broken.json`].code, 'spec_unknown_step');
    assert.ok(byFile[`${DIR}/broken.json`].message.includes(`${DIR}/broken.json`), byFile[`${DIR}/broken.json`].message);
    assert.ok(byFile[`${DIR}/broken.json`].message.includes('spec_unknown_step'), byFile[`${DIR}/broken.json`].message);
    assert.ok(byFile[`${DIR}/broken.json`].message.includes('"ghost"'), byFile[`${DIR}/broken.json`].message);
    assert.equal(byFile[`${DIR}/not-json.json`].code, 'spec_invalid_json');
    // The good spec still works through the host that skipped the broken ones.
    assert.equal((await host.runFlow({ flow: 'note-check', threadId: 'spec-good' })).status, 'done');
  } finally { await host.close(); native.db.close(); }
});

test('a spec written AFTER init is picked up without restarting the runtime', async () => {
  const { native, host } = await openHost({});
  try {
    assert.deepEqual((await host.flows()).flows.map(flow => flow.name), ['workspace-script-probe']);
    // The agent writes its spec with its ordinary file tool, then runs it — no restart.
    native.files.set(`${DIR}/late.json`, JSON.stringify(NOTE_SPEC));
    const result = await host.runFlow({ flow: 'note-check', threadId: 'spec-late' });
    assert.equal(result.status, 'done');
    assert.equal(result.state.note, 'from a spec');
    assert.equal((await host.flows()).flows.some(flow => flow.name === 'note-check'), true);
  } finally { await host.close(); native.db.close(); }
});
