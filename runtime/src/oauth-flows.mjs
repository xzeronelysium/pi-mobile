// Static embedding of pi-ai's OAuth flows, mirroring upstream `bun-oauth.ts`.
//
// `providers/*` advertise OAuth metadata and load the implementation lazily through
// `auth/oauth/load.js`'s bundler-opaque dynamic import. In a WebView there is no module loader, so
// the dynamic specifier can never resolve. `registerBundledOAuthFlowLoaders` is upstream's
// documented seam for exactly this case; registering the real flow modules keeps the sign-in
// semantics upstream rather than reimplementing any provider's flow here.
//
// The `node:http` / `node:crypto` imports inside those flows are satisfied by the browser shims in
// `src/shims/` (esbuild aliases in `build.mjs`).

import { registerBunOAuthFlows } from '@earendil-works/pi-ai/bun-oauth';
import { installHttpBridge } from './shims/node-http.mjs';

let installed = false;

/** Idempotent: pi-ai keeps one module-level loader registry. */
export function installOAuthFlows() {
  if (installed) return false;
  registerBunOAuthFlows();
  installed = true;
  return true;
}

/**
 * Wire the loopback callback shim to the WebView bridge. Browser entry only — a Node process
 * (runtime tests) keeps using the real `node:http`.
 */
export function installLoopbackBridge(bridge) {
  installHttpBridge(bridge);
}
