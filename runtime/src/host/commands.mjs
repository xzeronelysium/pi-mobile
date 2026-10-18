// App-owned commands: the skills (durable extensions, one section each) and the templates (prompt
// text inserted into the composer). The documents are re-read from the workspace on request; the
// installed registry is updated in place so an invoking turn can select a skill that appeared after
// startup.

import { SKILL_KIND, commandRows, discoverCommands } from '../commands.mjs';
import { syncSkillExtensions } from '../skills.mjs';

export function createCommandSurface({ bridge, requireOpen, workspaceRoot, registry }) {
  let skills = [];

  /**
   * Re-read the app-owned command documents and re-publish the pickers' rows.
   *
   * Skills are installed into the registry as extensions (one `section` each) so a turn can select
   * one; templates are returned as text to insert. The rows carry everything the pickers render —
   * the catalog is never truncated here, because a viewport's height is not a discovery limit.
   */
  async function commands(query = {}) {
    requireOpen();
    const discovered = await discoverCommands(bridge.call, workspaceRoot());
    skills = discovered.commands.filter(command => command.kind === SKILL_KIND);
    syncSkillExtensions(registry, skills);
    const value = {
      rows: commandRows(discovered.commands),
      dirs: discovered.dirs,
      errors: discovered.errors,
      ...(typeof query?.kind === 'string' && query.kind ? { kind: query.kind } : {}),
    };
    bridge.emit({ type: 'commands', value });
    return value;
  }

  return { commands, skills: () => skills };
}
