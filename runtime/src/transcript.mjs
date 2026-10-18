// Incremental projection of Pi Durable agent events onto the transcript rows the UI applies.
//
// The durable `message` event already carries the conversation's events; the Kotlin transcript
// used to answer each one with a fresh whole-transcript `history()` read, so every streaming batch
// re-serialized the entire conversation to move a few characters. This module removes that: it
// owns the running projection and, for each commit, emits only the rows that changed — upserted by
// their stable key — so the UI merges a delta instead of re-reading.
//
// The row shape is not redefined here: entries are projected by the same `appendEntry` that
// `projectTranscript` (the whole-transcript read, still used for reconciliation) replays, so the
// two can never disagree. The in-flight assistant partial (the `message_start`/`message_update`
// stream) is projected through that same function under a synthetic entry id, so a streamed answer
// is the same rows it will be once it is committed.

import { appendEntry } from './ui.mjs';

/** The synthetic entry id the in-flight assistant partial is projected under; never a durable id. */
const LIVE_KEY = 'live';

const clone = value => (value === undefined ? undefined : JSON.parse(JSON.stringify(value)));

/** Append `delta` to the string at `segments`, creating the path; mirrors pi-ai's streamed args. */
function appendAtPath(node, segments, delta) {
  if (segments.length === 0) return typeof node === 'string' ? node + delta : node;
  const [head, ...rest] = segments;
  const base = node && typeof node === 'object' ? { ...node } : {};
  base[head] = appendAtPath(base[head], rest, delta);
  return base;
}

/**
 * Fold one `MessageChange` into the in-flight assistant message.
 * @returns the replacement message when the change is a whole-message replacement, else undefined
 */
function applyChange(message, change) {
  if (change?.type === 'message') return change.message;
  if (!Array.isArray(message.content)) message.content = [];
  const index = change?.contentIndex;
  if (typeof index !== 'number' || index < 0) return undefined;
  switch (change.type) {
    case 'text_start':
    case 'thinking_start':
    case 'toolcall_start':
    case 'block':
      message.content[index] = change.block;
      break;
    case 'text_delta':
      message.content[index] = { ...(message.content[index] ?? { type: 'text' }), type: 'text', text: `${message.content[index]?.text ?? ''}${change.delta ?? ''}` };
      break;
    case 'thinking_delta':
      message.content[index] = { ...(message.content[index] ?? { type: 'thinking' }), type: 'thinking', thinking: `${message.content[index]?.thinking ?? ''}${change.delta ?? ''}` };
      break;
    case 'toolcall_delta': {
      const block = message.content[index] ?? { type: 'toolCall', arguments: {} };
      const root = block.arguments && typeof block.arguments === 'object' ? { ...block.arguments } : {};
      message.content[index] = { ...block, arguments: appendAtPath(root, Array.isArray(change.path) ? change.path : [], change.delta ?? '') };
      break;
    }
    default:
      break;
  }
  return undefined;
}

/**
 * A running transcript projection for one conversation's event stream.
 *
 * `apply(events)` takes one batch of durable agent events (including the single-event snapshot
 * batch) and returns the frame to hand the UI:
 *
 *   { rows, removed, full, snapshot }
 *   - `rows`     the changed rows, upserted by `key`
 *   - `removed`  the keys the UI drops (a retired streaming row)
 *   - `full`     true only for a snapshot: `rows` is then the whole projection, not a delta
 *   - `snapshot` true only when that whole projection was seeded from a durable snapshot, i.e. the
 *                frame really does carry the whole conversation. The UI may replace its transcript
 *                for such a frame and for nothing else: a frame that cannot say this cannot delete a
 *                row it happens not to carry.
 */
export function createTranscriptProjection() {
  /** Every live row by key, in the order it first appeared. */
  const byKey = new Map();
  /** Tool-call id -> the call's row, so a result folds onto the call it answers. */
  const tools = new Map();
  let live = null;
  let liveKeys = [];

  const projectEntry = entry => {
    const scratch = [];
    const touched = appendEntry(scratch, tools, entry);
    for (const row of scratch) byKey.set(row.key, row);
    return touched;
  };

  /** Re-project the in-flight partial; retire the keys the partial no longer occupies. */
  const refreshLive = () => {
    const before = liveKeys;
    const scratch = [];
    const touched = appendEntry(scratch, tools, { id: LIVE_KEY, model: [live.message] });
    for (const row of scratch) byKey.set(row.key, row);
    liveKeys = touched;
    const stale = before.filter(key => !touched.includes(key));
    for (const key of stale) byKey.delete(key);
    return { touched, stale };
  };

  const endLive = removed => {
    if (!live) return;
    for (const key of liveKeys) { byKey.delete(key); removed.push(key); }
    live = null;
    liveKeys = [];
  };

  const seed = (entries, generationMessage) => {
    byKey.clear();
    tools.clear();
    live = null;
    liveKeys = [];
    for (const record of entries ?? []) projectEntry(record?.entry ?? record);
    if (generationMessage !== undefined) {
      live = { message: clone(generationMessage) };
      refreshLive();
    }
  };

  return {
    /**
     * Apply one batch of durable agent events and return the frame the UI should merge.
     * @param {readonly object[]} events
     */
    apply(events) {
      const touched = [];
      const removed = [];
      let full = false;
      for (const event of events ?? []) {
        switch (event?.type) {
          case 'snapshot':
            seed(event.entries, event.generation?.message);
            full = true;
            break;
          case 'entry_appended':
            touched.push(...projectEntry(event.entry));
            break;
          case 'message_end':
            endLive(removed);
            touched.push(...projectEntry(event.entry));
            break;
          case 'message_start':
            // Only the assistant partial streams; a user/system message_start is a committed entry
            // announcing itself, and its `message_end` projects it.
            if (event.message?.role === 'assistant') {
              live = { message: clone(event.message) };
              const { touched: added, stale } = refreshLive();
              touched.push(...added);
              removed.push(...stale);
            }
            break;
          case 'message_update': {
            if (!live) live = { message: { role: 'assistant', content: [] } };
            for (const change of event.changes ?? []) {
              const replaced = applyChange(live.message, change);
              if (replaced !== undefined) live.message = replaced;
            }
            const { touched: added, stale } = refreshLive();
            touched.push(...added);
            removed.push(...stale);
            break;
          }
          default:
            // usage/inbox/turn/run/tool-progress events change no transcript row.
            break;
        }
      }
      if (full) return { rows: [...byKey.values()], removed: [], full: true, snapshot: true };
      // A key the batch retired is gone from this frame even if the same batch also touched it
      // (one commit can grow the in-flight partial AND commit its entry): dropping precedes
      // upserting, so a later turn reusing the synthetic key cannot resurrect a retired row.
      const keys = [...new Set(touched)];
      const gone = new Set(removed);
      return {
        rows: keys.filter(key => byKey.has(key) && !gone.has(key)).map(key => byKey.get(key)),
        removed: [...gone],
        full: false,
        snapshot: false,
      };
    },
    /** The whole projection right now, in row order (a reconciliation read). */
    rows() {
      return [...byKey.values()];
    },
  };
}
