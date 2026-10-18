// Provider, credential and one-shot operation surface.
//
// Everything the app can ask a provider to do outside a conversation turn: list models, answer
// whether a provider is configured, store a key, run the provider's own sign-in flow, refresh the
// catalog, and drive the image/classifier/deferred operations. The module owns the one in-flight
// login it guards; the conversation state stays with the session surface.

import { capabilityRows, modelRows, updateProviderCatalog } from '../providers.mjs';
import { describeCredential, rememberKey } from '../credentials.mjs';
import { createInteraction } from '../interaction.mjs';
import {
  AUTH_TYPE_API_KEY,
  AUTH_TYPE_OAUTH,
  failureCause,
  failureText,
  message,
  summarizeMessage,
} from './util.mjs';

export function createProviderSurface({ bridge, models, credentials, catalog, requireOpen }) {
  /** One sign-in at a time: the flow is interactive, so a second one would race the first's prompts. */
  let login = null;

  const providerOf = id => {
    const providerId = typeof id === 'string' ? id.trim() : '';
    const provider = models.getProviders().find(entry => entry.id === providerId);
    if (!provider) throw new Error(providerId ? `Unknown provider: ${providerId}` : 'Provider required');
    return provider;
  };
  const rowOf = id => catalog.find(row => row.id === id) ?? null;
  /** Which chat model to use when the caller did not name one. */
  const defaultModelId = providerId => models.getModels(providerId)[0]?.id ?? '';
  const listOf = (type, providerId) => (type && type !== 'chat'
    ? models.getModelsOfType(type, providerId)
    : models.getModels(providerId));

  const api = {
    providers() { return catalog; },
    capabilities() { return capabilityRows(catalog); },

    async models(query = {}) {
      const providerId = typeof query.provider === 'string' && query.provider ? query.provider : undefined;
      const type = typeof query.type === 'string' && query.type ? query.type : 'chat';
      const value = { provider: providerId ?? null, type, models: modelRows(listOf(type, providerId)) };
      bridge.emit({ type: 'models', value });
      return value;
    },

    async available(query = {}) {
      const providerId = typeof query.provider === 'string' && query.provider ? query.provider : undefined;
      const type = typeof query.type === 'string' && query.type ? query.type : 'chat';
      const list = type === 'chat'
        ? await models.getAvailable(providerId)
        : await models.getAvailableOfType(type, providerId);
      const value = { provider: providerId ?? null, type, models: modelRows(list) };
      bridge.emit({ type: 'available', value });
      return value;
    },

    /** Auth status for every provider without resolving secrets or refreshing a token. */
    async authState(query = {}) {
      const rows = [];
      for (const provider of models.getProviders()) {
        if (query.provider && provider.id !== query.provider) continue;
        const stored = await credentials.read(provider.id);
        let check;
        try { check = await models.checkAuth(provider.id); } catch { check = undefined; }
        rows.push({
          provider: provider.id,
          name: provider.name,
          configured: Boolean(check),
          source: check?.source ?? null,
          type: check?.type ?? stored?.type ?? null,
          stored: describeCredential(stored),
        });
      }
      const value = { providers: rows };
      if (!query.quiet) bridge.emit({ type: 'auth.state', value });
      return value;
    },

    async setKey(config) {
      const provider = providerOf(config?.provider);
      if (!provider.auth?.apiKey) throw new Error(`${provider.name} does not accept an API key`);
      if (typeof config.apiKey !== 'string' || !config.apiKey.trim()) throw new Error(`${provider.name} API key required`);
      await rememberKey(credentials, provider.id, config.apiKey.trim());
      bridge.emit({ type: 'auth.state', value: { provider: provider.id, type: AUTH_TYPE_API_KEY, configured: true } });
      return { provider: provider.id, type: AUTH_TYPE_API_KEY };
    },

    /**
     * Resolve request auth for a provider, refreshing an expired OAuth token through the provider's
     * own `refresh()` under the credential-store lock. The token itself is never returned.
     */
    async getAuth(config) {
      const provider = providerOf(typeof config === 'string' ? config : config?.provider);
      const result = await models.getAuth(provider.id);
      const value = {
        provider: provider.id,
        configured: Boolean(result),
        source: result?.source ?? null,
        baseUrl: result?.auth?.baseUrl ?? null,
        headerNames: Object.keys(result?.auth?.headers ?? {}),
        hasApiKey: typeof result?.auth?.apiKey === 'string',
      };
      bridge.emit({ type: 'auth.resolved', value });
      return value;
    },

    async logout(config) {
      const provider = providerOf(config?.provider);
      await models.logout(provider.id);
      bridge.emit({ type: 'auth.state', value: { provider: provider.id, type: null, configured: false } });
      return { provider: provider.id, removed: true };
    },

    /**
     * Run the provider's own sign-in flow. Semantics live upstream: `Models.login` calls the
     * provider's `apiKey.login`/`oauth.login`, drives it with our Android interaction adapter and
     * persists the returned credential through `modify`.
     */
    async login(config) {
      const provider = providerOf(config?.provider);
      const type = config?.type === AUTH_TYPE_OAUTH ? AUTH_TYPE_OAUTH : AUTH_TYPE_API_KEY;
      if (type === AUTH_TYPE_OAUTH) {
        if (!provider.auth?.oauth) throw new Error(`${provider.name} has no subscription sign-in`);
        if (models.getProviders().find(entry => entry.id === provider.id)?.auth.oauth === undefined) throw new Error(`${provider.name} has no subscription sign-in`);
      } else if (!provider.auth?.apiKey) {
        throw new Error(`${provider.name} does not accept an API key`);
      }
      if (login) throw new Error('A sign-in is already in progress');
      const controller = new AbortController();
      login = { provider: provider.id, type, controller };
      const row = rowOf(provider.id);
      bridge.emit({
        type: 'auth.started',
        provider: provider.id,
        name: provider.name,
        authType: type,
        subscription: row?.oauthSubscription === true,
      });
      const interaction = createInteraction(bridge.call, event => bridge.emit(event), controller.signal);
      let deviceId;
      if (typeof config?.deviceId === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(config.deviceId)) {
        deviceId = config.deviceId;
      }
      try {
        const credential = await models.login(provider.id, type, interaction, {
          getDeviceId: () => deviceId,
          ...(typeof config?.agentName === 'string' && config.agentName ? { agentName: config.agentName } : {}),
        });
        const summary = describeCredential(credential);
        const value = { provider: provider.id, authType: type, credential: summary };
        bridge.emit({ type: 'auth.settled', ...value, value });
        return value;
      } catch (error) {
        const cancelled = controller.signal.aborted;
        const cause = failureCause(error);
        bridge.emit({
          type: 'auth.failed',
          provider: provider.id,
          authType: type,
          cancelled,
          message: cancelled ? 'Sign-in cancelled' : failureText(error),
          // Surface the vault's own reason/code so the sheet shows why the write failed instead of
          // pi-ai's generic wrapper.
          ...(cause.text ? { reason: cause.text } : {}),
          ...(cause.code ? { causeCode: cause.code } : {}),
        });
        throw error;
      } finally {
        if (login?.controller === controller) login = null;
        bridge.emit({ type: 'auth.finished', provider: provider.id });
      }
    },

    /** Cancel an in-flight sign-in; the flow sees its own abort signal and unwinds. */
    cancelLogin() {
      if (!login) return { cancelled: false };
      login.controller.abort(new Error('Sign-in cancelled'));
      const provider = login.provider;
      login = null;
      bridge.emit({ type: 'auth.finished', provider, cancelled: true });
      return { cancelled: true, provider };
    },

    async refresh(query = {}) {
      const controller = new AbortController();
      const providers = typeof query.provider === 'string' && query.provider ? [query.provider] : undefined;
      const result = await models.refresh({ allowNetwork: query.allowNetwork !== false, force: query.force === true, ...(providers ? { providers } : {}), signal: controller.signal });
      updateProviderCatalog(models, catalog);
      const value = { aborted: result.aborted === true, errors: [...result.errors].map(([id, error]) => ({ id, message: message(error) })) };
      bridge.emit({ type: 'refresh', value });
      return value;
    },

    /** Real image generation through the provider that owns the image model. */
    async generateImages(config) {
      const provider = providerOf(config?.provider);
      const model = models.getModelOfType('image', provider.id, config?.modelId);
      if (!model) {
        const known = models.getModelsOfType('image', provider.id).map(entry => entry.id);
        const error = known.length
          ? `Unknown ${provider.name} image model: ${config?.modelId ?? '(none)'}`
          : `${provider.name} has no image models`;
        const value = { error, provider: provider.id, modelId: config?.modelId ?? null, known };
        bridge.emit({ type: 'images', value });
        return value;
      }
      const prompt = typeof config?.prompt === 'string' ? config.prompt : '';
      const input = Array.isArray(config?.input) && config.input.length
        ? config.input
        : [{ type: 'text', text: prompt }];
      const options = config?.metadata && typeof config.metadata === 'object' ? { metadata: config.metadata } : undefined;
      // `Models.generateImages` never rejects: unknown/unconfigured providers and providers without
      // an images implementation come back as an error result.
      const result = await models.generateImages(model, { input }, options);
      const value = {
        provider: result.provider,
        model: result.model,
        stopReason: result.stopReason,
        error: result.errorMessage ?? null,
        responseId: result.responseId ?? null,
        usage: result.usage ?? null,
        output: (result.output ?? []).map(part => (part.type === 'text'
          ? { type: 'text', text: part.text }
          : { type: 'image', mimeType: part.mimeType ?? part.mediaType ?? null, base64: part.data ?? part.base64 ?? null })),
      };
      bridge.emit({ type: 'images', value });
      return value;
    },

    /** Structured classification through the provider's classifier model. */
    async classify(config) {
      const provider = providerOf(config?.provider);
      const model = models.getModelOfType('classifier', provider.id, config?.modelId);
      if (!model) {
        const known = models.getModelsOfType('classifier', provider.id).map(entry => entry.id);
        const error = known.length
          ? `Unknown ${provider.name} classifier model: ${config?.modelId ?? '(none)'}`
          : `${provider.name} has no classifier models`;
        const value = { error, provider: provider.id, modelId: config?.modelId ?? null, known };
        bridge.emit({ type: 'classify', value });
        return value;
      }
      const contextInput = {
        state: config?.state && typeof config.state === 'object' ? config.state : {},
        questions: config?.questions && typeof config.questions === 'object' ? config.questions : {},
        ...(Array.isArray(config?.images) && config.images.length ? { images: config.images } : {}),
      };
      const result = await models.classify(model, contextInput);
      const value = {
        provider: result.provider,
        model: result.model,
        stopReason: result.stopReason,
        error: result.errorMessage ?? null,
        answers: result.answers ?? {},
        usage: result.usage ?? null,
      };
      bridge.emit({ type: 'classify', value });
      return value;
    },

    /** Continue a provider-deferred response (`AssistantMessage.deferred`). */
    async fetchDeferred(config) {
      requireOpen();
      const provider = providerOf(config?.provider);
      const model = models.getModel(provider.id, config?.modelId);
      if (!model) throw new Error(`Unknown ${provider.name} model: ${config?.modelId}`);
      const handle = config?.handle;
      if (!handle || typeof handle.id !== 'string' || !handle.id) throw new Error('Deferred handle required');
      const result = await models.fetchDeferred(model, handle);
      const value = summarizeMessage(result);
      bridge.emit({ type: 'deferred', value });
      return value;
    },

    async cancelDeferred(config) {
      requireOpen();
      const provider = providerOf(config?.provider);
      const model = models.getModel(provider.id, config?.modelId);
      if (!model) throw new Error(`Unknown ${provider.name} model: ${config?.modelId}`);
      const handle = config?.handle;
      if (!handle || typeof handle.id !== 'string' || !handle.id) throw new Error('Deferred handle required');
      await models.cancelDeferred(model, handle);
      return { provider: provider.id, cancelled: true };
    },
  };

  return { providerOf, defaultModelId, api };
}
