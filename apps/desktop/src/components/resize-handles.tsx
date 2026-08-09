import { useMemo } from "react";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";

/**
 * Resize handles for the window without native decoration.
 *
 * Without the OS border there's no way to drag the edges to resize, so we
 * draw 8 invisible areas (4 edges + 4 corners) that call
 * `startResizeDragging(direction)`. This is cross-platform (Windows/macOS/Linux —
 * uses the same WM move/resize mechanism as the titlebar's startDragging).
 *
 * They sit at a z-index below the titlebar's window buttons (which go up to
 * z 50) so they don't steal the close/maximize click in the top right corner.
 * They disappear when the window is maximized (nothing to resize).
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

const EDGE = 5; // edge thickness
const CORNER = 12; // corner size

interface Handle {
  dir: ResizeDir;
  cursor: string;
  style: React.CSSProperties;
}

const HANDLES: Handle[] = [
  // Edges (between the corners)
  { dir: "North", cursor: "ns-resize", style: { top: 0, left: CORNER, right: CORNER, height: EDGE } },
  { dir: "South", cursor: "ns-resize", style: { bottom: 0, left: CORNER, right: CORNER, height: EDGE } },
  { dir: "West", cursor: "ew-resize", style: { left: 0, top: CORNER, bottom: CORNER, width: EDGE } },
  { dir: "East", cursor: "ew-resize", style: { right: 0, top: CORNER, bottom: CORNER, width: EDGE } },
  // Corners
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
