/**
 * Preferência de visibilidade do dock — chave + codec compartilhados entre
 * overlay, dock-bar e use-active-recording (estava duplicada nos 3 com a mesma
 * chave "nora.dock.visible"). Auditoria desktop #36/#39/#52.
 */
const DOCK_STORAGE_KEY = "nora.dock.visible";

/** Default: dock visível. Persistido como "1"/"0", com try/catch silencioso. */
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
    // localStorage pode estar indisponível; ignore
  }
}
