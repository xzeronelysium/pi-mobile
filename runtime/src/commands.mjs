// App-owned command documents: the durable replacement for the CLI's command discovery.
//
// The CLI engine discovered slash commands by loading installed packages and reading its own
// config tree. Pi Durable has no package loader and no Node, so a command is a plain markdown file
// the app owns: `<workspace>/.pi-skills/<name>.md` and `<workspace>/.pi-templates/<name>.md`.
//
// A skill contributes a durable prompt section (see `skills.mjs`); a template is inserted into the
// composer and sent as an ordinary model turn. Nothing here is sent to the model — this module only
// parses documents and projects the rows the pickers render.

export const SKILL_KIND = 'skill';
export const TEMPLATE_KIND = 'template';

/** Directory names inside the workspace, one per command kind. */
export const COMMAND_DIRS = {
  [SKILL_KIND]: '.pi-skills',
  [TEMPLATE_KIND]: '.pi-templates',
};

/** The `skill:` invocation form, kept because the CLI and the pre-cut app used `/skill:<name>`. */
export const SKILL_COMMAND_PREFIX = 'skill:';

/** Lowercase, no spaces: what a slash invocation can actually name. */
const NAME_PATTERN = /^[a-z0-9][a-z0-9._-]{0,63}$/;

/** Optional leading frontmatter block. Only flat `key: value` pairs are read. */
const FRONTMATTER = /^---\r?\n([\s\S]*?)\r?\n---[ \t]*\r?\n?/;

/**
 * Parse one app-owned command document.
 *
 * The body is the skill's instructions (or the template's prompt text) and is never rewritten: it
 * is what the section renders, and the stored user entry stays the raw string the user authored.
 *
 * @returns the command, or null when the document cannot name itself or carries no body.
 */
export function parseCommandDocument(text, fallbackName = '', kind = SKILL_KIND) {
  if (typeof text !== 'string' || text.trim() === '') return null;
  const match = FRONTMATTER.exec(text);
  const fields = match ? frontmatterFields(match[1]) : {};
  const body = (match ? text.slice(match[0].length) : text).trim();
  if (body === '') return null;
  const name = commandName(fields.name, fallbackName);
  if (name === '') return null;
  const description = singleLine(fields.description) || firstLine(body);
  const argumentHint = singleLine(fields['argument-hint'] ?? fields.argumentHint ?? fields.arguments);
  return {
    kind: kind === TEMPLATE_KIND ? TEMPLATE_KIND : SKILL_KIND,
    name,
    description,
    ...(argumentHint ? { argumentHint } : {}),
    instructions: body,
  };
}

/**
 * The command's name: the frontmatter `name` when it is usable, else the file's own base name.
 * A name a slash invocation could not address (spaces, capitals, punctuation) is not silently
 * repaired — the file name is used instead, and when neither works the document is dropped.
 */
export function commandName(candidate, fallbackName = '') {
  const direct = String(candidate ?? '').trim();
  if (NAME_PATTERN.test(direct)) return direct;
  const derived = String(fallbackName ?? '')
    .replace(/\.md$/i, '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-')
    .replace(/^[^a-z0-9]+/, '')
    .replace(/[^a-z0-9]+$/, '')
    .slice(0, 64);
  return NAME_PATTERN.test(derived) ? derived : '';
}

/**
 * The invocation a composer inserts and the dispatcher matches: `/name` for a skill, `/name` for a
 * template as well — both are named commands, and `/skill:name` stays accepted as typed input.
 */
export function invocationOf(command) {
  return `/${command.name}`;
}

/** The text selecting a row puts in the composer. A skill inserts its invocation; a template its prompt. */
export function insertTextOf(command) {
  if (command.kind === TEMPLATE_KIND) return command.instructions;
  return `${invocationOf(command)} `;
}

/**
 * The rows the pickers and the dispatcher share, ordered by name. The catalog is never truncated:
 * `maxVisible` belongs to the viewport, not to discovery.
 */
