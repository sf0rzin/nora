/**
 * NORA Core — Settings hub.
 *
 * Thin layout: sets up the `.page` container and the hub header. The internal
 * per-section navigation (Conta · Segurança · Workspace · Contexto da
 * empresa) and the respective panels live in the client page (context/page.tsx),
 * where the active section state can drive the panel switch.
 *
 * No IAM here: Core is individual; teams and permissions belong to Enterprise.
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
