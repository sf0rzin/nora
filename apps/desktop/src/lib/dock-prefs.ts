/**
 * Dock visibility preference — key + codec shared between the overlay and the
 * dock-bar (it was duplicated in each of them with the same key
 * "nora.dock.visible"). Desktop audit #36/#39/#52.
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
