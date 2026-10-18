import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import path from 'node:path';

const dir = fileURLToPath(new URL('.', import.meta.url));
const shim = name => path.join(dir, 'src/shims', name);

// Browser stand-ins for the Node built-ins reachable from pi-ai's OAuth flows. The real
// implementations live in `src/shims/`; every other `node:*` specifier becomes an empty module so
// the bundle never needs a Node runtime. All of the replaced imports are guarded by
// `typeof process` / lazy imports upstream.
const SHIMS = {
  'node:http': shim('node-http.mjs'),
  'node:crypto': shim('node-crypto.mjs'),
};

const nodeBuiltins = {
  name: 'node-builtin-browser-shims',
  setup(context) {
    context.onResolve({ filter: /^node:/ }, args => (SHIMS[args.path] ? { path: SHIMS[args.path] } : { path: args.path, namespace: 'node-stub' }));
    context.onLoad({ filter: /.*/, namespace: 'node-stub' }, args => ({
      // Never executed: upstream only reaches these behind a `typeof process !== "undefined"` guard.
      contents: `export default undefined; // ${args.path} is unavailable in the Android WebView runtime`,
      loader: 'js',
    }));
  },
};

// `@earendil-works/pi-mcp`'s root re-exports `StdioTransport`, which imports `cross-spawn`
// (CommonJS). A CommonJS import is never considered side-effect free, so the stdio transport — and
// with it `child_process`, `path`, `fs` and `isexe` — cannot be tree-shaken out of a
// `platform: 'browser'` bundle. The package's `exports` map publishes no subpath for the transports
// either, so the two modules that do run in a WebView are resolved here instead.
// The package's `exports` map publishes no `./package.json` subpath either, and its `import`-only
// conditions are invisible to the CommonJS resolver, so the root comes from the ESM resolver.
const MCP_PACKAGE = path.dirname(path.dirname(fileURLToPath(import.meta.resolve('@earendil-works/pi-mcp'))));

const mcpBrowserEntry = {
  name: 'pi-mcp-browser-entry',
  setup(context) {
    context.onResolve({ filter: /^@earendil-works\/pi-mcp$/ }, () => ({ path: '@earendil-works/pi-mcp', namespace: 'pi-mcp-browser' }));
    context.onLoad({ filter: /.*/, namespace: 'pi-mcp-browser' }, () => ({
      // Exactly the surface `src/mcp.mjs` imports: the client core, the Streamable HTTP transport,
      // and the content projection. `stdio` and the OAuth helpers stay out — the former needs a
      // process, the latter opens its own callback server.
      contents: [
        `export { McpClient } from ${JSON.stringify(path.join(MCP_PACKAGE, 'dist/client.js'))};`,
        `export { StreamableHttpTransport, McpHttpError, McpAuthRequiredError, McpSessionExpiredError } from ${JSON.stringify(path.join(MCP_PACKAGE, 'dist/transports/streamable-http.js'))};`,
        `export { toLlmContent } from ${JSON.stringify(path.join(MCP_PACKAGE, 'dist/protocol/content.js'))};`,
        `export { McpError, JSON_RPC_ERROR_CODES } from ${JSON.stringify(path.join(MCP_PACKAGE, 'dist/protocol/jsonrpc.js'))};`,
      ].join('\n'),
      loader: 'js',
      // The generated entry imports absolute paths; without a resolve directory esbuild refuses to
      // look them up on the file system.
      resolveDir: MCP_PACKAGE,
    }));
  },
};

// The default destination is the asset Gradle packages. `PI_RUNTIME_OUT` lets a second test file
// build the same source to its own path, so two suites running in parallel never race on the
// shipped bundle.
const outfile = process.env.PI_RUNTIME_OUT
  ? path.resolve(dir, process.env.PI_RUNTIME_OUT)
  : '../app/src/main/assets/native-runtime/runtime.js';

await build({
  absWorkingDir: fileURLToPath(new URL('.', import.meta.url)),
  entryPoints: ['src/index.mjs'],
  outfile,
  bundle: true, platform: 'browser', format: 'iife', target: 'es2022',
  minifySyntax: true,
  define: { process: 'undefined' },
  // pi-mcp's SSE reader measures streamed events with the Node `Buffer` global; the injected
  // stand-in is the only `Buffer` in the bundle, and nothing else can see it.
  inject: [shim('buffer.mjs')],
  legalComments: 'inline', logLevel: 'info',
  plugins: [nodeBuiltins, mcpBrowserEntry],
});
