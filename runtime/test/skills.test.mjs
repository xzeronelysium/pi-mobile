// Skills as durable extensions: the invocation resolves against the catalog, the extension carries
// one prompt section, and the real engine renders that section for the invoking turn alone.

import test from 'node:test';
import assert from 'node:assert/strict';
import { fauxAssistantMessage } from '@earendil-works/pi-ai/providers/faux';
import { getCurrentSystemMessage } from '@earendil-works/pi-ai/utils/transcript';
import { createRegistry } from '@earendil-works/pi-durable';
import { parseCommandDocument } from '../src/commands.mjs';
import { SKILL_EXTENSION_PREFIX, baseExtensions, isSkillExtension, skillExtension, skillInvocation, syncSkillExtensions } from '../src/skills.mjs';
import { withFauxHarness } from './support/durable.mjs';

const demo = parseCommandDocument('---\nname: demo\ndescription: Demo\n---\nDEMO-SKILL-INSTRUCTIONS', 'demo.md', 'skill');
const other = parseCommandDocument('---\nname: other\n---\nOTHER-SKILL-INSTRUCTIONS', 'other.md', 'skill');

test('a skill invocation resolves only against the discovered catalog', () => {
  assert.equal(skillInvocation('/demo', [demo]).skill, demo);
  assert.equal(skillInvocation('/skill:demo please', [demo]).arguments, 'please');
  assert.equal(skillInvocation('/demo', [demo]).prefix, 'demo');
  assert.equal(skillInvocation('/nope', [demo]), null);
  assert.equal(skillInvocation('/demolish', [demo]), null);
  assert.equal(skillInvocation('plain text', [demo]), null);
});

test('a skill is one named extension contributing one prompt section', () => {
  const extension = skillExtension(demo);
  assert.equal(extension.name, 'skill:demo');
  assert.equal(isSkillExtension(extension), true);
  assert.equal(extension.tools, undefined);
  assert.equal(extension.sections.length, 1);
  assert.equal(extension.sections[0].key, 'skill-demo');
  assert.equal(extension.sections[0].render(), 'DEMO-SKILL-INSTRUCTIONS');
  assert.equal(isSkillExtension({ name: 'approvals' }), false);
});

test('the registry holds one extension per discovered skill and drops the ones that disappeared', () => {
  const registry = createRegistry();
  registry.install({ name: 'approvals', hooks: [] });
  const first = syncSkillExtensions(registry, [demo, other]);
  assert.deepEqual(first.installed, ['skill:demo', 'skill:other']);
  assert.deepEqual(first.removed, []);
  // The base selection is everything that is not a skill, so a skill contributes nothing until a
  // turn selects it.
  assert.deepEqual(baseExtensions(registry.snapshot()).map(e => e.name), ['approvals']);
  const second = syncSkillExtensions(registry, [demo]);
  assert.deepEqual(second.installed, ['skill:demo']);
  assert.deepEqual(second.removed, ['skill:other']);
  assert.deepEqual(registry.snapshot().installed().map(e => e.name).sort(), ['approvals', SKILL_EXTENSION_PREFIX + 'demo']);
});

test('the invoking turn renders the skill section and no other request does', async () => {
  const registry = createRegistry();
  registry.install({ name: 'approvals', hooks: [] });
  syncSkillExtensions(registry, [demo]);
  const seen = [];
  await withFauxHarness(async ({ context, faux, root }) => {
    const agent = await root.agent(context);
    // What the host selects by default: every installed extension that is not a skill.
    assert.deepEqual(agent.extensions.map(e => e.name), ['approvals']);
    faux.setResponses([
      contextFor => { seen.push(contextFor); return fauxAssistantMessage('plain'); },
      contextFor => { seen.push(contextFor); return fauxAssistantMessage('invoked'); },
      contextFor => { seen.push(contextFor); return fauxAssistantMessage('after'); },
    ]);
    const extension = registry.snapshot().extension('skill:demo');
    // Turn 1: an ordinary prompt, with no skill selected.
    const plain = await root.submit({ type: 'input', content: 'plain question', requestId: 'r1' }, context);
    await plain.wait(context);
    // Turn 2: select the skill, exactly as the host does around an invoking send.
    await root.configure({ extensions: { add: [extension] } }, context);
    const invoking = await root.submit({ type: 'input', content: '/demo hello', requestId: 'r2' }, context);
    await invoking.wait(context);
    await root.configure({ extensions: null }, context);
    // Turn 3: the selection is gone again.
    const after = await root.submit({ type: 'input', content: 'another plain question', requestId: 'r3' }, context);
    await after.wait(context);
  }, { registry, settings: { get extensions() { return baseExtensions(registry.snapshot()); } } });
  assert.equal(seen.length, 3);
  // The effective prompt is the replay of every pi.system message in order: a section is patched by
  // name and a `null` patch removes it, so this is what the model actually reads.
  const sectionOf = request => getCurrentSystemMessage(request.messages)?.sections?.['skill-demo'] ?? '';
  // Only the turn that selected the skill carried its instructions.
  assert.equal(sectionOf(seen[0]).includes('DEMO-SKILL-INSTRUCTIONS'), false);
  assert.equal(sectionOf(seen[1]).includes('DEMO-SKILL-INSTRUCTIONS'), true);
  assert.ok(sectionOf(seen[1]).startsWith('<skill-demo>'));
  assert.ok(sectionOf(seen[1]).endsWith('</skill-demo>'));
  assert.equal(sectionOf(seen[2]).includes('DEMO-SKILL-INSTRUCTIONS'), false);
  // The removal really is a patch in the transcript, not a re-render that happens to drop it.
  assert.equal(seen[2].messages.some(message => message.sections?.['skill-demo'] === null), true);
});

test('the stored user entry stays the raw invocation, not the expansion', async () => {
  const registry = createRegistry();
  syncSkillExtensions(registry, [demo]);
  await withFauxHarness(async ({ context, faux, root }) => {
    faux.setResponses([fauxAssistantMessage('done')]);
    const extension = registry.snapshot().extension('skill:demo');
    await root.configure({ extensions: { add: [extension] } }, context);
    const submission = await root.submit({ type: 'input', content: '/demo hello', requestId: 'r1' }, context);
    await submission.wait(context);
    const page = await root.entries({ order: 'ascending' }, 50, undefined, context);
    const user = page.items.find(entry => entry.kind === 'pi.user');
    assert.equal(user === undefined, false, `no user entry among ${JSON.stringify(page.items.map(entry => entry.kind))}`);
    // The user entry's contribution to model context is the raw invocation, unchanged.
    assert.equal(user.model[0].content, '/demo hello');
  }, { registry });
});
