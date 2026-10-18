// Browser stand-in for `node:http`, backing pi-ai's loopback OAuth callback servers.
//
// A WebView cannot listen on a TCP port, so the socket work happens in the Kotlin host
// (`backends/OAuthLoopbackServer.kt`). This module implements just the surface the upstream
// callback servers use: `createServer(handler)`, `server.on/once/off`, `listen(port, host, cb)`,
// `address()`, `close()` and `closeAllConnections()`. Incoming browser requests arrive as
// `oauth.request` bridge events; the handler's `response.end()` is sent back with `oauth.respond`.
//
// The shim is inert until [`installHttpBridge`] receives the app bridge, so importing the OAuth
// flow graph in a plain Node process keeps using the real `node:http`.

let bridge = null;
const servers = new Map();
let sequence = 0;

/** Wire the WebView bridge once, at bundle start. */
export function installHttpBridge(host) {
  bridge = host;
  host.subscribe(event => {
    if (!event || event.event !== 'oauth.request') return;
    const server = servers.get(event.serverId);
    if (!server) {
      void host.call('oauth.respond', {
        serverId: event.serverId,
        requestId: event.requestId,
        status: 503,
        contentType: 'text/plain; charset=utf-8',
        body: 'Sign-in listener is no longer active.',
      }).catch(() => undefined);
      return;
    }
    server.dispatch(event);
  });
}

/** True once the Kotlin-backed listener is available; the callbacks then use the real socket. */
export function isHttpBridgeInstalled() {
  return bridge !== null;
}

class LoopbackResponse {
  constructor(server, requestId) {
    this.server = server;
    this.requestId = requestId;
    this.status = 200;
    this.headers = {};
    this.done = false;
  }

  writeHead(status, headers) {
    this.status = status;
    if (headers && typeof headers === 'object') this.headers = { ...headers };
    return this;
  }

  setHeader(name, value) {
    this.headers[String(name).toLowerCase()] = String(value);
    return this;
  }

  end(body = '') {
    if (this.done) return this;
    this.done = true;
    const text = typeof body === 'string' ? body : new TextDecoder().decode(body);
    void this.server.host.call('oauth.respond', {
      serverId: this.server.id,
      requestId: this.requestId,
      status: this.status,
      contentType: this.headers['content-type'] ?? 'text/html; charset=utf-8',
      body: text,
    }).catch(() => undefined);
    return this;
  }
}

class LoopbackServer {
  constructor(handler) {
    this.handler = handler;
    this.listeners = new Map();
    this.host = null;
    this.id = `oauth-${++sequence}`;
    this.port = null;
    this.bound = null;
    this.closed = false;
    this.clients = new Set();
  }

  on(event, listener) {
    if (!this.listeners.has(event)) this.listeners.set(event, new Set());
    this.listeners.get(event).add(listener);
    return this;
  }

  once(event, listener) {
    const wrapped = (...args) => { this.off(event, wrapped); listener(...args); };
    return this.on(event, wrapped);
  }

  off(event, listener) {
    this.listeners.get(event)?.delete(listener);
    return this;
  }

  removeListener(event, listener) {
    return this.off(event, listener);
  }

  emit(event, ...args) {
    for (const listener of [...(this.listeners.get(event) ?? [])]) {
      try { listener(...args); } catch { /* listener failures must not stop the listener loop */ }
    }
    return true;
  }

  /** Called by the bridge shim for one parsed browser request. */
  dispatch(event) {
    if (this.closed) return;
    const request = {
      method: event.method ?? 'GET',
      url: event.target ?? '/',
      headers: event.headers ?? {},
    };
    const response = new LoopbackResponse(this, event.requestId);
    this.clients.add(event.requestId);
    try {
      this.handler(request, response);
    } catch (error) {
      if (!response.done) {
        response.status = 500;
        response.end(`Sign-in handler failed: ${error?.message ?? String(error)}`);
      }
    }
  }

  listen(port = 0, hostname = '127.0.0.1', callback) {
    if (!bridge) {
      const error = new Error('OAuth loopback listener is unavailable in this build');
      error.code = 'ENOTSUP';
      queueMicrotask(() => this.emit('error', error));
      return this;
    }
    this.host = bridge;
    servers.set(this.id, this);
    const requested = Number.isFinite(Number(port)) ? Number(port) : 0;
    void bridge.call('oauth.listen', { serverId: this.id, port: requested, host: hostname })
      .then(bound => {
        if (this.closed) return;
        this.port = bound?.port ?? requested;
        this.bound = { address: hostname, family: 'IPv4', port: this.port };
        callback?.();
      })
      .catch(error => {
        if (this.closed) return;
        this.emit('error', error instanceof Error ? error : new Error(String(error)));
      });
    return this;
  }

  address() {
    return this.bound;
  }

  close(callback) {
    this.closed = true;
    servers.delete(this.id);
    this.clients.clear();
    if (this.host) void this.host.call('oauth.close', { serverId: this.id }).catch(() => undefined);
    callback?.();
    return this;
  }

  closeAllConnections() {
    if (this.host) void this.host.call('oauth.closeAll', { serverId: this.id }).catch(() => undefined);
    this.clients.clear();
  }
}

export function createServer(handler) {
  return new LoopbackServer(handler);
}
