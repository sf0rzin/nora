import { useMemo } from "react";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";

/**
 * Custom titlebar for the main window.
 *
 * The "main" window runs with `decorations: false` (no native OS bar), so the
 * window actions (minimize / maximize / close) and the drag are
 * implemented here. NORA mark on the left, controls on the right.
 *
 * Cross-platform: we use `getCurrentWebviewWindow().startDragging()` on
 * mousedown because `-webkit-app-region: drag` / `data-tauri-drag-region` do NOT
 * work on WebKitGTK (Linux). `startDragging()` covers x11/wayland/macOS/
 * Windows. Double-click maximize is handled via `onDoubleClick`.
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
        width: 34,
        height: 28,
        borderRadius: "var(--radius-sm)",
        background: "transparent",
        border: "none",
        color: "var(--muted)",
        cursor: "pointer",
        padding: 0,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.background = danger ? "var(--danger)" : "rgba(0,0,0,0.06)";
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

  // Drag — left button only. `e.detail > 1` (second click of a
  // double-click) is ignored so it doesn't swallow the `onDoubleClick` that maximizes.
  const onDragMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0 || e.detail > 1) return;
    win.startDragging().catch((err) => console.warn("[titlebar] startDragging failed:", err));
  };

  const onToggleMaximize = () => {
    win.toggleMaximize().catch(() => {});
  };

  return (
    <header
      className="flex items-stretch shrink-0 select-none"
      style={{
        height: "var(--titlebar-h)",
        background: "var(--sidebar)",
        borderBottom: "1px solid var(--border)",
      }}
    >
      {/* Drag region — fills everything but the controls (no logo). */}
      <div
        className="flex-1 min-w-0 flex items-center"
        onMouseDown={onDragMouseDown}
        onDoubleClick={onToggleMaximize}
        style={{ cursor: "default" }}
      />

      {/* Window controls. position+zIndex above the resize handles (z40)
          so the close/maximize click in the corner doesn't turn into a resize. */}
      <div
        className="flex items-center"
        style={{ gap: 2, padding: "0 6px 0 4px", position: "relative", zIndex: 50 }}
      >
        <ControlButton onClick={() => win.minimize().catch(() => {})} title="Minimizar">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
        </ControlButton>
        <ControlButton onClick={onToggleMaximize} title="Maximizar / restaurar">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <rect x="5" y="5" width="14" height="14" rx="2.5" />
          </svg>
        </ControlButton>
        <ControlButton onClick={() => win.close().catch(() => {})} title="Fechar" danger>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </ControlButton>
      </div>
    </header>
  );
}
