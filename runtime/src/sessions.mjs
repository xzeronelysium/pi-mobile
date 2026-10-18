// Durable-backed session / usage / queue / export layer over the Pi Durable Harness.
//
// The host opens ONE root conversation; the restored UI needs the whole surface. Everything here
// reads committed durable state through the real Harness — no shadow copy of transcripts is kept,
// so a reopened app sees the same sessions.
//
// The payload shapes are the contract the Kotlin reducers already read:
//   listSessions()      -> the `conversations` payload  (SessionListReducer)
//   conversationTree()  -> the `tree` payload           (ConversationTreeReducer)
//   usageState()        -> the `usage` payload          (UsageReducer)
//   queueState()        -> the `inbox` payload          (InboxReducer)
//   exportConversation()-> the `export` payload         (no reducer yet; the whole document)

import { AgentDoc, defineDoc, InboxDoc, ROOT_CONVERSATION_ID } from '@earendil-works/pi-durable';
import { projectTranscript } from './ui.mjs';

/** Entries read per history page when opening a conversation. */
export const PAGE_LIMIT = 200;
/**
 * Entries scanned per conversation to build its name, timestamps and message count. A conversation
 * longer than this reports the count it read and `truncated: true`; nothing is invented.
 */
const LIST_SCAN = 100;
const NAME_LENGTH = 120;

/** Whitespace-collapsed and capped, or '' when there is nothing to show. One cap for every name. */
function normalizeName(value) {
  const text = typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : '';
  return text.length > NAME_LENGTH ? `${text.slice(0, NAME_LENGTH - 1)}…` : text;
}

/** One-line name for a conversation, from its first real message. */
function nameOf(rows) {
  const row = rows.find(entry => entry.role === 'user') ?? rows.find(entry => entry.role === 'assistant');
  return normalizeName(row?.text);
}

/**
 * A conversation's own title, written by `renameSession()`.
 *
 * It is the SAME source of truth the list and the tree report: `conversationName()` prefers the
 * stored title and falls back to the first real message, so a session has exactly one name — never a
 * derived one beside a stored one. `latest` keeps the newest rename; `fork: 'current'` carries the
 * title into a fork, which is what a reader expects of a fork's label.
 */
export const TitleDoc = defineDoc({
  kind: 'pi.mobile.title',
  version: 1,
  scope: 'conversation',
  history: 'latest',
  fork: 'current',
  initial: () => ({ name: '' }),
});

/** The name the drawer and the tree both show: the stored title first, else the first real message. */
export async function conversationName(harness, conversationId, projected, context) {
  const stored = normalizeName((await harness.snapshot(TitleDoc, conversationId, context))?.name);
  return stored || nameOf(projected) || `Conversation ${conversationId}`;
}

/**
 * Rename a conversation by writing the one title both surfaces read. A blank name is refused rather
 * than stored, because an empty title would silently fall back to the derived name.
 */
export async function renameSession(harness, conversationId, name, context) {
  const cleaned = normalizeName(name);
  if (!cleaned) throw new Error('Name required');
  await harness.commit(async tx => {
    Object.assign(await tx.doc(TitleDoc, conversationId), { name: cleaned });
  }, context);
  return { conversationId: String(conversationId), name: cleaned };
}

/** Newest and oldest message timestamps the transcript itself carries; 0 when it carries none. */
function spanOf(rows) {
  const stamps = rows.map(row => row.timestamp).filter(stamp => typeof stamp === 'number' && stamp > 0);
  return stamps.length === 0 ? { created: 0, modified: 0 } : { created: Math.min(...stamps), modified: Math.max(...stamps) };
}

/** Read one ascending page of a conversation's committed transcript and project it. */
async function historyOf(conversation, options, context) {
  const page = await conversation.entries(
    { order: 'ascending' },
    Number.isInteger(options?.limit) && options.limit > 0 ? options.limit : PAGE_LIMIT,
    options?.cursor,
    context,
  );
  return { page, rows: projectTranscript(page.items) };
}

