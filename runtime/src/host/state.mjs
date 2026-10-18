// The one piece of mutable runtime state the host surfaces share.
//
// `Init` opens the harness and the first conversation; the session surface switches it; the close
// path clears it. Keeping that state here — instead of in a closure spread across one 900-line
// factory — is what lets the surfaces stay independent modules while still agreeing on which
// conversation is open and which durable event stream is attached.

import { watchEvents } from '@earendil-works/pi-durable';
import { projectTranscript } from '../ui.mjs';
import { createTranscriptProjection } from '../transcript.mjs';
import { listSessions } from '../sessions.mjs';

export function createHostState({ bridge, context }) {
  const state = {
    harness: undefined,
    conversation: undefined,
    events: undefined,
    projection: undefined,
    active: false,
    workspaceRoot: '/',
  };

  const requireOpen = () => { if (!state.conversation) throw new Error('PiNative not initialized'); };
  const switchGuard = () => { if (state.active) throw new Error('Cannot switch conversation while a send is active'); };
  const conversationIdOf = requested => (Number.isInteger(requested) ? requested : state.conversation.id);
  const historyFrame = page => ({ ...page, rows: projectTranscript(page.items) });

  /**
   * (Re)attach the durable event stream and its transcript projection.
   *
   * The projection is the incremental half of the transcript: the snapshot seeds it, and every
   * later `message` frame carries only the rows that changed in that batch — upserted by key — so
   * the UI merges a delta instead of re-reading the whole conversation. The `message` event keeps
   * its `events` payload; the projected rows ride alongside under `value`. `history()` remains the
   * reconciliation read on load, settle, abort, session switch and error, so nothing can drift.
   */
  const restartEvents = async id => {
    if (state.events) { await state.events.stop(); state.events = undefined; }
    state.events = await watchEvents(state.harness, id, context);
    state.projection = createTranscriptProjection();
    // The projection IS the payload: the raw durable events used to ride along too, which sent the
    // same content twice (and, for this seed frame, the whole conversation as durable entries).
    // Nothing consumed them, so only the projected rows cross now.
    bridge.emit({ type: 'message', value: state.projection.apply([state.events.snapshot]) });
    state.events.start(async batch => { bridge.emit({ type: 'message', value: state.projection.apply(batch) }); });
  };

  const activate = async handle => {
    switchGuard();
    await restartEvents(handle.id);
    state.conversation = handle;
    bridge.emit({ type: 'session', conversationId: state.conversation.id });
    return handle;
  };

  /** Re-emit the drawer's session list; called wherever the set of conversations changes. */
  const emitConversations = async () => {
    const value = await listSessions(state.harness, { currentId: state.conversation.id }, context);
    bridge.emit({ type: 'conversations', value });
    return value;
  };

  /**
   * The conversation a launch asked to start on, or null when there is nothing to resume: a request
   * that is not a conversation id, one that names no conversation, or the one already open. Nothing
   * is created or revived to satisfy it.
   */
  const resumeRequested = async requested => {
    if (!Number.isInteger(requested) || requested === state.conversation.id) return null;
    return (await state.harness.conversation(requested, context)) ?? null;
  };

  return { state, requireOpen, switchGuard, conversationIdOf, historyFrame, restartEvents, activate, emitConversations, resumeRequested };
}
