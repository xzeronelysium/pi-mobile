// Conversation surface: reading the transcript, sending a turn, and every operation that moves the
// active conversation (switch, new, fork, reset, rename, compact) or reads its queue, usage and
// export. The durable event stream itself is owned by `state.mjs`; this module drives it through
// `activate` and never touches the harness's open conversation directly.

import {
  compactionOutcome,
  conversationTree,
  createSession as createDurableSession,
  enqueue,
  exportConversation,
  forkSession as forkDurableSession,
  openSession as openDurableSession,
  queueState,
  renameSession as renameDurableSession,
  resetSession as resetDurableSession,
  usageState,
  withdraw as withdrawSubmission,
  writeEntry as writePassiveEntry,
} from '../sessions.mjs';
import { rememberKey } from '../credentials.mjs';
import { SKILL_EXTENSION_PREFIX, skillInvocation } from '../skills.mjs';
import { projectTranscript } from '../ui.mjs';
import { resolve as resolvePath } from '../path.mjs';
import { AUTH_TYPE_API_KEY, newestFirst } from './util.mjs';

export function createSessionSurface({
  bridge,
  models,
  context,
  state,
  requireOpen,
  switchGuard,
  conversationIdOf,
  historyFrame,
  activate,
  emitConversations,
  credentials,
  providerOf,
  defaultModelId,
  registry,
  skills,
}) {
  const api = {
    async history({ limit = 100, cursor, order = 'ascending' } = {}) {
      requireOpen();
      // The reader is looking at the conversation's TAIL, so the read that backs their transcript is
      // the tail too: the newest entries, handed back oldest-first. A head page would arrive as rows
      // the reader is not looking at and, applied as a whole-conversation read, delete the newest
      // rows from the screen — the reported "the previous message and its answer vanish when the next
      // message is sent". A page that does not reach the conversation's first entry carries `next`,
      // which is what tells the UI it is a page and must be merged rather than replace anything.
      const read = await state.conversation.entries({ order: 'descending' }, limit, cursor, context);
      const page = order === 'descending' ? read : newestFirst(read);
      const value = { ...page, rows: projectTranscript(page.items) };
      bridge.emit({ type: 'history', value });
      return value;
    },

    async snapshot() {
      requireOpen();
      const value = await state.conversation.context(context);
      const enriched = { ...value, ...(Array.isArray(value?.entries) ? { rows: projectTranscript(value.entries) } : {}) };
      bridge.emit({ type: 'snapshot', value: enriched });
      return enriched;
    },

    async send(config) {
      requireOpen();
      const provider = providerOf(config?.provider ?? 'openai');
      const stored = await credentials.read(provider.id);
      const typed = typeof config?.apiKey === 'string' && config.apiKey.trim() ? config.apiKey.trim() : '';
      const key = typed || (stored?.type === AUTH_TYPE_API_KEY ? stored.key : undefined);
      // An OAuth credential needs no key here: `Models.getAuth` resolves and refreshes it from the
      // store under the provider lock when the turn is dispatched.
      if (!key && !stored) {
        const methods = [provider.auth?.apiKey ? 'an API key' : null, provider.auth?.oauth ? 'subscription sign-in' : null].filter(Boolean).join(' or ');
        throw new Error(`${provider.name} needs ${methods || 'a credential'}`);
      }
      if (typeof config.prompt !== 'string' || !config.prompt.trim()) throw new Error('Prompt required');
      if (typeof config.requestId !== 'string' || !config.requestId) throw new Error('requestId required');
      // A slash command is resolved before anything is dispatched. A skill's instructions are its
      // durable extension's section, and a slash command naming nothing is refused here rather than
      // sent to the model. Prose that merely begins with a slash path is left alone: only a lone
      // `/token` is a command that could not be expanded.
      const invocation = skillInvocation(config.prompt, skills());
      if (invocation === null && /^\/[A-Za-z0-9:_-]+$/.test(config.prompt.trim())) throw new Error(`${config.prompt.trim()} is not a known command`);
      const turnExtension = invocation === null ? undefined : registry.snapshot().extension(SKILL_EXTENSION_PREFIX + invocation.skill.name);
      if (invocation !== null && turnExtension === undefined) throw new Error(`${invocation.prefix} is not a known command`);
      const modelId = typeof config.modelId === 'string' && config.modelId.trim() ? config.modelId.trim() : defaultModelId(provider.id);
      if (!modelId) throw new Error('Model required');
      if (!models.getModel(provider.id, modelId)) throw new Error(`Unknown ${provider.name} model: ${modelId}`);
      if (state.active) throw new Error('Send already active');
      state.active = true;
      try {
        // Persisting the key is what makes it survive a runtime restart; a key that came from the
        // store is already there.
        if (typed) await rememberKey(credentials, provider.id, typed);
        await state.conversation.configure({ model: { provider: provider.id, modelId }, ...(config.thinkingLevel ? { thinkingLevel: config.thinkingLevel } : {}) }, context);
        // The invoking turn selects exactly this skill. The section then renders for that request
        // alone and is cleared as soon as the run settles, so the stored user entry stays the raw
        // invocation and no other request's system prompt carries the instructions.
        if (turnExtension) await state.conversation.configure({ extensions: { add: [turnExtension] } }, context);
        const submission = await state.conversation.submit({ type: 'input', content: config.prompt, requestId: config.requestId, whenBusy: 'reject' }, context);
        bridge.emit({ type: 'submitted', requestId: config.requestId, submissionId: submission.id });
        const result = await submission.wait(context);
        bridge.emit({ type: 'settled', requestId: config.requestId, value: result });
        return result;
      } finally {
        if (turnExtension) await state.conversation.configure({ extensions: null }, context).catch(() => {});
        state.active = false;
      }
    },

    // --- Session / usage / queue / export ------------------------------------------------

    /** Every conversation in storage, newest first, with a real name; cursor-paged. */
    async sessions(query = {}) {
      requireOpen();
      return emitConversations();
    },

    /** The conversation tree: fork edges for the drawer's nested view. */
    async tree(query = {}) {
      requireOpen();
      const value = await conversationTree(state.harness, { currentId: state.conversation.id, limit: query?.limit }, context);
      bridge.emit({ type: 'tree', value });
      return value;
    },

    /** Switch the host to another conversation; the durable event stream follows. */
    async openSession(config = {}) {
      requireOpen();
      const id = config?.conversationId;
      if (!Number.isInteger(id)) throw new Error('conversationId required');
      const opened = await openDurableSession(state.harness, id, context, { limit: config?.limit });
      if (!opened) throw new Error(`Unknown conversation: ${id}`);
      await activate(opened.conversation);
      const page = { items: opened.items, ...(opened.cursor ? { next: opened.cursor } : {}) };
      bridge.emit({ type: 'history', value: historyFrame(page) });
      return { conversationId: id, parent: opened.parent, owner: opened.owner, rows: opened.rows.length, next: opened.cursor ?? null };
    },

    /** Create a fresh conversation and switch to it. */
    async newSession(config = {}) {
      requireOpen();
      const handle = await createDurableSession(state.harness, config?.agent ? { agent: config.agent } : {}, context);
      await activate(handle);
      await emitConversations();
      return { conversationId: handle.id, created: true };
    },

    /** Fork the active conversation at one visible entry and switch to the fork. */
    async forkSession(config = {}) {
      requireOpen();
      switchGuard();
      const at = config?.entryId;
      if (!Number.isInteger(at)) throw new Error('entryId required');
      const handle = await forkDurableSession(state.conversation, at, config?.agent ? { agent: config.agent } : {}, context);
      const opened = await openDurableSession(state.harness, handle.id, context, {});
      await activate(handle);
      const page = { items: opened.items, ...(opened.cursor ? { next: opened.cursor } : {}) };
      bridge.emit({ type: 'history', value: historyFrame(page) });
      await emitConversations();
      return { conversationId: handle.id, parent: opened.parent ?? null, at };
    },

    /** Start a new context in the active conversation, optionally with a handoff note. */
    async resetSession(config = {}) {
      requireOpen();
      const value = await resetDurableSession(state.conversation, config?.handoff, context);
      const page = await state.conversation.entries({ order: 'ascending' }, 200, undefined, context);
      bridge.emit({ type: 'session.reset', ...value, value });
      bridge.emit({ type: 'history', value: historyFrame(page) });
      return value;
    },

    /**
     * Rename a conversation. The name written here is the one the list and the tree already report:
     * `sessions.mjs` keeps a single derivation (`conversationName`), preferring this stored title
     * over the name derived from the first message.
     */
    async renameSession(config = {}) {
      requireOpen();
      const id = config?.conversationId === undefined ? state.conversation.id : config.conversationId;
      if (!Number.isInteger(id)) throw new Error('conversationId required');
      const value = await renameDurableSession(state.harness, id, config?.name, context);
      bridge.emit({ type: 'session.renamed', value });
      // The drawer's list carries the new name; the tree is re-read by whoever displays it.
      await emitConversations();
      return value;
    },

    /**
     * Compact older messages into a summary. Admission is acknowledged at once and the durable
     * outcome arrives as a second frame, so tapping Compact never blocks on a model call; the
     * summarization itself keeps running in the conversation, which is the API's own semantics.
     */
    async compactSession(config = {}) {
      requireOpen();
      const text = typeof config?.instructions === 'string' ? config.instructions.trim() : '';
      const instructions = text ? text : undefined;
      const taskId = await state.conversation.compact(instructions, context);
      const admitted = { taskId: String(taskId), status: 'admitted', ...(instructions ? { instructions } : {}) };
      bridge.emit({ type: 'session.compacted', value: admitted });
      state.harness
        .waitForTask(taskId, context)
        .then(settled => bridge.emit({ type: 'session.compacted', value: compactionOutcome(taskId, settled) }))
        .catch(error => bridge.emit({ type: 'error', operation: 'compactSession', message: error instanceof Error ? error.message : String(error) }));
      return admitted;
    },

    /**
     * The footer's usage frame: the session's model/tool totals plus the active conversation's
     * context pressure. The context window comes from the catalog, never from a guess.
     */
    async usage(query = {}) {
      requireOpen();
      const id = conversationIdOf(query?.conversationId);
      const target = id === state.conversation.id ? state.conversation : (await state.harness.conversation(id, context)) ?? null;
      const value = await usageState(state.harness, target, context, {
        windowOf: (provider, modelId) => models.getModel(provider, modelId)?.contextWindow,
      });
      bridge.emit({ type: 'usage', value });
      return value;
    },

    /** Input waiting for a boundary in the active conversation. */
    async queue(query = {}) {
      requireOpen();
      const value = await queueState(state.harness, conversationIdOf(query?.conversationId), context);
      bridge.emit({ type: 'inbox', value });
      return value;
    },

    /** Admit input that joins the running work at the next tool boundary. */
    async steer(config = {}) {
      requireOpen();
      const value = await enqueue(state.conversation, { text: config?.text ?? config?.prompt, mode: 'steer', requestId: config?.requestId }, context);
      bridge.emit({ type: 'queued', value });
      return value;
    },

    /** Admit input that starts the next run once the current answer lands. */
    async followUp(config = {}) {
      requireOpen();
      const value = await enqueue(state.conversation, { text: config?.text ?? config?.prompt, mode: 'followUp', requestId: config?.requestId }, context);
      bridge.emit({ type: 'queued', value });
      return value;
    },

    /** Withdraw a queued submission; the durable outcome is returned, never assumed. */
    async withdraw(config = {}) {
      requireOpen();
      const submissionId = config?.submissionId;
      const value = {
        submissionId,
        result: await withdrawSubmission(state.harness, submissionId, context, conversationIdOf(config?.conversationId)),
      };
      bridge.emit({ type: 'withdrawn', value });
      return value;
    },

    /** Append a passive entry without asking the model anything. */
    async writeEntry(config = {}) {
      requireOpen();
      const value = await writePassiveEntry(state.conversation, { kind: config?.kind, data: config?.data }, context);
      bridge.emit({ type: 'written', value });
      return value;
    },

    /** Export a transcript as markdown/json/jsonl, optionally writing it into the workspace. */
    async exportSession(config = {}) {
      requireOpen();
      const id = conversationIdOf(config?.conversationId);
      const target = id === state.conversation.id ? state.conversation : await state.harness.conversation(id, context);
      if (!target) throw new Error(`Unknown conversation: ${id}`);
      const value = await exportConversation(target, {
        format: config?.format,
        includeThinking: config?.includeThinking === true,
        limit: config?.limit,
        now: Date.now,
      }, context);
      if (typeof config?.path === 'string' && config.path) {
        const path = resolvePath(state.workspaceRoot, config.path);
        await bridge.call('file.write', { path, text: value.content });
        value.path = path;
      }
      bridge.emit({ type: 'export', value });
      return value;
    },

    async abort() { requireOpen(); await state.conversation.abort(context); return { aborted: true }; },
  };

  return { api };
}
