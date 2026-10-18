// The bridge between pi-ai's `AuthInteraction` contract and the Android sign-in UI.
//
// Upstream flows drive sign-in by calling `interaction.notify(event)` for out-of-band steps
// (auth URL, device code, progress) and `interaction.prompt(prompt)` for user input (text, secret,
// select, manual_code). Both are forwarded to Kotlin as events; a prompt returns a promise that
// resolves when the Kotlin sheet replies, and rejects when the prompt or the whole login is
// cancelled. The flows are upstream code — nothing about their semantics is reimplemented here.

const PROMPT_TYPES = new Set(['text', 'secret', 'select', 'manual_code']);
const EVENT_TYPES = new Set(['info', 'auth_url', 'device_code', 'progress']);

function promptPayload(prompt) {
  const type = PROMPT_TYPES.has(prompt?.type) ? prompt.type : 'text';
  return {
    type,
    message: typeof prompt?.message === 'string' ? prompt.message : '',
    placeholder: typeof prompt?.placeholder === 'string' ? prompt.placeholder : null,
    options: type === 'select'
      ? (prompt.options ?? []).map(option => ({
        id: String(option.id),
        label: String(option.label ?? option.id),
        ...(option.description ? { description: String(option.description) } : {}),
      }))
      : null,
  };
}

function eventPayload(event) {
  const type = EVENT_TYPES.has(event?.type) ? event.type : 'info';
  return {
    type,
    ...(typeof event.message === 'string' ? { message: event.message } : {}),
    ...(typeof event.url === 'string' ? { url: event.url } : {}),
    ...(typeof event.instructions === 'string' ? { instructions: event.instructions } : {}),
    ...(typeof event.userCode === 'string' ? { userCode: event.userCode } : {}),
    ...(typeof event.verificationUri === 'string' ? { verificationUri: event.verificationUri } : {}),
    ...(typeof event.intervalSeconds === 'number' ? { intervalSeconds: event.intervalSeconds } : {}),
    ...(typeof event.expiresInSeconds === 'number' ? { expiresInSeconds: event.expiresInSeconds } : {}),
    ...(Array.isArray(event.links) ? { links: event.links.map(link => ({ url: String(link.url), ...(link.label ? { label: String(link.label) } : {}) })) } : {}),
  };
}

/**
 * @param call bridge request/response
 * @param emit bridge event sink
 * @param signal aborts the whole login flow
 */
export function createInteraction(call, emit, signal) {
  let sequence = 0;
  return {
    signal,
    async prompt(prompt) {
      const requestId = `auth-prompt-${++sequence}`;
      const payload = promptPayload(prompt);
      emit({ type: 'auth.prompt', requestId, prompt: payload });
      // The flows race a `manual_code` prompt against a browser callback and abort the loser
      // (see `waitForCallbackOrManualInput`). Mirroring that abort into Kotlin closes the sheet
      // instead of leaving a prompt the user can answer into nothing.
      const onAbort = () => {
        void call('auth.dismissPrompt', { requestId }).catch(() => undefined);
      };
      if (prompt?.signal) prompt.signal.addEventListener('abort', onAbort, { once: true });
      try {
        const answer = await call('auth.prompt', { requestId, ...payload });
        return typeof answer === 'string' ? answer : '';
      } finally {
        if (prompt?.signal) prompt.signal.removeEventListener('abort', onAbort);
        emit({ type: 'auth.prompt.done', requestId });
      }
    },
    notify(event) {
      // The bridge builds `{event: <type>, ...fields}`, so the payload must not reuse the `event`
      // key or it would overwrite the envelope's event name.
      emit({ type: 'auth.notice', notice: eventPayload(event) });
    },
  };
}
