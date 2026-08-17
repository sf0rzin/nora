/**
 * NORA Core — Settings hub.
 *
 * Thin layout: sets up the `.page` container and the hub header. The internal
 * per-section navigation (Conta · Segurança · Workspace · Contexto da
 * empresa) and the respective panels live in the client page (context/page.tsx),
 * where the active section state can drive the panel switch.
 *
 * IAM is a sibling ROUTE under this layout (settings/iam), not one of those sections: it
 * carries its own data loading and forms, so it keeps its own page. It is reached from the
 * "Administração" entry in the sidebar and from the command palette. MCP credentials
 * (settings/mcp, ADR 0041) are a sibling for the same reason, reached from the palette.
 */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="page" style={{ maxWidth: 880 }}>
      <header style={{ marginBottom: 32 }}>
        <h1 className="h1">Configurações</h1>
        <p className="lede" style={{ marginTop: 8 }}>
          Sua conta, segurança e workspace — tudo num lugar só.
        </p>
      </header>
      {children}
    </div>
  );
}
