// Helpers the JS and Python sandbox tools both read, so their contracts cannot drift apart. The
// verified output record (path, bytes, sha256) is what Kotlin's finalizeSandboxReport builds after
// checking the file system; the [outputs] block renders it identically for either interpreter.

/** The verified output records one run reported, whatever the worker's own shape. */
export function outputRecords(value) {
  const reported = Array.isArray(value?.outputs) ? value.outputs : [];
  return reported.filter(entry => entry !== null && typeof entry === 'object');
}

/** The `[outputs]` block, or null when the run declared no outputs — one line per verified file. */
export function renderOutputBlock(records) {
  if (records.length === 0) return null;
  return '[outputs]\n' + records.map(entry =>
    `${entry.path}  ${Number.isFinite(entry.bytes) ? `${entry.bytes} bytes` : 'size unknown'}` + `  sha256:${typeof entry.sha256 === 'string' ? entry.sha256.slice(0, 16) : '—'}`,
  ).join('\n');
}