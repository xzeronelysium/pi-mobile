// The loopback `node:http` shim that lets pi-ai's browser sign-in flows run in the WebView.
//
// The socket is owned by Kotlin (`OAuthLoopbackServer.kt`); this test drives the shim with a fake
// bridge that plays the Kotlin side, so the request/response contract between them is pinned.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, installHttpBridge, isHttpBridgeInstalled } from '../src/shims/node-http.mjs';

function fakeBridge({ listenError } = {}) {
  const calls = [];
  const listeners = new Set();
  return {
    calls,
    deliver: event => { for (const listener of [...listeners]) listener(event); },
    bridge: {
      call: async (op, args) => {
        calls.push({ op, args });
        if (op === 'oauth.listen') {
          if (listenError) throw listenError;
          return { port: args.port || 45123 };
        }
        if (op === 'oauth.respond' || op === 'oauth.close' || op === 'oauth.closeAll') return null;
        throw new Error(`Unexpected op ${op}`);
      },
      subscribe: listener => { listeners.add(listener); return () => listeners.delete(listener); },
      emit: () => {},
    },
  };
}

test('the shim exposes the node:http surface pi-ai callbacks need before a bridge is installed', () => {
  assert.equal(isHttpBridgeInstalled(), false);
  const server = createServer(() => {});
  assert.equal(server.address(), null);
  assert.equal(typeof server.once, 'function');
  assert.equal(typeof server.off, 'function');
  assert.equal(typeof server.listen, 'function');
  assert.equal(typeof server.close, 'function');
  assert.equal(typeof server.closeAllConnections, 'function');
});

test('listen binds through the bridge, delivers the browser request and returns the Kotlin response', async () => {
  const native = fakeBridge();
  installHttpBridge(native.bridge);
  assert.equal(isHttpBridgeInstalled(), true);
  const seen = [];
  const server = createServer((request, response) => {
    seen.push({ method: request.method, url: request.url });
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    response.end('<html>Signed in.</html>');
  });
  const port = await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
  assert.equal(port, 45123);
  assert.deepEqual(native.calls[0], { op: 'oauth.listen', args: { serverId: server.id, port: 0, host: '127.0.0.1' } });

  native.deliver({ event: 'oauth.request', serverId: server.id, requestId: 'req-1', method: 'GET', target: '/callback?code=abc&state=verifier' });
  assert.deepEqual(seen, [{ method: 'GET', url: '/callback?code=abc&state=verifier' }]);
  const response = native.calls.find(call => call.op === 'oauth.respond');
  assert.deepEqual(response.args, {
    serverId: server.id,
    requestId: 'req-1',
    status: 200,
    contentType: 'text/html; charset=utf-8',
    body: '<html>Signed in.</html>',
  });

  server.close();
  assert.equal(native.calls.some(call => call.op === 'oauth.close' && call.args.serverId === server.id), true);
  server.closeAllConnections();
  assert.equal(native.calls.some(call => call.op === 'oauth.closeAll'), true);
});

test('a port that cannot be bound surfaces as an error event with the Node error code', async () => {
  const failure = Object.assign(new Error('Address already in use'), { code: 'EADDRINUSE' });
  const native = fakeBridge({ listenError: failure });
  installHttpBridge(native.bridge);
  const server = createServer(() => {});
  const error = await new Promise(resolve => {
    server.listen(1455, '127.0.0.1', () => resolve(null));
    server.once('error', resolve);
  });
  assert.equal(error.code, 'EADDRINUSE');
  server.close();
});

test('a request for a closed listener is answered instead of hanging the browser', () => {
  const native = fakeBridge();
  installHttpBridge(native.bridge);
  native.deliver({ event: 'oauth.request', serverId: 'oauth-999', requestId: 'req-gone', method: 'GET', target: '/callback' });
  const response = native.calls.find(call => call.op === 'oauth.respond');
  assert.equal(response.args.status, 503);
  assert.match(response.args.body, /no longer active/);
});
