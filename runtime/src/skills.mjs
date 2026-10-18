// Skills as durable extensions.
//
// The CLI loaded a SKILL.md off disk while expanding `/skill:<name>` into the prompt. Pi Durable has
// no file loader and no engine-side skill concept, so a skill is instead a *named extension* that
// contributes a prompt `section(...)`: `runtime/src/commands.mjs` parses the app-owned document and
// this module turns it into the extension.
//
// The section only renders while the turn that invoked the skill is selected, so a skill contributes
// nothing to the system prompt of any other request. That is what keeps the stored user entry the
// raw `/name args` the user authored: the model receives the instructions, the transcript receives
// a projection (`chat/UserMessagePresentation.kt`).

import { matchCommand } from './commands.mjs';

/** Extension names are namespaced so the base selection can exclude every skill by name. */
export const SKILL_EXTENSION_PREFIX = 'skill:';

/**
 * Section keys are the extension's own namespace, but the registry reserves its shape: a key must
 * match `/^[a-z][a-z0-9_-]*$/` (a colon is not a legal section key) and must not be `instructions`.
 */
export const SKILL_SECTION_PREFIX = 'skill-';

/** The prompt section key one skill owns. */
export function skillSectionKey(name) {
  return SKILL_SECTION_PREFIX + name;
}

/**
 * One skill as a durable extension.
 *
 * Two skills may not collide, and a skill removed from the workspace must leave a section the
 * system-prompt planner can remove by key, so both the extension name and the section key are
 * namespaced.
 */
export function skillExtension(skill) {
  const key = skillSectionKey(skill.name);
  return {
    name: SKILL_EXTENSION_PREFIX + skill.name,
    sections: [
      {
        key,
        // The instructions verbatim; the section tag is the engine's, so the model sees
        // `<skill-name>…</skill-name>` around the skill's own text.
        render: () => skill.instructions,
      },
    ],
  };
}

export function isSkillExtension(extension) {
  return typeof extension?.name === 'string' && extension.name.startsWith(SKILL_EXTENSION_PREFIX);
}

/**
 * The extensions a conversation gets by default: everything installed that is not a skill.
 *
 * `HarnessSettings.extensions` is the default selection; leaving it unset would select *every*
 * installed extension, which would render every discovered skill's instructions on every request.
 * Skills are therefore installed for their identity and selected per turn instead.
 */
export function baseExtensions(snapshot) {
  return snapshot.installed().filter(extension => !isSkillExtension(extension));
}

/**
 * Bring the registry's skill extensions in line with the skills discovered on disk.
 *
 * Installed skills are replaced in place (the registry matches by name), so re-reading the workspace
 * after an edit changes what the next invocation renders. A skill whose file disappeared is
 * uninstalled, which also drops its section from every later request.
 *
 * @returns `{ installed, removed }` — the names the registry now holds and the ones it dropped.
 */
export function syncSkillExtensions(registry, skills) {
  const wanted = new Map(skills.map(skill => [SKILL_EXTENSION_PREFIX + skill.name, skillExtension(skill)]));
  const installed = [];
  const removed = [];
  for (const extension of registry.snapshot().installed()) {
    if (!isSkillExtension(extension)) continue;
    if (!wanted.has(extension.name)) {
      registry.uninstall(extension);
      removed.push(extension.name);
    }
  }
  for (const [name, extension] of wanted) {
    registry.install(extension);
    installed.push(name);
  }
  return { installed, removed };
}

/**
 * The skill a prompt invokes, if any.
 *
 * Only the line's first token is considered: `/name` and `/skill:name` both resolve, and a token
 * that names no discovered skill resolves to null so the caller can reject it locally instead of
 * sending an unknown slash command to the model.
 *
 * @returns `{ skill, arguments }`, or null when the prompt does not invoke a discovered skill.
 */
export function skillInvocation(text, skills) {
  const byName = new Map(skills.map(skill => [skill.name, skill]));
  const match = matchCommand(text, new Set(byName.keys()));
  if (match === null || !match.known) return null;
  return { skill: byName.get(match.name), arguments: match.arguments, prefix: match.prefix };
}