/**
 * The `conversations` payload: every conversation newest first, with the row fields the drawer
 * reads. Cursor-paged; `next` is the continuation or `null`, and no total is claimed.
 */
export async function listSessions(harness, { currentId, limit = 50, cursor } = {}, context) {
  const page = await harness.commit(
    tx => tx.scanConversations({ order: 'descending' }, limit, cursor),
    context,
  );
  const rows = [];
  for (const record of page.items) {
    const conversation = await harness.conversation(record.id, context);
    if (!conversation) continue;
    const { page: entries, rows: projected } = await historyOf(conversation, { limit: LIST_SCAN }, context);
    rows.push({
      id: String(record.id),
      name: await conversationName(harness, record.id, projected, context),
      ...spanOf(projected),
      messageCount: projected.length,
      owner: record.owner ? String(record.owner.conversationId) : null,
      current: record.id === currentId,
      truncated: Boolean(entries.next),
    });
  }
  return { current: currentId === undefined ? null : String(currentId), rows, next: page.next ?? null };
}

/**
 * The `tree` payload: every conversation with its fork edge and the entry it forked at. Depth is
 * left out on purpose; `ConversationTreeReducer` computes it from the nesting.
 */
export async function conversationTree(harness, { currentId, limit = 200 } = {}, context) {
  const page = await harness.commit(tx => tx.scanConversations({ order: 'ascending' }, limit), context);
  const nodes = [];
  for (const record of page.items) {
    const conversation = await harness.conversation(record.id, context);
    const projected = conversation ? (await historyOf(conversation, { limit: LIST_SCAN }, context)).rows : [];
    nodes.push({
      id: String(record.id),
      parentId: record.parent ? String(record.parent.conversationId) : null,
      entryId: record.parent ? String(record.parent.at) : null,
      title: await conversationName(harness, record.id, projected, context),
      current: record.id === currentId,
    });
  }
  return { nodes };
}

/** Open a conversation by id: its handle, committed transcript rows and fork edge, or `null`. */
export async function openSession(harness, id, context, options = {}) {
  const conversation = await harness.conversation(id, context);
  if (!conversation) return null;
  const record = await harness.commit(tx => tx.conversation(id), context);
  const { page, rows } = await historyOf(conversation, options, context);
  return {
    conversation,
    id: conversation.id,
    parent: record?.parent ?? null,
    owner: record?.owner ?? null,
    items: page.items,
    rows,
    cursor: page.next ?? null,
  };
}

/** Create a fresh ownerless conversation, optionally with its own agent choices. */
export async function createSession(harness, { agent } = {}, context) {
  return harness.createConversation(
    { ownership: { kind: 'ownerless' }, ...(agent ? { agent } : {}) },
    context,
  );
}

/** Fork a conversation at one visible entry id; the fork continues independently. */
export async function forkSession(conversation, at, { agent } = {}, context) {
  if (!Number.isInteger(at)) throw new Error('Fork entry id required');
  return conversation.fork(
    at,
    { ownership: { kind: 'ownerless' }, ...(agent ? { agent } : {}) },
    context,
  );
}

/** Start a new context, optionally with a handoff note; stored entries are retained. */
export async function resetSession(conversation, handoff, context) {
  if (handoff !== undefined && typeof handoff !== 'string') throw new Error('Handoff must be text');
  await conversation.reset(handoff, context);
  return { conversationId: conversation.id, reset: true };
}

// --- Usage ---------------------------------------------------------------------------------
// `pi.usage` (one conversation) and `harness.usage()` (the whole session) hold raw pi-ai counters
// keyed `provider/modelId` for responses and by tool name for tool results. The projection adds
// them up the way upstream does, so the footer can show real spend.

const REQUIRED_COUNTERS = ['input', 'output', 'cacheRead', 'cacheWrite'];
/** Counters only some providers report; added once either side has them. */
const OPTIONAL_COUNTERS = ['cacheWrite1h', 'reasoning'];
const COST_COUNTERS = ['input', 'output', 'cacheRead', 'cacheWrite', 'total'];

