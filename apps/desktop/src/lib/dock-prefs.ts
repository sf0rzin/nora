/**
 * Dock visibility preference — key + codec shared between
 * overlay, dock-bar and use-active-recording (it was duplicated in all 3 with the same
 * key "nora.dock.visible"). Desktop audit #36/#39/#52.
 */
const DOCK_STORAGE_KEY = "nora.dock.visible";

/** Default: dock visible. Persisted as "1"/"0", with a silent try/catch. */
export function getDockVisible(): boolean {
  try {
    const v = localStorage.getItem(DOCK_STORAGE_KEY);
    return v == null ? true : v === "1";
  } catch {
    return true;
  }
}

export function setDockVisible(visible: boolean): void {
  try {
    localStorage.setItem(DOCK_STORAGE_KEY, visible ? "1" : "0");
  } catch {
    // localStorage may be unavailable; ignore
  }
}