export function commandRows(commands) {
  return [...commands]
    .filter(command => command && command.name && command.instructions)
    .sort((left, right) => (left.name < right.name ? -1 : left.name > right.name ? 1 : 0))
    .map(command => ({
      kind: command.kind,
      name: command.name,
      invocation: invocationOf(command),
      description: command.description ?? '',
      argumentHint: command.argumentHint ?? '',
      insert: insertTextOf(command),
    }));
}

/** Command names in a `Set` for exact lookup by the dispatcher. */
export function commandNames(commands) {
  return new Set(commands.map(command => command.name));
}

/**
 * Match a typed slash command against the catalog.
 *
 * Accepts `/name`, `/skill:name` and a bare `/name` followed by arguments. `arguments` is the rest
 * of the line, which a skill contributes to its own turn and a template ignores.
 *
 * @returns `{ name, arguments, prefix }`, or `{ name: '', arguments: '', prefix }` for an unknown
 *   slash command — the caller rejects it locally rather than sending it to the model.
 */
export function matchCommand(text, names) {
  const line = String(text ?? '').replace(/^\s+/, '');
  if (!line.startsWith('/')) return null;
  const token = line.slice(1).split(/\s/, 1)[0] ?? '';
  if (token === '') return null;
  const known = names instanceof Set ? names : new Set(names ?? []);
  const direct = token.startsWith(SKILL_COMMAND_PREFIX) ? token.slice(SKILL_COMMAND_PREFIX.length) : token;
  const rest = line.slice(1 + token.length).trim();
  const name = known.has(direct) ? direct : '';
  return { name, arguments: name ? rest : '', prefix: token, known: name !== '' };
}

/**
 * Read every command document of [kinds] out of the app-owned directories under [root].
 *
 * The files are read through the bridge (`file.list` / `file.read`), so this is host-side work: the
 * agent's own tools are irrelevant, and a workspace the agent may read is not required.
 *
 * A directory that does not exist yet is not an error — it means no command of that kind has been
 * added. A file that cannot be read or parsed is reported, never silently dropped.
 *
 * @returns `{ commands, rows, dirs, errors }`.
 */
export async function discoverCommands(call, root, kinds = [SKILL_KIND, TEMPLATE_KIND]) {
  const base = String(root ?? '').replace(/\/+$/, '');
  const commands = [];
  const errors = [];
  const dirs = [];
  for (const kind of kinds) {
    const directory = `${base}/${COMMAND_DIRS[kind]}`;
    dirs.push(directory);
    let listing;
    try {
      listing = await call('file.list', { path: directory });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      // A directory nothing has written to yet is empty, not broken.
      if (/not_found|No such file|not_directory|Not a directory/i.test(message)) continue;
      errors.push(`${directory}: ${message}`);
      continue;
    }
    const entries = Array.isArray(listing?.entries) ? listing.entries : [];
    for (const entry of entries) {
      if (entry?.kind !== 'file' || !/\.md$/i.test(String(entry.name ?? ''))) continue;
      const path = `${directory}/${entry.name}`;
      try {
        const text = await call('file.read', { path });
        const command = parseCommandDocument(text, entry.name, kind);
        if (command === null) errors.push(`${path}: no usable name or empty body`);
        else commands.push(command);
      } catch (error) {
        errors.push(`${path}: ${error instanceof Error ? error.message : String(error)}`);
      }
    }
  }
  return { commands, rows: commandRows(commands), dirs, errors };
}

function frontmatterFields(block) {
  const fields = {};
  for (const line of block.split(/\r?\n/)) {
    const separator = line.indexOf(':');
    if (separator <= 0) continue;
    const key = line.slice(0, separator).trim().toLowerCase();
    const value = line.slice(separator + 1).trim().replace(/^["']|["']$/g, '');
    if (key !== '') fields[key] = value;
  }
  return fields;
}

function singleLine(value) {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim();
  return text;
}

/** One line of description when the document did not write one: the first prose line of the body. */
function firstLine(body) {
  for (const line of body.split(/\r?\n/)) {
    const text = line.replace(/^#+\s*/, '').trim();
    if (text !== '') return text.slice(0, 160);
  }
  return '';
}
