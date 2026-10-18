// Where an agent-authored flow lives, and how the host finds it.
//
// A flow the agent authors is plain JSON written into its own workspace: `<workspace>/.pi-flows/*.json`.
// This module is the one place that reads that directory and turns each file into a definition the
// existing engine (`flow.mjs`) runs — through the one compiler that already exists (`flow-spec.mjs`).
//
// WHY A DIRECTORY OF FILES, AND NOT AN OP THE MODEL CALLS
// The agent already has a file write tool: authoring a flow needs no new capability, only a
// convention for where the file goes and a way to find it again. A directory keeps that convention
// visible (the agent can list it), diffable and inspectable, and it is exactly what the host reads
// at `init()` so a spec compiled here is in the SAME registry the runner resolves `resumeFlow` by
// name from.
//
// ONE BAD FILE MUST NOT TAKE THE RUNTIME DOWN
// Every failure is collected, never thrown: a directory that does not exist yet means nothing was
// authored, a file that cannot be read is one error, and a file that is not a valid spec is one
// error carrying the file's path AND the compiler's own coded message (a `SpecError`), so the agent
// can fix its own file from the message alone. The valid specs beside it still compile and register.
//
// The whole module runs in the WebView: it reaches no Node API, only the injected bridge `call`.

import { SpecError, compileFlowSpec } from './flow-spec.mjs';

/** The directory inside the workspace an agent-authored flow spec lives in. */
export const FLOW_SPEC_DIR = '.pi-flows';
/** Only these files are read; anything else in the directory is left alone. */
export const FLOW_SPEC_EXTENSION = '.json';

/** The directory a workspace's specs live in, resolved without a leading duplicate slash. */
export function flowSpecDir(workspace) {
  const base = typeof workspace === 'string' ? workspace.trim().replace(/\/+$/, '') : '';
  return base === '' ? `/${FLOW_SPEC_DIR}` : `${base}/${FLOW_SPEC_DIR}`;
}

/** A directory that has simply never been written to is empty, not broken. */
const MISSING_DIR = /not_found|no such file|not_directory|not a directory|enoent/i;

/** Spec files, in a stable order, so two loads of the same directory produce the same registry. */
const specFilesOf = entries => entries
  .filter(entry => entry?.kind === 'file' && String(entry.name ?? '').toLowerCase().endsWith(FLOW_SPEC_EXTENSION))
  .map(entry => String(entry.name))
  .sort((left, right) => (left < right ? -1 : left > right ? 1 : 0));

/**
 * Read and compile every spec under `<workspace>/.pi-flows`.
 *
 * @returns `{ dir, specs, errors, signature }`:
 *   * `specs` — `{ name, path, definition }` for each file that compiled, in file-name order;
 *   * `errors` — `{ path, name, code, message }` for each file that could not be read or compiled,
 *     where `message` already names the file and states the compiler's own coded reason verbatim;
 *   * `signature` — a value that changes exactly when the set of spec files or their text changes,
 *     so a caller can tell whether the registry it built is still current.
 */
export async function loadFlowSpecs(call, workspace) {
  const dir = flowSpecDir(workspace);
  const specs = [];
  const errors = [];
  const files = [];
  if (typeof call !== 'function') return { dir, specs, errors, signature: 'no-bridge' };

  let listing;
  try {
    listing = await call('file.list', { path: dir });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (MISSING_DIR.test(message)) return { dir, specs, errors, signature: 'empty' };
    errors.push({ path: dir, name: null, code: 'flow_spec_dir_unreadable', message: `${dir}: the spec directory could not be listed: ${message}` });
    return { dir, specs, errors, signature: `unreadable:${message}` };
  }

  for (const name of specFilesOf(Array.isArray(listing?.entries) ? listing.entries : [])) {
    const path = `${dir}/${name}`;
    let text;
    try {
      text = await call('file.read', { path });
    } catch (error) {
      errors.push({ path, name, code: 'flow_spec_unreadable', message: `${path}: the spec file could not be read: ${error instanceof Error ? error.message : String(error)}` });
      continue;
    }
    const body = typeof text === 'string' ? text : String(text ?? '');
    files.push(`${path}\u0000${body}`);
    try {
      // The file's own name is only for reporting: the flow's name is what the spec declares, and a
      // spec that cannot declare one is refused by the compiler like any other mistake.
      const definition = compileFlowSpec(body);
      specs.push({ name: definition.name, path, definition });
    } catch (error) {
      if (error instanceof SpecError) {
        errors.push({ path, name, code: error.code, message: `${path}: ${error.code}: ${error.message}` });
      } else {
        errors.push({ path, name, code: 'flow_spec_failed', message: `${path}: the spec could not be compiled: ${error instanceof Error ? error.message : String(error)}` });
      }
    }
  }

  // The signature is the file set AND the bytes, so editing a spec in place (keeping its name) is a
  // change too: the compiled definition may differ even though the path did not.
  return { dir, specs, errors, signature: files.length === 0 ? 'empty' : files.join('\u0001') };
}
