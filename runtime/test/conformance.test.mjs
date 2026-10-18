// Runs Pi Durable's own ExecutionEnv conformance suite against AndroidExecutionEnv,
// backed by the Node reference bridge (the spec-in-code for the Kotlin adapter).

import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createEnvConformance } from '@earendil-works/pi-durable/testing';
import { createAndroidExecutionEnv } from '../src/env.mjs';
import { createNodeBridge } from './support/node-bridge.mjs';

const assertions = {
  ok(value, message) { assert.ok(value, message); },
  strictEqual(actual, expected, message) { assert.strictEqual(actual, expected, message); },
  deepEqual(actual, expected, message) { assert.deepStrictEqual(actual, expected, message); },
  partialDeepEqual(actual, expected, message) {
    if (typeof assert.partialDeepStrictEqual === 'function') assert.partialDeepStrictEqual(actual, expected, message);
    else assert.deepStrictEqual(actual, expected, message);
  },
  greaterThan(actual, expected, message) { assert.ok(actual > expected, message ?? `${actual} > ${expected}`); },
  async rejects(promise, messageIncludes) {
    await assert.rejects(promise, error => String(error?.message ?? error).includes(messageIncludes));
  },
};

const cases = createEnvConformance({
  assertions,
  async withEnv(use) {
    const dir = await mkdtemp(join(tmpdir(), 'pi-env-'));
    const temp = await mkdtemp(join(tmpdir(), 'pi-tmp-'));
    const bridge = createNodeBridge({ root: dir, tempRoot: temp });
    const env = createAndroidExecutionEnv({ call: bridge.call, cwd: dir, subscribe: bridge.subscribe });
    try {
      await use(env);
    } finally {
      await bridge.close();
      await rm(dir, { recursive: true, force: true });
      await rm(temp, { recursive: true, force: true });
    }
  },
  shell: ['/bin/sh', '-c'],
  symlinks: true,
});

for (const testCase of cases) {
  test(testCase.name, { timeout: testCase.timeoutMs ?? 30_000 }, async () => { await testCase.run(); });
}
