// MCP over Streamable HTTP, as the app reaches it: the persisted server list plus whatever each
// configured server is doing right now. Connection is always its own explicit step.

export function createMcpSurface({ bridge, mcp }) {
  return {
    /** The persisted server list plus whatever each configured server is doing right now. */
    async mcpState() {
      await mcp.load();
      const value = mcp.state();
      bridge.emit({ type: 'mcp', value });
      return value;
    },

    /** Configure a server. Nothing connects here; connection is its own explicit step. */
    async mcpAdd(config) { await mcp.load(); return mcp.add(config ?? {}); },

    async mcpRemove(config) { await mcp.load(); return mcp.remove(config ?? {}); },

    /** Connect and install the server's tools; a failure keeps the reason and reports no tools. */
    async mcpConnect(config) { await mcp.load(); return mcp.connect(config ?? {}); },

    async mcpDisconnect(config) { await mcp.load(); return mcp.disconnect(config ?? {}); },

    close() { return mcp.close(); },
  };
}
