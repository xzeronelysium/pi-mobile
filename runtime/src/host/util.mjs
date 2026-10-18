// Shared helpers for the host surface modules.
//
// These are the small, dependency-free pieces every host module used to define inline: error text,
// the auth type constants, and the two projections that normalize upstream results. Keeping them in
// one place means the provider, session and lifecycle modules cannot drift on wording or shape.

export const AUTH_TYPE_API_KEY = 'api_key';
export const AUTH_TYPE_OAUTH = 'oauth';

export const message = error => (error instanceof Error ? error.message : String(error));

/**
 * The newest page of a read, handed back oldest-first, with the page's own continuation. The cursor
 * now points towards the conversation's START, so a page that carries `next` did not reach the first
 * entry: it is a page of a longer conversation, never the whole thing.
 */
export function newestFirst(page) {
  return { items: [...page.items].reverse(), ...(page.next === undefined ? {} : { next: page.next }) };
}

/**
 * pi-ai wraps credential-store failures in a generic `ModelsError` ("Credential store modify failed
 * for <provider>") and that wrapper overwrites the native failure's `code` (for example "keystore")
 * with its own ("auth"). Follow one level of `cause` so a sign-in failure can carry the vault's own
 * reason and code. The cause is the bridge's coded error, never secret material.
 */
export const failureCause = error => {
  const cause = error instanceof Error ? error.cause : undefined;
  if (cause instanceof Error) return { text: cause.message || cause.name, code: typeof cause.code === 'string' ? cause.code : '' };
  if (typeof cause === 'string') return { text: cause, code: '' };
  return { text: '', code: '' };
};

/** The wrapper message with the underlying cause text appended when the wrapper omitted it. */
export const failureText = error => {
  const base = message(error);
  const { text } = failureCause(error);
  return text && !base.includes(text) ? `${base}: ${text}` : base;
};

/** Non-secret projection of an assistant message, for one-shot operations off the transcript. */
export function summarizeMessage(result) {
  if (!result) return null;
  const text = Array.isArray(result.content)
    ? result.content.filter(part => part?.type === 'text').map(part => part.text).join('')
    : '';
  return {
    provider: result.provider ?? null,
    model: result.model ?? null,
    text,
    stopReason: result.stopReason ?? null,
    errorMessage: result.errorMessage ?? null,
    usage: result.usage ?? null,
    ...(result.deferred ? { deferred: result.deferred } : {}),
  };
}
