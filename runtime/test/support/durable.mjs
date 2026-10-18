// Shared fixture: a real Pi Durable Harness over MemoryStorage with pi-ai's faux provider, so
// session/usage/queue/export tests exercise the actual durable engine without a network.
import { BACKGROUND_CONTEXT } from '@earendil-works/chord/context';
import { createModels } from '@earendil-works/pi-ai/models';
import { fauxProvider } from '@earendil-works/pi-ai/providers/faux';
import { Harness, MemoryStorage, createRegistry } from '@earendil-works/pi-durable';

/**
 * Open a faux-backed harness, run `run`, and close it.
 * `createRoot: false` skips the root conversation for tests that create their own.
 * `registry` supplies an application registry (extensions installed by the caller); when absent the
 * harness gets an empty one, so a test that cares about tools or sections passes its own.
 * `settings` is layered over the deterministic defaults, so a test can reproduce the host's own
 * extension selection without losing the offline stream/retry policy.
 */
export async function withFauxHarness(run, { createRoot = true, registry, settings } = {}) {
  const context = BACKGROUND_CONTEXT;
  const faux = fauxProvider();
  const models = createModels();
  models.setProvider(faux.provider);
  const harness = await Harness.open(new MemoryStorage(), {
    models,
    registry: registry ?? createRegistry(),
    settings: { retry: { enabled: false }, compaction: { enabled: false }, stream: { transport: 'sse', maxRetries: 0 }, ...settings },
  }, context);
  const agent = { model: { provider: faux.provider.id, modelId: faux.getModel().id } };
  const root = createRoot ? await harness.root(context, { agent }) : undefined;
  try {
    await run({ context, faux, models, harness, root, agent, registry });
  } finally {
    await harness.close(context).catch(() => {});
  }
}
