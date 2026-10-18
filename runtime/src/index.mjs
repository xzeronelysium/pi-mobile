import { createBridge } from './bridge.mjs';
import { createPiNative } from './host.mjs';
import { installLoopbackBridge } from './oauth-flows.mjs';
import { installFetchTransport } from './fetch.mjs';
const bridge = createBridge(window);
// pi-ai performs provider HTTP with the ambient fetch, and the WebView's own fetch cannot reach a
// provider API from the https://pi.local document origin: no provider grants that origin via CORS,
// so the request is refused before a model call. Install the Kotlin transport as the ambient fetch
// before the runtime starts, so the OAuth token exchange and dynamic model listing are covered too
// and not just the chat streams, which also get it as an explicit `fetch` option.
const transport = installFetchTransport(bridge);
window.PiNative = createPiNative(bridge, { fetch: transport });
// Loopback OAuth callbacks need the Kotlin listener; register it before any sign-in can start.
installLoopbackBridge(bridge);
bridge.emit({ type: 'loaded', runtime: 'pi-durable', version: '1.1.0' });
