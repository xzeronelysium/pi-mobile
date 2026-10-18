// App-owned `CredentialStore` for pi-ai, backed by the Android Keystore vault.
//
// pi-ai's contract (`auth/types.d.ts`) requires one credential per provider, `modify` as the only
// write path with per-provider mutual exclusion, and rejection only on storage failure. The secret
// bytes never touch app-visible storage in plaintext: every payload is sealed by
// `KeystoreCredentialStore.kt` (AES-GCM with a non-exportable Android Keystore key) before it is
// written to the app-private vault file.
//
// The in-memory map is the read view; the vault is written through on every mutation so a WebView
// reload, process restart or upgrade cannot lose or downgrade a credential.

export function createCredentialStore(call) {
  const credentials = new Map();
  const chains = new Map();
  let loaded = false;

  /** Serialize tasks per provider id, keeping the chain entry until the active work settles. */
  const enqueue = (providerId, task) => {
    const previous = chains.get(providerId) ?? Promise.resolve();
    const next = previous.then(task, task);
    const guarded = next.catch(() => undefined);
    chains.set(providerId, guarded);
    guarded.then(() => {
      if (chains.get(providerId) === guarded) chains.delete(providerId);
    });
    return next;
  };

  return {
    /** Read the sealed vault into the in-memory view. Called once per runtime start. */
    async load() {
      const rows = await call('credential.list', {});
      credentials.clear();
      for (const row of Array.isArray(rows) ? rows : []) {
        if (!row || typeof row.provider !== 'string' || typeof row.payload !== 'string') continue;
        try {
          const credential = JSON.parse(row.payload);
          if (credential && typeof credential.type === 'string') credentials.set(row.provider, credential);
        } catch {
          // A payload this build cannot parse is left sealed in the vault rather than deleted.
        }
      }
      loaded = true;
      return { count: credentials.size };
    },

    isLoaded() {
      return loaded;
    },

    async read(providerId) {
      return credentials.get(providerId);
    },

    async list() {
      return [...credentials].map(([providerId, credential]) => ({ providerId, type: credential.type }));
    },

    async modify(providerId, fn) {
      return enqueue(providerId, async () => {
        const next = await fn(credentials.get(providerId));
        if (next !== undefined) {
          await call('credential.put', { provider: providerId, payload: JSON.stringify(next) });
          credentials.set(providerId, next);
        }
        return next ?? credentials.get(providerId);
      });
    },

    async delete(providerId) {
      await enqueue(providerId, async () => {
        await call('credential.delete', { provider: providerId });
        credentials.delete(providerId);
      });
      return undefined;
    },
  };
}

/** Store an api-key credential without exposing the key to the transcript. */
export async function rememberKey(store, providerId, key, env) {
  return store.modify(providerId, async () => ({
    type: 'api_key',
    key,
    ...(env && Object.keys(env).length ? { env } : {}),
  }));
}

/** Explicit logout: remove the provider's credential entirely. */
export async function forgetKey(store, providerId) {
  return store.delete(providerId);
}

/** Non-secret projection of a stored credential for events and status UI. */
export function describeCredential(credential) {
  if (!credential) return null;
  return {
    type: credential.type,
    ...(typeof credential.expires === 'number' ? { expires: credential.expires, expired: Date.now() >= credential.expires } : {}),
    ...(typeof credential.accountId === 'string' ? { accountId: credential.accountId } : {}),
    ...(credential.env && typeof credential.env === 'object' ? { env: Object.keys(credential.env) } : {}),
    ...(typeof credential.key === 'string' && credential.key ? { keyLength: credential.key.length } : {}),
    ...(typeof credential.refresh === 'string' && credential.refresh ? { refreshable: true } : {}),
  };
}
