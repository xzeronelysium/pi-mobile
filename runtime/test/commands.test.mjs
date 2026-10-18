// App-owned command documents: parsing, projection and discovery through the bridge.

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  COMMAND_DIRS,
  TEMPLATE_KIND,
  commandName,
  commandRows,
  discoverCommands,
  insertTextOf,
  matchCommand,
  parseCommandDocument,
} from '../src/commands.mjs';

test('a command document carries its frontmatter and its body verbatim', () => {
  const skill = parseCommandDocument(
    ['---', 'name: commit', 'description: Write a conventional commit message', 'argument-hint: <scope>', '---', '', 'Inspect the workspace and write the message.', '', 'Second paragraph.'].join('\n'),
    'ignored.md',
    'skill',
  );
  assert.equal(skill.kind, 'skill');
  assert.equal(skill.name, 'commit');
  assert.equal(skill.description, 'Write a conventional commit message');
  assert.equal(skill.argumentHint, '<scope>');
  // The body is never trimmed of its inner structure or rewritten.
  assert.equal(skill.instructions, 'Inspect the workspace and write the message.\n\nSecond paragraph.');
});

test('a document without frontmatter takes its name from the file and its description from the body', () => {
  const skill = parseCommandDocument('# Review\n\nRead the workspace and report defects.', 'Review File.md', 'skill');
  assert.equal(skill.name, 'review-file');
  assert.equal(skill.description, 'Review');
  assert.equal(skill.instructions, '# Review\n\nRead the workspace and report defects.');
});

test('an unusable document is dropped rather than given an invented name', () => {
  assert.equal(parseCommandDocument('', 'a.md', 'skill'), null);
  assert.equal(parseCommandDocument('---\nname: keep\n---\n', 'a.md', 'skill'), null);
  // Neither the declared name nor the file name can be invoked.
  assert.equal(parseCommandDocument('body', '... .md', 'skill'), null);
  assert.equal(commandName('Not A Name', ''), '');
  // A declared name a slash invocation cannot address falls back to the file name, not a repaired name.
  assert.equal(commandName('Not A Name', 'fallback.md'), 'fallback');
  assert.equal(commandName('ok-name', 'other.md'), 'ok-name');
});

test('rows carry the invocation, the description and the text a picker inserts', () => {
  const skill = parseCommandDocument('---\nname: demo\ndescription: Demo skill\n---\nDo the demo thing.', 'demo.md', 'skill');
  const template = parseCommandDocument('---\nname: handoff\ndescription: Hand off\n---\nSummarize the work so far.', 'handoff.md', TEMPLATE_KIND);
  const rows = commandRows([skill, template]);
  assert.deepEqual(rows.map(row => [row.kind, row.name]), [['skill', 'demo'], ['template', 'handoff']]);
  const demo = rows.find(row => row.name === 'demo');
  const handoff = rows.find(row => row.name === 'handoff');
  assert.equal(demo.invocation, '/demo');
  // A skill inserts its invocation; a template inserts its prompt text.
  assert.equal(demo.insert, '/demo ');
  assert.equal(insertTextOf(demo), '/demo ');
  assert.equal(handoff.invocation, '/handoff');
  assert.equal(handoff.insert, 'Summarize the work so far.');
  assert.equal(handoff.description, 'Hand off');
  assert.deepEqual(rows.map(row => row.name), ['demo', 'handoff']);
});

test('a typed slash command resolves only against the discovered catalog', () => {
  const names = new Set(['demo']);
  assert.deepEqual(matchCommand('/demo', names), { name: 'demo', arguments: '', prefix: 'demo', known: true });
  assert.deepEqual(matchCommand('/skill:demo', names), { name: 'demo', arguments: '', prefix: 'skill:demo', known: true });
  assert.deepEqual(matchCommand('/demo  with args ', names), { name: 'demo', arguments: 'with args', prefix: 'demo', known: true });
  // An unknown command is reported as unknown, never silently treated as chat.
  assert.deepEqual(matchCommand('/nope', names), { name: '', arguments: '', prefix: 'nope', known: false });
  // Prose is not a command: no leading slash, or a slash that does not open a token.
  assert.equal(matchCommand('hello /demo', names), null);
  assert.equal(matchCommand('/', names), null);
  assert.equal(matchCommand('/ demo', names), null);
  assert.equal(matchCommand('  ', names), null);
});

test('discovery reads only markdown out of the app-owned directories and reports what it cannot read', async () => {
  const root = '/workspace';
  const files = new Map([
    [`${root}/${COMMAND_DIRS.skill}/demo.md`, '---\nname: demo\ndescription: Demo\n---\nDemo body.\n'],
    [`${root}/${COMMAND_DIRS.skill}/Notes.md`, 'A skill whose body is its own instructions.'],
    [`${root}/${COMMAND_DIRS.skill}/broken.md`, '---\nname: broken\n---\n'],
    [`${root}/${COMMAND_DIRS.template}/handoff.md`, '---\nname: handoff\n---\nSummarize the work.\n'],
  ]);
  const listing = directory => [...files.keys()]
    .filter(path => path.startsWith(`${directory}/`))
    .map(path => ({ name: path.slice(directory.length + 1), kind: 'file', size: 0, mtimeMs: 0 }));
  const call = async (op, args) => {
    if (op === 'file.list') {
      // An absent directory is what a workspace with no commands yet looks like.
      if (args.path.endsWith('.pi-absent')) throw new Error('No such file: ' + args.path);
      if (![...files.keys()].some(path => path.startsWith(`${args.path}/`))) throw new Error('No such file: ' + args.path);
      if (args.path.endsWith('.pi-missing')) throw new Error('No such file: ' + args.path);
      return { entries: [...listing(args.path), { name: 'sub', kind: 'directory', size: 0, mtimeMs: 0 }, { name: 'readme.txt', kind: 'file', size: 0, mtimeMs: 0 }] };
    }
    if (op === 'file.read') {
      if (!files.has(args.path)) throw new Error('permission_denied: ' + args.path);
      return files.get(args.path);
    }
    throw new Error(`Unexpected op ${op}`);
  };
  const discovered = await discoverCommands(call, root);
  // Rows are ordered by name, whichever kind they are.
  assert.deepEqual(discovered.rows.map(row => [row.kind, row.name]), [['skill', 'demo'], ['template', 'handoff'], ['skill', 'notes']]);
  // A file with no body is named, not dropped silently.
  assert.equal(discovered.errors.some(error => error.includes('broken.md')), true);
  assert.deepEqual(discovered.dirs, [`${root}/${COMMAND_DIRS.skill}`, `${root}/${COMMAND_DIRS.template}`]);

  // Nothing has written the directories yet: empty, not an error.
  const empty = await discoverCommands(async () => { throw new Error('No such file: /workspace/.pi-skills'); }, root);
  assert.deepEqual(empty.rows, []);
  assert.deepEqual(empty.errors, []);

  // A directory that exists but cannot be read is reported.
  const unreadable = await discoverCommands(async (op, args) => {
    if (op === 'file.list') throw new Error('permission_denied: ' + args.path);
    throw new Error(`Unexpected op ${op}`);
  }, root);
  assert.equal(unreadable.errors.length, 2);
  assert.equal(unreadable.errors[0].includes('permission_denied'), true);
});
