// Reading agent-authored flow specs out of `<workspace>/.pi-flows`.
//
// The point of this module is that the agent authors a flow by writing ONE file, so the loader has
// to be forgiving about the directory and precise about the file: no directory yet means nothing
// was authored; one unreadable file or one bad spec is ONE named, coded error that never hides the
// specs beside it and never throws.

import test from 'node:test';
import assert from 'node:assert/strict';
import { compileFlowSpec } from '../src/flow-spec.mjs';
import { FLOW_SPEC_DIR, flowSpecDir, loadFlowSpecs } from '../src/flow-specs.mjs';

/** A bridge with just the two file ops the loader uses, over an in-memory directory. */
function fakeCall(tree = {}, { listThrows = null } = {}) {
  const requests = [];
  const call = async (op, args = {}) => {
    requests.push({ op, args });
    if (op === 'file.list') {
      if (listThrows !== null) throw new Error(listThrows);
      const prefix = `${String(args.path).replace(/\/+$/, '')}/`;
      const entries = Object.keys(tree)
        .filter(path => path.startsWith(prefix))
        .map(path => ({ name: path.slice(prefix.length), kind: 'file', size: String(tree[path]).length, mtimeMs: 0 }));
      if (entries.length === 0) throw new Error(`No such file: ${args.path}`);
      return { entries };
    }
    if (op === 'file.read') {
      if (!Object.prototype.hasOwnProperty.call(tree, args.path)) throw new Error(`No such file: ${args.path}`);
      return tree[args.path];
    }
    throw new Error(`Unexpected op ${op}`);
  };
  return { call, requests };
}

const VALID = JSON.stringify({
  version: 1,
  name: 'probe',
  state: { n: { initial: 0 } },
  entry: 'a',
  steps: { a: { capability: 'shell', args: { command: 'true' } } },
});

test('the directory is <workspace>/.pi-flows, resolved without a doubled slash', () => {
  assert.equal(FLOW_SPEC_DIR, '.pi-flows');
  assert.equal(flowSpecDir('/workspace'), '/workspace/.pi-flows');
  assert.equal(flowSpecDir('/workspace/'), '/workspace/.pi-flows');
  assert.equal(flowSpecDir('   '), '/.pi-flows');
  assert.equal(flowSpecDir(null), '/.pi-flows');
});

test('a directory nothing was written to yet is empty, not an error', async () => {
  const { call } = fakeCall({});
  const discovery = await loadFlowSpecs(call, '/workspace');
  assert.deepEqual(discovery.specs, []);
  assert.deepEqual(discovery.errors, []);
  assert.equal(discovery.dir, '/workspace/.pi-flows');
  assert.equal(discovery.signature, 'empty');
});

test('a valid spec file is read and compiled into a definition the engine accepts', async () => {
  const { call } = fakeCall({ '/workspace/.pi-flows/probe.json': VALID, '/workspace/.pi-flows/notes.txt': 'ignored' });
  const discovery = await loadFlowSpecs(call, '/workspace');
  assert.equal(discovery.errors.length, 0);
  assert.equal(discovery.specs.length, 1);
  assert.equal(discovery.specs[0].name, 'probe');
  assert.equal(discovery.specs[0].path, '/workspace/.pi-flows/probe.json');
  // The definition is the real engine object: the compiler ran, it did not just read JSON.
  assert.equal(typeof discovery.specs[0].definition.steps.a, 'function');
  assert.deepEqual(discovery.specs[0].definition.entry, ['a']);
});

test('a broken spec names its file and carries the compiler\'s own coded message, and hides nothing', async () => {
  // A real compiler message, so the assertion is against the text a model would actually receive.
  let expected;
  try {
    compileFlowSpec({ version: 1, name: 'typo', state: { n: { initial: 0 } }, entry: 'a', steps: { a: { capability: 'shell', args: { command: 'true' }, next: 'ghost' } } });
  } catch (error) { expected = error; }
  assert.ok(expected, 'the fixture spec must really be invalid');

  const broken = JSON.stringify({ version: 1, name: 'typo', state: { n: { initial: 0 } }, entry: 'a', steps: { a: { capability: 'shell', args: { command: 'true' }, next: 'ghost' } } });
  const { call } = fakeCall({ '/workspace/.pi-flows/aaa-broken.json': broken, '/workspace/.pi-flows/zzz-good.json': VALID });
  const discovery = await loadFlowSpecs(call, '/workspace');

  // The good spec beside it still compiled: one bad file never hides another.
  assert.deepEqual(discovery.specs.map(spec => spec.name), ['probe']);
  assert.equal(discovery.errors.length, 1);
  const [error] = discovery.errors;
  assert.equal(error.name, 'aaa-broken.json');
  assert.equal(error.path, '/workspace/.pi-flows/aaa-broken.json');
  assert.equal(error.code, expected.code);
  assert.equal(error.message, `/workspace/.pi-flows/aaa-broken.json: ${expected.code}: ${expected.message}`);
});

