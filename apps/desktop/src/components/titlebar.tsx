import { useMemo } from "react";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";
import { useWindowMaximized } from "@/hooks/use-window-maximized";

/**
 * Barra de título custom da janela principal.
 *
 * A janela "main" roda com `decorations: false` (sem a barra nativa do SO),
 * então as ações de janela (minimizar / maximizar / fechar) e o arraste são
 * implementados aqui.
 *
 * Cross-platform: usamos `getCurrentWebviewWindow().startDragging()` no
 * mousedown porque `-webkit-app-region: drag` / `data-tauri-drag-region` NÃO
 * funcionam no WebKitGTK (Linux). `startDragging()` cobre x11/wayland/macOS/
 * Windows — mesmo padrão já validado no dock (dock-bar.tsx). Maximizar por
 * duplo-clique é tratado manualmente via `onDoubleClick` (o SO não nos dá isso
 * de graça sem decorações nativas).
 */
function ControlButton({
  onClick,
  title,
  danger,
  children,
}: {
  onClick: () => void;
  title: string;
  danger?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      aria-label={title}
      className="grid place-items-center transition-colors"
      style={{
        width: 30,
        height: 26,
        borderRadius: 7,
        background: "transparent",
        border: "none",
        color: "var(--muted)",
        cursor: "pointer",
        padding: 0,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.background = danger
          ? "var(--danger)"
          : "rgba(0,0,0,0.06)";
        e.currentTarget.style.color = danger ? "#fff" : "var(--ink)";
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.background = "transparent";
        e.currentTarget.style.color = "var(--muted)";
      }}
    >
      {children}
    </button>
  );
}

export function Titlebar() {
  const win = useMemo(() => getCurrentWebviewWindow(), []);
  const maximized = useWindowMaximized();

  // Arraste — só botão esquerdo. `e.detail > 1` (segundo clique de um
  // duplo-clique) é ignorado pra não engolir o `onDoubleClick` que maximiza.
  const onDragMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0 || e.detail > 1) return;
    win.startDragging().catch((err) =>
      console.warn("[titlebar] startDragging failed:", err),
    );
  };

  const onToggleMaximize = () => {
    win.toggleMaximize().catch(() => {});
  };

  return (
    <header
      className="flex items-stretch shrink-0 select-none"
      style={{
        height: 36,
        background: "var(--sidebar)",
        borderBottom: "1px solid var(--border)",
      }}
    >
      {/* Região de arraste — preenche tudo menos os controles */}
      <div
        className="flex-1 min-w-0 flex items-center"
        onMouseDown={onDragMouseDown}
        onDoubleClick={onToggleMaximize}
        style={{ cursor: "default", paddingLeft: 14 }}
      >
        <span
          className="inline-flex items-center gap-1.5 pointer-events-none"
          style={{
            fontSize: 11,
            fontWeight: 500,
            letterSpacing: "0.06em",
            textTransform: "uppercase",
            color: "var(--muted)",
            opacity: 0.7,
          }}
        >
          NORA Desktop
        </span>
      </div>

      {/* Controles de janela */}
      <div className="flex items-center gap-0.5" style={{ padding: "0 6px 0 4px" }}>
        <ControlButton onClick={() => win.minimize().catch(() => {})} title="Minimizar">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
        </ControlButton>
        <ControlButton onClick={onToggleMaximize} title={maximized ? "Restaurar" : "Maximizar"}>
          {maximized ? (
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <rect x="8" y="8" width="11" height="11" rx="1.5" />
              <path d="M5 16V6.5A1.5 1.5 0 0 1 6.5 5H16" />
            </svg>
          ) : (
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <rect x="5" y="5" width="14" height="14" rx="2" />
            </svg>
          )}
        </ControlButton>
        <ControlButton onClick={() => win.close().catch(() => {})} title="Fechar" danger>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </ControlButton>
      </div>
    </header>
  );
}
