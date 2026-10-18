// Browser stand-in for `node:crypto`, used by pi-ai's OAuth flows.
//
// The upstream flows import `randomBytes` (openai-chatgpt) to build the nonce, state and PKCE
// material. In the WebView the platform crypto object is already a CSPRNG, so the shim keeps the
// upstream code path byte-for-byte and only swaps the entropy source.
export function randomBytes(size) {
  const bytes = new Uint8Array(size);
  globalThis.crypto.getRandomValues(bytes);
  return bytes;
}

export function randomUUID() {
  return globalThis.crypto.randomUUID();
}

export function getRandomValues(array) {
  return globalThis.crypto.getRandomValues(array);
}