/** A zeroed pi-ai `Usage`, the identity for summation. */
export function zeroUsage() {
  return {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
    totalTokens: 0,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
  };
}

/** Add every counter of `usage` to `total`; mirrors pi-durable's own `addUsage`. */
export function addUsage(total, usage) {
  if (!usage) return total;
  for (const key of REQUIRED_COUNTERS) total[key] += Number(usage[key] ?? 0);
  total.totalTokens += Number(usage.totalTokens ?? 0);
  for (const key of OPTIONAL_COUNTERS) {
    if (usage[key] !== undefined) total[key] = Number(total[key] ?? 0) + Number(usage[key]);
  }
  for (const key of COST_COUNTERS) total.cost[key] += Number(usage.cost?.[key] ?? 0);
  return total;
}

function bucketKeys(state, field) {
  const record = state?.[field];
  return record && typeof record === 'object' ? Object.keys(record).sort() : [];
}

/** Project one raw `UsageState` into sorted buckets plus an honest grand total. */
export function projectUsage(state) {
  const totals = zeroUsage();
  const models = bucketKeys(state, 'models').map(key => {
    const value = state.models[key] ?? zeroUsage();
    addUsage(totals, value);
    const slash = key.indexOf('/');
    return {
      key,
      provider: slash < 0 ? key : key.slice(0, slash),
      modelId: slash < 0 ? '' : key.slice(slash + 1),
      usage: value,
    };
  });
  const tools = bucketKeys(state, 'tools').map(name => {
    const value = state.tools[name] ?? zeroUsage();
    addUsage(totals, value);
    return { name, usage: value };
  });
  return { models, tools, totals };
}

/**
 * Prompt size of the conversation's last provider request: the tokens that request reported
 * carrying. That is a real number, not an estimate of the next request's size.
 */
async function lastPromptTokens(conversation, context) {
  const view = await conversation.context(context);
  for (let index = view.entries.length - 1; index >= 0; index -= 1) {
    for (const message of view.entries[index].model ?? []) {
      if (message?.role !== 'assistant' || !message.usage) continue;
      const usage = message.usage;
      return Number(usage.input ?? 0) + Number(usage.cacheRead ?? 0) + Number(usage.cacheWrite ?? 0);
    }
  }
  return 0;
}

/**
 * The `usage` payload: the session's model and tool totals plus the active conversation's context
 * pressure. `windowOf(provider, modelId)` supplies a model's context window from the catalog; an
 * unpublished window stays 0 ("not reported") instead of being guessed.
 */
export async function usageState(harness, conversation, context, { windowOf } = {}) {
  const ledger = projectUsage(await harness.usage(context));
  const models = ledger.models.map(bucket => ({
    provider: bucket.provider,
    modelId: bucket.modelId,
    input: Number(bucket.usage.input ?? 0),
    output: Number(bucket.usage.output ?? 0),
    cacheRead: Number(bucket.usage.cacheRead ?? 0),
    cacheWrite: Number(bucket.usage.cacheWrite ?? 0),
    cost: Number(bucket.usage.cost?.total ?? 0),
  }));

  let used = 0;
  let window = 0;
  if (conversation) {
    used = await lastPromptTokens(conversation, context);
    const agent = await harness.snapshot(AgentDoc, conversation.id, context);
    const provider = agent?.model?.provider;
    const modelId = agent?.model?.modelId;
    if (typeof windowOf === 'function' && provider && modelId) {
      const reported = Number(windowOf(provider, modelId));
      window = Number.isFinite(reported) && reported > 0 ? reported : 0;
    }
  }
  return { models, tools: ledger.tools.map(bucket => ({ name: bucket.name })), context: { used, window } };
}

// --- Queue ---------------------------------------------------------------------------------
// Input handed to a busy conversation waits in `pi.inbox` until a boundary places it: a steer
// joins the running work after the current tool round, a follow-up starts the next run. The
// submission ledger carries the durable status; the inbox carries the mode and the text.

