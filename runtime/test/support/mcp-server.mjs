// A real Streamable-HTTP MCP server for the runtime tests.
//
// `initialize` answers with JSON (the spec's ordinary reply) and `tools/list` / `tools/call` answer
// with `text/event-stream`. The SSE replies are deliberate: the SSE reader is the only code path in
// the transport that touches the Node `Buffer` global, so a stubbed JSON-only server would pass even
// where the WebView bundle fails.
import { createServer } from 'node:http';

export async function startMockMcpServer({ name = 'mock', version = '1.0.0', instructions, tools = [], failWith = null, callResult } = {}) {
  const requests = [];
  const server = createServer((req, res) => {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      if (failWith !== null) {
        res.writeHead(failWith).end(JSON.stringify({ error: 'deliberate failure' }));
        return;
      }
      if (req.method === 'GET') { res.writeHead(405).end(); return; }
      let message;
      try { message = JSON.parse(body); } catch { res.writeHead(400).end(); return; }
      requests.push(message);
      const reply = result => res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: message.id, result }));
      const sse = result => res.writeHead(200, { 'content-type': 'text/event-stream' }).end(`event: message\ndata: ${JSON.stringify({ jsonrpc: '2.0', id: message.id, result })}\n\n`);
      switch (message.method) {
        case 'initialize':
          return reply({
            protocolVersion: '2025-06-18',
            capabilities: { tools: {} },
            serverInfo: { name, version },
            ...(instructions === undefined ? {} : { instructions }),
          });
        case 'notifications/initialized':
          res.writeHead(202).end();
          return;
        case 'tools/list':
          return sse({ tools });
        case 'tools/call':
          return sse(callResult ? callResult(message.params) : { content: [{ type: 'text', text: `echo:${message.params.arguments?.text ?? ''}` }] });
        default:
          res.writeHead(404).end();
      }
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    url: `http://127.0.0.1:${port}/mcp`,
    requests,
    methods: () => requests.filter(request => typeof request.method === 'string' && !request.method.startsWith('notifications/')).map(request => request.method),
    close: () => new Promise(resolve => { server.closeAllConnections?.(); server.close(resolve); }),
  };
}

/** A local port with nothing listening on it: the honest connection failure. */
export async function closedPort() {
  const server = createServer(() => {});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  await new Promise(resolve => server.close(resolve));
  return `http://127.0.0.1:${port}/mcp`;
}
