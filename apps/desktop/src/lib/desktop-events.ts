/**
 * Canais Tauri emit/listen entre as janelas (main / overlay / dock).
 * Os VALORES são contrato de runtime — não renomear sem migrar todas as pontas.
 * Centraliza as strings que estavam hardcoded em vários arquivos. Auditoria #52.
 */
export const EVENTS = {
  /** Dock avisa overlay/main que sua visibilidade mudou (X do próprio dock). */
  DOCK_VISIBILITY_CHANGED: "nora://dock-visibility-changed",
} as const;

export interface DockVisibilityPayload {
  visible: boolean;
}