test('invalid JSON is refused as text, without taking the other specs down', async () => {
  const { call } = fakeCall({ '/workspace/.pi-flows/bad.json': '{ "name": ', '/workspace/.pi-flows/good.json': VALID });
  const discovery = await loadFlowSpecs(call, '/workspace');
  assert.equal(discovery.errors.length, 1);
  assert.equal(discovery.errors[0].code, 'spec_invalid_json');
  assert.ok(discovery.errors[0].message.includes('/workspace/.pi-flows/bad.json'), discovery.errors[0].message);
  assert.deepEqual(discovery.specs.map(spec => spec.name), ['probe']);
});

test('an unreadable file is one named error, not a throw', async () => {
  // The listing succeeds but the read fails: model a directory whose entry then disappeared.
  const flaky = async (op) => {
    if (op === 'file.list') return { entries: [{ name: 'gone.json', kind: 'file' }] };
    if (op === 'file.read') throw new Error('No such file: gone.json');
    throw new Error(`Unexpected op ${op}`);
  };
  const discovery = await loadFlowSpecs(flaky, '/workspace');
  assert.equal(discovery.errors.length, 1);
  assert.equal(discovery.errors[0].name, 'gone.json');
  assert.equal(discovery.errors[0].code, 'flow_spec_unreadable');
  assert.ok(discovery.errors[0].message.includes('/workspace/.pi-flows/gone.json'), discovery.errors[0].message);
});

test('a directory that cannot be listed at all is reported once and never thrown', async () => {
  const { call } = fakeCall({}, { listThrows: 'Permission denied' });
  const discovery = await loadFlowSpecs(call, '/workspace');
  assert.equal(discovery.specs.length, 0);
  assert.equal(discovery.errors.length, 1);
  assert.equal(discovery.errors[0].name, null);
  assert.equal(discovery.errors[0].code, 'flow_spec_dir_unreadable');
  assert.ok(discovery.errors[0].message.includes('Permission denied'), discovery.errors[0].message);
});

test('the signature changes exactly when the set of spec files or their bytes changes', async () => {
  const one = await loadFlowSpecs(fakeCall({ '/workspace/.pi-flows/a.json': VALID }).call, '/workspace');
  const sameAgain = await loadFlowSpecs(fakeCall({ '/workspace/.pi-flows/a.json': VALID }).call, '/workspace');
  assert.equal(one.signature, sameAgain.signature, 'the same files produce the same signature');

  const edited = await loadFlowSpecs(fakeCall({ '/workspace/.pi-flows/a.json': VALID.replace('"true"', '"false"') }).call, '/workspace');
  assert.notEqual(edited.signature, one.signature, 'editing a spec in place is a change');

  const added = await loadFlowSpecs(fakeCall({ '/workspace/.pi-flows/a.json': VALID, '/workspace/.pi-flows/b.json': VALID.replace('probe', 'other') }).call, '/workspace');
  assert.notEqual(added.signature, one.signature, 'adding a spec is a change');
});

test('specs are read in file-name order, so two loads build the same registry', async () => {
  const tree = { '/workspace/.pi-flows/z.json': VALID.replace('probe', 'zed'), '/workspace/.pi-flows/a.json': VALID.replace('probe', 'aye') };
  const discovery = await loadFlowSpecs(fakeCall(tree).call, '/workspace');
  assert.deepEqual(discovery.specs.map(spec => spec.name), ['aye', 'zed']);
});

test('no bridge is an empty directory, not a crash', async () => {
  const discovery = await loadFlowSpecs(undefined, '/workspace');
  assert.deepEqual(discovery.specs, []);
  assert.deepEqual(discovery.errors, []);
});
