import test from 'node:test';
import assert from 'node:assert/strict';

test('bridge correlates concurrent replies and separates events', async () => {
  const { createBridge } = await import('../src/bridge.mjs');
  const messages = [];
  const scope = { AndroidNative: { postMessage: s => messages.push(JSON.parse(s)) } };
  const bridge = createBridge(scope);
  const first = bridge.call('file.read', { path: 'a' });
  const second = bridge.call('file.info', { path: 'b' });
  assert.notEqual(messages[0].id, messages[1].id);
  scope.nativeReply(JSON.stringify({ id: messages[1].id, ok: true, value: 2 }));
  scope.nativeReply(JSON.stringify({ id: messages[0].id, ok: true, value: 1 }));
  assert.deepEqual(await Promise.all([first, second]), [1, 2]);
  bridge.emit({ type: 'ready' });
  assert.deepEqual(messages[2], { event: 'ready' });
  const failure = bridge.call('file.read', {});
  scope.nativeReply(JSON.stringify({ id: messages[3].id, ok: false, error: { message: 'denied', code: 'EACCES' } }));
  await assert.rejects(failure, { message: 'denied', code: 'EACCES' });
  assert.equal(scope.nativeReply('broken'), false);
  assert.equal(scope.nativeReply('{"id":"unknown","ok":true}'), false);
});

test('nativeEvent routes host events to subscribers and survives a bad listener', async () => {
  const { createBridge } = await import('../src/bridge.mjs');
  const scope = { AndroidNative: { postMessage: () => {} } };
  const bridge = createBridge(scope);
  const seen = [];
  const unsubscribe = bridge.subscribe(event => seen.push(event));
  bridge.subscribe(() => { throw new Error('listener failure'); });

  assert.equal(scope.nativeEvent(JSON.stringify({ event: 'shell.output', requestId: 's1', stream: 'stdout', base64: 'aGk=' })), true);
  assert.deepEqual(seen, [{ event: 'shell.output', requestId: 's1', stream: 'stdout', base64: 'aGk=' }]);

  unsubscribe();
  scope.nativeEvent({ event: 'shell.output', requestId: 's2' });
  assert.equal(seen.length, 1, 'unsubscribed listener stopped receiving');
  assert.equal(scope.nativeEvent('not json'), false);
});
