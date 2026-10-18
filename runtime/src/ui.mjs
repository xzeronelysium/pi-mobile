// Project Pi Durable entries onto transcript rows for the UI.
//
// Every durable entry carries `model`: the provider messages it represents (`user`,
// `assistant` with `text`/`thinking`/`toolCall` blocks, `toolResult` with
// `toolCallId`/`toolName`/`isError`). Flattening those gives the Compose transcript its rows
// without going through the old CLI message shapes.
//
// Tool results are folded onto the tool-call row they answer, matched by `toolCallId`, so a
// call renders as one row that gains its output and status.

const TOOL_STATUS = { pending: 'pending', ok: 'ok', error: 'error' };

function textOf(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .filter(block => block?.type === 'text' && typeof block.text === 'string')
    .map(block => block.text)
    .join('');
}

function rowKey(entry, suffix) {
  return `${entry.id ?? '?'}:${suffix}`;
}

function userRows(entry, message) {
  const text = textOf(message.content);
  // Non-text blocks (images) are summarised rather than dropped silently.
  const others = Array.isArray(message.content)
    ? message.content.filter(block => block?.type !== 'text').length
    : 0;
  return [{
    key: rowKey(entry, 'user'),
    role: 'user',
    text,
    ...(others > 0 ? { attachments: others } : {}),
    timestamp: message.timestamp ?? null,
  }];
}

function assistantRows(entry, message, tools) {
  const rows = [];
  const blocks = Array.isArray(message.content) ? message.content : [{ type: 'text', text: textOf(message.content) }];
  blocks.forEach((block, index) => {
    if (block?.type === 'text') {
      if (block.text?.length) rows.push({ key: rowKey(entry, `text${index}`), role: 'assistant', text: block.text, timestamp: message.timestamp ?? null });
      return;
    }
    if (block?.type === 'thinking') {
      if (block.thinking?.length) rows.push({ key: rowKey(entry, `think${index}`), role: 'thinking', text: block.thinking });
      return;
    }
    if (block?.type === 'toolCall') {
      const row = {
        key: rowKey(entry, `tool${index}`),
        role: 'tool',
        name: block.name ?? 'tool',
        toolCallId: block.id ?? null,
        arguments: block.arguments === undefined ? '' : JSON.stringify(block.arguments),
        status: TOOL_STATUS.pending,
        timestamp: message.timestamp ?? null,
      };
      rows.push(row);
      if (row.toolCallId) tools.set(row.toolCallId, row);
    }
  });
  if (message.stopReason === 'error' && message.errorMessage) {
    rows.push({ key: rowKey(entry, 'error'), role: 'notice', text: message.errorMessage });
  }
  return rows;
}

function toolResultRows(entry, message, tools) {
  const output = textOf(message.content);
  const status = message.isError ? TOOL_STATUS.error : TOOL_STATUS.ok;
  const existing = message.toolCallId ? tools.get(message.toolCallId) : undefined;
  if (existing) {
    existing.status = status;
    existing.output = output;
    existing.durationMs = message.durationMs ?? null;
    return [];
  }
  return [{
    key: rowKey(entry, 'toolresult'),
    role: 'tool',
    name: message.toolName ?? 'tool',
    toolCallId: message.toolCallId ?? null,
    arguments: '',
    status,
    output,
    timestamp: message.timestamp ?? null,
  }];
}

function noticeFor(entry) {
  if (entry.kind === 'pi.compaction') return 'Context compacted.';
  if (entry.kind === 'pi.reset') return 'Conversation reset.';
  return null;
}

/**
 * Append one durable entry's rows onto `rows`, registering or folding tool calls in `tools`.
 *
 * This is the one place entries become rows: `projectTranscript` replays a whole page through it,
 * and `runtime/src/transcript.mjs` applies one commit's entries through it to produce the
 * incremental frames the UI upserts. Keeping both on this function is what keeps a single
 * projection owner — there is no second row shape to drift.
 *
 * @returns {string[]} the keys of the rows this entry produced or updated (a folded tool result
 *   updates the call's existing row rather than adding one)
 */
export function appendEntry(rows, tools, entry) {
  const touched = [];
  if (!entry || typeof entry !== 'object') return touched;
  // System entries record prompt/tool changes; they are not transcript rows.
  if (entry.kind === 'pi.system') return touched;
  const notice = noticeFor(entry);
  if (notice) {
    const row = { key: rowKey(entry, 'notice'), role: 'notice', text: notice };
    rows.push(row);
    touched.push(row.key);
    return touched;
  }
  for (const message of Array.isArray(entry.model) ? entry.model : []) {
    if (message?.role === 'user') {
      const produced = userRows(entry, message);
      rows.push(...produced);
      touched.push(...produced.map(row => row.key));
    } else if (message?.role === 'assistant') {
      const produced = assistantRows(entry, message, tools);
      rows.push(...produced);
      touched.push(...produced.map(row => row.key));
    } else if (message?.role === 'toolResult') {
      const existing = message.toolCallId ? tools.get(message.toolCallId) : undefined;
      const produced = toolResultRows(entry, message, tools);
      // A folded result mutates the call's row in place; report that row so the caller re-sends it.
      if (existing) touched.push(existing.key);
      rows.push(...produced);
      touched.push(...produced.map(row => row.key));
    }
  }
  return touched;
}

/**
 * @param {readonly unknown[]} records entry records (`{entry}`) or bare entries
 * @returns {Array<object>} ordered transcript rows
 */
export function projectTranscript(records) {
  const rows = [];
  const tools = new Map();
  for (const record of records ?? []) {
    const entry = record?.entry ?? record;
    appendEntry(rows, tools, entry);
  }
  return rows;
}