const QUEUE_MODES = new Set(['steer', 'followUp']);

/** Text of a stored `UserInput` (a string, or blocks whose text parts are concatenated). */
function inputText(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content.filter(part => part?.type === 'text' && typeof part.text === 'string').map(part => part.text).join('');
}

/** The `inbox` payload: the queued submissions of one conversation, in ID order. */
export async function queueState(harness, conversationId, context) {
  const inbox = await harness.snapshot(InboxDoc, conversationId, context);
  const records = new Map(
    (await harness.inspect(context)).submissions
      .filter(record => record.conversationId === conversationId)
      .map(record => [record.id, record]),
  );
  const queued = (inbox?.items ?? []).map(item => ({
    id: String(item.id),
    kind: item.mode,
    text: item.mode === 'write' ? '' : inputText(item.content),
    status: records.get(item.id)?.status ?? 'queued',
  }));
  return { queued };
}

/**
 * Admit input that waits for a boundary when the conversation is busy (`mode: "steer"` joins the
 * running work, `mode: "followUp"` starts the next run). An idle conversation runs it at once.
 */
export async function enqueue(conversation, config = {}, context) {
  const mode = config.mode === undefined ? 'followUp' : config.mode;
  if (!QUEUE_MODES.has(mode)) throw new Error(`Unsupported queue mode: ${mode}`);
  const text = typeof config.text === 'string' ? config.text : '';
  if (!text.trim()) throw new Error('Queued input text required');
  const submission = await conversation.submit({
    type: 'input',
    content: text,
    whenBusy: mode,
    ...(typeof config.requestId === 'string' && config.requestId ? { requestId: config.requestId } : {}),
  }, context);
  return { id: submission.id, mode, status: (await submission.status(context)).status };
}

/** Withdraw a queued submission by id (a number or the string the drawer rendered). */
export async function withdraw(harness, submissionId, context, conversationId) {
  const id = typeof submissionId === 'string' ? Number(submissionId) : submissionId;
  if (!Number.isInteger(id)) throw new Error('Submission id required');
  return harness.abortSubmission(id, context, conversationId);
}

/** Append a passive entry without asking the model anything. */
export async function writeEntry(conversation, entry, context) {
  const kind = typeof entry?.kind === 'string' ? entry.kind : '';
  if (!kind) throw new Error('Entry kind required');
  const submission = await conversation.submit({
    type: 'write',
    entry: { kind, ...(entry.data === undefined ? {} : { data: entry.data }) },
  }, context);
  return { id: submission.id, kind, status: (await submission.status(context)).status };
}

// --- Export --------------------------------------------------------------------------------
// Turn the committed transcript into a portable document. The projection is the same one the
// Compose transcript renders, so an export and the screen never disagree about a message.

export const EXPORT_FORMATS = ['markdown', 'json', 'jsonl'];
/**
 * Every format [exportConversation] can render. HTML is a standalone document rather than
 * structured data, so it lives beside [EXPORT_FORMATS] without changing that exact inventory.
 */
export const EXPORT_TARGETS = [...EXPORT_FORMATS, 'html'];
/** The content type each rendered format carries, reported so the caller can name the file. */
const EXPORT_MIME = {
  markdown: 'text/markdown',
  json: 'application/json',
  jsonl: 'application/x-ndjson',
  html: 'text/html',
};
/** Entries read per page while exporting. */
const EXPORT_PAGE = 200;
/** Default entry cap; a capped export reports `truncated` instead of implying completeness. */
const EXPORT_LIMIT = 5000;

/** Ascending committed entries of a conversation, up to `cap`, with a truncation flag. */
async function collectEntries(conversation, cap, context) {
  const items = [];
  let cursor;
  let truncated = false;
  for (;;) {
    const page = await conversation.entries({ order: 'ascending' }, Math.min(EXPORT_PAGE, cap - items.length), cursor, context);
    items.push(...page.items);
    if (!page.next || items.length >= cap) {
      truncated = Boolean(page.next);
      break;
    }
    cursor = page.next;
  }
  return { items, truncated };
}

