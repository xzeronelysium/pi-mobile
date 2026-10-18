// pi-ai ModelsStore backed by the app's native SQLite bridge. Share the host's database
// facade so catalog writes serialize with durable transactions and flow checkpoints.
export function createModelsStore(database) {
  let opening;
  const ready = () => opening ??= database.exec(
    'CREATE TABLE IF NOT EXISTS provider_model_catalogs (provider_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)',
  ).catch(error => { opening = undefined; throw error; });

  return {
    async read(providerId, options) {
      options?.signal?.throwIfAborted();
      await ready();
      options?.signal?.throwIfAborted();
      const row = await database.get('SELECT payload FROM provider_model_catalogs WHERE provider_id = ?', providerId);
      return row ? JSON.parse(row.payload) : undefined;
    },
    async write(providerId, entry, options) {
      options?.signal?.throwIfAborted();
      const payload = JSON.stringify(entry);
      await ready();
      options?.signal?.throwIfAborted();
      // REPLACE works on the SQLite version shipped with the oldest supported Android.
      await database.run('INSERT OR REPLACE INTO provider_model_catalogs (provider_id, payload) VALUES (?, ?)', providerId, payload);
    },
    async delete(providerId, options) {
      options?.signal?.throwIfAborted();
      await ready();
      options?.signal?.throwIfAborted();
      await database.run('DELETE FROM provider_model_catalogs WHERE provider_id = ?', providerId);
    },
  };
}
