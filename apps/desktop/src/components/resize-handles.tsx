import { useMemo } from "react";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";

/**
 * Pegas de redimensionamento para a janela sem decoração nativa.
 *
 * Sem a borda do SO não há como arrastar as bordas pra redimensionar, então
 * desenhamos 8 áreas invisíveis (4 bordas + 4 cantos) que chamam
 * `startResizeDragging(direção)`. Isso é cross-platform (Windows/macOS/Linux —
 * usa o mesmo mecanismo de move/resize do WM, igual ao startDragging do título).
 *
 * Ficam num z-index abaixo dos botões de janela da titlebar (que sobem pra
 * z 50) pra não roubar o clique de fechar/maximizar no canto superior direito.
 * Some quando a janela está maximizada (não há o que redimensionar).
 */
type ResizeDir =
  | "North"
  | "South"
  | "East"
  | "West"
  | "NorthEast"
  | "NorthWest"
  | "SouthEast"
  | "SouthWest";

const EDGE = 5; // espessura das bordas
const CORNER = 12; // tamanho dos cantos

interface Handle {
  dir: ResizeDir;
  cursor: string;
  style: React.CSSProperties;
}

const HANDLES: Handle[] = [
  // Bordas (entre os cantos)
  { dir: "North", cursor: "ns-resize", style: { top: 0, left: CORNER, right: CORNER, height: EDGE } },
  { dir: "South", cursor: "ns-resize", style: { bottom: 0, left: CORNER, right: CORNER, height: EDGE } },
  { dir: "West", cursor: "ew-resize", style: { left: 0, top: CORNER, bottom: CORNER, width: EDGE } },
  { dir: "East", cursor: "ew-resize", style: { right: 0, top: CORNER, bottom: CORNER, width: EDGE } },
  // Cantos
  { dir: "NorthWest", cursor: "nwse-resize", style: { top: 0, left: 0, width: CORNER, height: CORNER } },
  { dir: "NorthEast", cursor: "nesw-resize", style: { top: 0, right: 0, width: CORNER, height: CORNER } },
  { dir: "SouthWest", cursor: "nesw-resize", style: { bottom: 0, left: 0, width: CORNER, height: CORNER } },
  { dir: "SouthEast", cursor: "nwse-resize", style: { bottom: 0, right: 0, width: CORNER, height: CORNER } },
];

export function ResizeHandles() {
  const win = useMemo(() => getCurrentWebviewWindow(), []);

  const onDown = (dir: ResizeDir) => (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    e.preventDefault();
    win.startResizeDragging(dir).catch((err) =>
      console.warn("[resize] startResizeDragging failed:", err),
    );
  };

  return (
    <>
      {HANDLES.map((h) => (
        <div
          key={h.dir}
          onMouseDown={onDown(h.dir)}
          aria-hidden
          style={{
            position: "fixed",
            zIndex: 40,
            background: "transparent",
            cursor: h.cursor,
            ...h.style,
          }}
        />
      ))}
    </>
  );
}