const HEADINGS = { user: '## User', assistant: '## Assistant', thinking: '## Thinking' };

/** Markdown document with one section per transcript row. */
function renderMarkdown(conversationId, rows, meta) {
  const lines = [
    `# Pi conversation ${conversationId}`,
    '',
    `_Exported ${meta.exportedAt} · ${rows.length} messages${meta.truncated ? ' · truncated' : ''}_`,
    '',
  ];
  for (const row of rows) {
    if (row.role === 'notice') {
      lines.push(`> ${row.text ?? ''}`, '');
      continue;
    }
    if (row.role === 'tool') {
      lines.push(`## Tool: ${row.name ?? 'tool'} [${row.status ?? 'pending'}]`, '');
      if (row.arguments) lines.push('```json', row.arguments, '```', '');
      if (row.output) lines.push('```', row.output, '```', '');
      continue;
    }
    lines.push(HEADINGS[row.role] ?? `## ${row.role}`, '');
    if (row.attachments) lines.push(`_(+${row.attachments} attachment${row.attachments === 1 ? '' : 's'})_`, '');
    if (row.text) lines.push(row.text, '');
  }
  return `${lines.join('\n').replace(/\n+$/, '')}\n`;
}

/** Structured JSON document holding every projected row. */
function renderJson(conversationId, rows, meta) {
  return `${JSON.stringify({ conversationId: String(conversationId), exportedAt: meta.exportedAt, truncated: meta.truncated, messages: rows }, null, 2)}\n`;
}

/** Newline-delimited JSON: one projected row per line. */
function renderJsonl(rows) {
  return rows.length === 0 ? '' : `${rows.map(row => JSON.stringify(row)).join('\n')}\n`;
}

/** Escape a value for HTML text/attribute context. User text must never inject markup. */
function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Dark grayscale, matching the Compose transcript palette; no external assets, no scripts. */
const HTML_STYLE = [
  ':root{color-scheme:dark}',
  'body{margin:0;background:#121212;color:#e6e6e6;font:14px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}',
  'header{padding:20px 20px 8px;border-bottom:1px solid #2a2a2a}',
  'h1{font-size:16px;margin:0 0 4px;font-weight:600}',
  'h2{font-size:13px;margin:0 0 4px;color:#9a9a9a;font-weight:600;text-transform:lowercase}',
  '.meta{margin:0;color:#9a9a9a;font-size:12px}',
  'main{padding:8px 20px 32px}',
  'section.row{margin:14px 0;padding-left:10px;border-left:2px solid #2a2a2a}',
  'section.user{border-left-color:#6f6f6f;background:#1a1a1a;padding:8px 10px}',
  'section.assistant{border-left-color:#4a4a4a}',
  'section.tool,section.thinking{border-left-color:#333}',
  'section.tool.error h2,section.tool.error .status{color:#c98a8a}',
  'p{margin:0;white-space:pre-wrap;word-break:break-word}',
  'pre{margin:6px 0;padding:6px 8px;background:#1a1a1a;overflow:auto;white-space:pre-wrap;word-break:break-word}',
  'pre.args{color:#b8b8b8}',
  '.status{color:#9a9a9a}',
  '.attachments{color:#9a9a9a;font-size:12px}',
  '.notice{color:#c98a8a}',
].join('\n');

/**
 * Self-contained HTML document holding every projected row, escaped and readable off-device.
 * The transcript is inlined as text (not wrapped in base64), so a reader sees it without scripts.
 */
