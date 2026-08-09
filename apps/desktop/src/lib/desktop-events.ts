/**
 * Tauri emit/listen channels between the windows (main / overlay / dock).
 * The VALUES are a runtime contract — don't rename without migrating every end.
 * Centralizes the strings that were hardcoded across several files. Audit #52.
 */
export const EVENTS = {
  /** Dock tells overlay/main its visibility changed (the dock's own X). */
  DOCK_VISIBILITY_CHANGED: "nora://dock-visibility-changed",
} as const;

export interface DockVisibilityPayload {
  visible: boolean;
}
