// Browser stand-in for the Node `Buffer` global.
//
// Only `Buffer.byteLength` is reachable from the code the WebView bundles: pi-mcp's SSE reader uses
// it to bound a streamed event's size, and Node's `Buffer` is not a global in a WebView. esbuild's
// `inject` replaces the free identifier, so nothing else in the bundle can see this object and no
// real `Buffer` is emulated.

export const Buffer = {
  /** UTF-8 length of [value], counting a surrogate pair as the four bytes it encodes to. */
  byteLength(value) {
    const text = typeof value === 'string' ? value : String(value);
    let bytes = 0;
    for (let index = 0; index < text.length; index++) {
      const code = text.charCodeAt(index);
      if (code < 0x80) bytes += 1;
      else if (code < 0x800) bytes += 2;
      else if (code >= 0xd800 && code < 0xdc00 && index + 1 < text.length) {
        bytes += 4;
        index++;
      } else bytes += 3;
    }
    return bytes;
  },
};