function renderHtml(conversationId, rows, meta) {
  const body = rows.map(row => {
    if (row.role === 'notice') return `<p class="notice">${escapeHtml(row.text)}</p>`;
    if (row.role === 'tool') {
      const status = row.status ?? 'pending';
      const args = row.arguments ? `<pre class="args">${escapeHtml(row.arguments)}</pre>` : '';
      const out = row.output ? `<pre class="out">${escapeHtml(row.output)}</pre>` : '';
      return `<section class="row tool ${escapeHtml(status)}"><h2>${escapeHtml(row.name ?? 'tool')} <span class="status">[${escapeHtml(status)}]</span></h2>${args}${out}</section>`;
    }
    if (row.role === 'thinking') {
      return `<section class="row thinking"><h2>thinking</h2><p>${escapeHtml(row.text ?? '')}</p></section>`;
    }
    const heading = escapeHtml(row.role === 'user' ? 'user' : row.role === 'assistant' ? 'assistant' : row.role);
    const attachments = row.attachments
      ? `<p class="attachments">(+${Number(row.attachments)} attachment${row.attachments === 1 ? '' : 's'})</p>`
      : '';
    return `<section class="row ${escapeHtml(row.role)}"><h2>${heading}</h2>${attachments}<p>${escapeHtml(row.text ?? '')}</p></section>`;
  }).join('\n');
  return [
    '<!DOCTYPE html>',
    '<html lang="en">',
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    `<title>Pi conversation ${escapeHtml(conversationId)}</title>`,
    `<style>${HTML_STYLE}</style>`,
    '</head>',
    '<body>',
    `<header><h1>Pi conversation ${escapeHtml(conversationId)}</h1><p class="meta">Exported ${escapeHtml(meta.exportedAt)} · ${rows.length} messages${meta.truncated ? ' · truncated' : ''}</p></header>`,
    '<main>',
    body,
    '</main>',
    '</body>',
    '</html>',
    '',
  ].join('\n');
}

/**
 * Export a conversation's committed transcript. `limit` caps the entries read and the result says
 * `truncated` when it did; an unsupported format is rejected rather than guessed.
 */
export async function exportConversation(conversation, { format = 'markdown', includeThinking = false, limit = EXPORT_LIMIT, now = Date.now } = {}, context) {
  if (!EXPORT_TARGETS.includes(format)) throw new Error(`Unsupported export format: ${format}`);
  const cap = Number.isInteger(limit) && limit > 0 ? limit : EXPORT_LIMIT;
  const { items, truncated } = await collectEntries(conversation, cap, context);
  const exportedAt = new Date(typeof now === 'function' ? now() : now).toISOString();
  const projected = projectTranscript(items);
  const rows = includeThinking ? projected : projected.filter(row => row.role !== 'thinking');
  const meta = { exportedAt, truncated };
  const content = format === 'html'
    ? renderHtml(conversation.id, rows, meta)
    : format === 'jsonl'
      ? renderJsonl(rows)
      : format === 'json'
        ? renderJson(conversation.id, rows, meta)
        : renderMarkdown(conversation.id, rows, meta);
  return {
    format,
    conversationId: String(conversation.id),
    exportedAt,
    truncated,
    mime: EXPORT_MIME[format] ?? 'text/plain',
    entries: items.length,
    messages: rows.length,
    bytes: content.length,
    content,
  };
}

/** The reserved root conversation's id, for callers that want to pin it. */
export const ROOT_ID = ROOT_CONVERSATION_ID;

/**
 * The terminal frame of a manual compaction, from the task's own settled record. Nothing is
 * assumed: a run that failed reports its own message and carries no entry id.
 */
export function compactionOutcome(taskId, settled) {
  const outcome = settled?.state?.outcome;
  return {
    taskId: String(taskId),
    status: typeof outcome?.status === 'string' ? outcome.status : 'terminal',
    ...(outcome?.status === 'failed' ? { error: String(outcome.error?.message ?? 'compaction failed') } : {}),
    ...(outcome?.result?.entryId !== undefined ? { entryId: String(outcome.result.entryId) } : {}),
    ...(outcome?.result?.submissionId !== undefined ? { submissionId: String(outcome.result.submissionId) } : {}),
  };
}
