// Built-in pi-ai providers, their sign-in capability matrix and catalog projection.
//
// Credentials are never stored here: `credentials.mjs` owns the Keystore-backed vault and this
// module only describes what each provider supports. The OAuth mechanism table is derived from
// the upstream flow sources in `@earendil-works/pi-ai/dist/auth/oauth/*.js`; it drives both the
// sign-in sheet and the capability report the user sees, so a provider whose flow needs a
// capability the host cannot supply is labelled instead of silently failing.

import { builtinProviders } from '@earendil-works/pi-ai/providers/all';

/**
 * Per-provider OAuth transport requirements, read from the upstream flows:
 * - `loopback`: the flow starts a loopback HTTP callback server (`auth/oauth/callback-server.js`).
 * - `deviceCode`: the flow emits a `device_code` event and polls (`device-code.js`).
 * - `manualCode`: the flow can be finished by pasting a code or redirect URL.
 * - `loopbackRequired`: the flow awaits the callback server without a fallback, so a host that
 *   cannot listen on a TCP port cannot complete this sign-in.
 */
export const OAUTH_MECHANISMS = {
  anthropic: { loopback: true, loopbackRequired: false, deviceCode: false, manualCode: true },
  'openai-codex': { loopback: true, loopbackRequired: false, deviceCode: true, manualCode: true },
  // Provider id `openai` uses the openai-chatgpt flow; the ChatGPT sign-in refuses to start without
  // its loopback server rather than letting the callback reach another process.
  openai: { loopback: true, loopbackRequired: true, deviceCode: false, manualCode: true },
  'github-copilot': { loopback: false, loopbackRequired: false, deviceCode: true, manualCode: false },
  openrouter: { loopback: true, loopbackRequired: true, deviceCode: false, manualCode: true },
  'kimi-coding': { loopback: false, loopbackRequired: false, deviceCode: true, manualCode: false },
  meta: { loopback: false, loopbackRequired: false, deviceCode: true, manualCode: false },
  xai: { loopback: false, loopbackRequired: false, deviceCode: true, manualCode: false },
  radius: { loopback: true, loopbackRequired: true, deviceCode: true, manualCode: false },
};

function providerRow(models, provider) {
  const all = models.getAllModels(provider.id);
  const count = type => all.filter(model => (model.type ?? 'chat') === type).length;
  const oauth = provider.auth?.oauth;
  const mechanism = oauth ? (OAUTH_MECHANISMS[provider.id] ?? null) : null;
  return {
    id: provider.id,
    name: provider.name,
    apiKey: Boolean(provider.auth?.apiKey),
    oauth: Boolean(oauth),
    oauthName: oauth?.name ?? null,
    oauthLoginLabel: oauth?.loginLabel ?? null,
    oauthSubscription: oauth?.isSubscription === true,
    oauthLoopback: mechanism ? mechanism.loopback : null,
    oauthLoopbackRequired: mechanism ? mechanism.loopbackRequired : null,
    oauthDeviceCode: mechanism ? mechanism.deviceCode : null,
    oauthManualCode: mechanism ? mechanism.manualCode : null,
    oauthMechanismKnown: Boolean(mechanism),
    // A provider with OAuth that has no table entry has never been source-reviewed; the UI says so
    // rather than guessing that its flow is portable.
    deferred: typeof provider.fetchDeferred === 'function',
    counts: { chat: count('chat'), image: count('image'), classifier: count('classifier'), total: all.length },
  };
}

/** Register every built-in provider and return the catalog rows the UI enumerates. */
export function installProviders(models) {
  const catalog = [];
  for (const provider of builtinProviders()) {
    models.setProvider(provider);
    catalog.push(providerRow(models, provider));
  }
  catalog.sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  return catalog;
}

/** Keep the app's provider rows in sync without replacing registered provider instances. */
export function updateProviderCatalog(models, catalog) {
  for (const provider of models.getProviders()) {
    const row = catalog.find(entry => entry.id === provider.id);
    if (row) Object.assign(row, providerRow(models, provider));
  }
}

/**
 * The ladder of pi thinking levels, lowest first. Used to project a model's `thinkingLevelMap`
 * into the flat list the Model/Effort sheet renders.
 */
export const THINKING_LEVELS = ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
const ALL_LEVELS = ['off', ...THINKING_LEVELS];

/**
 * Catalog projection for the picker. Everything the Model sheet shows travels with the row —
 * context window, pricing, input/output modalities and the model's own thinking levels — so the
 * UI never has to guess or re-derive provider metadata.
 *
 * Fields a model genuinely does not carry stay absent instead of becoming a default; a missing
 * `thinkingLevelMap` means "provider defaults apply", which is not the same as "no levels".
 */
export function modelRows(list) {
  return list.map(model => {
    const levels = model.thinkingLevelMap
      ? ALL_LEVELS.filter(level => model.thinkingLevelMap[level] !== null)
      : undefined;
    return {
      provider: model.provider,
      id: model.id,
      name: model.name ?? model.id,
      reasoning: model.reasoning === true,
      type: model.type ?? 'chat',
      api: model.api ?? null,
      ...(typeof model.contextWindow === 'number' ? { contextWindow: model.contextWindow } : {}),
      ...(typeof model.maxTokens === 'number' ? { maxTokens: model.maxTokens } : {}),
      ...(Array.isArray(model.input) ? { input: model.input } : {}),
      ...(Array.isArray(model.output) ? { output: model.output } : {}),
      ...(model.cost && typeof model.cost === 'object' ? { cost: model.cost } : {}),
      ...(levels ? { thinkingLevels: levels } : {}),
    };
  });
}

/**
 * Operations this build can actually attempt for a provider, with the reason when it cannot.
 * Used by the capability report so the UI never implies a feature works because metadata exists.
 */
export function capabilityRows(catalog) {
  return catalog.map(provider => {
    const signIn = [];
    if (provider.apiKey) signIn.push('api key');
    if (provider.oauth) {
      if (!provider.oauthMechanismKnown) signIn.push('subscription (flow not source-reviewed)');
      else {
        if (provider.oauthDeviceCode) signIn.push('device code');
        if (provider.oauthLoopback) signIn.push(`browser loopback${provider.oauthLoopbackRequired ? ' (required)' : ''}`);
        if (provider.oauthManualCode) signIn.push('pasted code');
      }
    }
    const operations = [];
    if (provider.counts.chat > 0) operations.push('chat');
    if (provider.counts.image > 0) operations.push('images');
    if (provider.counts.classifier > 0) operations.push('classify');
    if (provider.deferred) operations.push('deferred');
    return { id: provider.id, name: provider.name, signIn, operations };
  });
}
