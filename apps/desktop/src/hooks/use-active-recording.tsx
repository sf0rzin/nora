import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { emit } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";
import { useRecording } from "./use-recording";
import { useTauriListener } from "./use-tauri-listener";
import { getDockVisible } from "@/lib/dock-prefs";

const META_STORAGE_KEY = "nora.active-recording.meta";

interface ActiveMeetingMeta {
  title: string;
  description: string;
  startedAt: string;
}

interface StartOptions {
  title: string;
  description?: string;
  deviceName?: string | null;
  captureSystemAudio?: boolean;
  systemAudioDevice?: string | null;
}

interface ActiveRecordingState {
  isRecording: boolean;
  duration: number;
  transcriptLines: ReturnType<typeof useRecording>["transcriptLines"];
  partialText: string;
  deviceName: string;
  sampleRate: number;
  speakerMap: Record<string, string>;
  getSpeakerName: ReturnType<typeof useRecording>["getSpeakerName"];
  renameSpeaker: ReturnType<typeof useRecording>["renameSpeaker"];
  devices: string[];
  selectedDevice: string | null;
  setSelectedDevice: (d: string | null) => void;
  error: string | null;
  isSaving: boolean;
  saveError: string | null;
  savedMeetingId: string | null;
  meta: ActiveMeetingMeta | null;
  /** True when user has clicked stop and the save flow is running. */
  isFinishing: boolean;
  start: (opts: StartOptions) => Promise<void>;
  stopAndSave: () => Promise<void>;
  cancel: () => Promise<void>;
}

const ActiveRecordingContext = createContext<ActiveRecordingState | null>(null);

export function ActiveRecordingProvider({ children }: { children: ReactNode }) {
  const recording = useRecording({ language: "pt-BR" });

  const [meta, setMeta] = useState<ActiveMeetingMeta | null>(() => {
    try {
      const raw = localStorage.getItem(META_STORAGE_KEY);
      return raw ? (JSON.parse(raw) as ActiveMeetingMeta) : null;
    } catch {
      return null;
    }
  });
  const [isFinishing, setIsFinishing] = useState(false);
  const metaRef = useRef(meta);
  metaRef.current = meta;

  const persistMeta = useCallback((m: ActiveMeetingMeta | null) => {
    setMeta(m);
    try {
      if (m) localStorage.setItem(META_STORAGE_KEY, JSON.stringify(m));
      else localStorage.removeItem(META_STORAGE_KEY);
    } catch {
      // localStorage may be unavailable; ignore
    }
  }, []);

  const start = useCallback(
    async (opts: StartOptions) => {
      const newMeta: ActiveMeetingMeta = {
        title: opts.title.trim() || "Reunião sem título",
        description: opts.description?.trim() || "",
        startedAt: new Date().toISOString(),
      };
      persistMeta(newMeta);
      await recording.startRecording({
        deviceName: opts.deviceName ?? null,
        captureSystemAudio: opts.captureSystemAudio ?? false,
        systemAudioDevice: opts.systemAudioDevice ?? null,
      });
      // Open the dock window too, if user prefers it visible.
      if (getDockVisible()) {
        invoke("toggle_dock", { show: true }).catch(() => {});
      }
    },
    [persistMeta, recording],
  );

  const stopAndSave = useCallback(async () => {
    if (isFinishing) return;
    setIsFinishing(true);
    try {
      await recording.stopRecording();
      const m = metaRef.current;
      // Give the transcript context a tick to settle final partials
      await new Promise((r) => setTimeout(r, 120));
      if (!m) {
        await emit("nora://save-result", {
          ok: false,
          error: "Sem metadados da reunião — abra uma nova pelo modal.",
        }).catch(() => {});
        return;
      }
      const result = await recording.saveMeeting(m.title);
      if (result.ok) {
        await emit("nora://save-result", {
          ok: true,
          meetingId: result.meetingId,
        }).catch(() => {});
        // Success: close the overlay so user is back in the main window
        // with the meeting detail already navigated to.
        invoke("toggle_overlay", { show: false }).catch(() => {});
      } else {
        await emit("nora://save-result", {
          ok: false,
          error: result.error,
        }).catch(() => {});
        // Save failed: keep overlay open so user can retry or discard.
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      await emit("nora://save-result", { ok: false, error: msg }).catch(() => {});
    } finally {
      // Reset finishing in ALL paths so the overlay button never gets stuck.
      setIsFinishing(false);
      invoke("toggle_dock", { show: false }).catch(() => {});
    }
  }, [isFinishing, recording]);

  const cancel = useCallback(async () => {
    setIsFinishing(false);
    await recording.stopRecording().catch(() => {});
    persistMeta(null);
    invoke("toggle_dock", { show: false }).catch(() => {});
    invoke("toggle_overlay", { show: false }).catch(() => {});
  }, [persistMeta, recording]);

  // When a save succeeds (incluindo retries que rodam fora do stopAndSave —
  // ex.: retry-worker, save manual), garante navegação + cleanup. O
  // stopAndSave path principal já faz tudo isso explicitamente, mas como
  // saveMeeting pode ser invocado por outros caminhos (worker, retry-save
  // listener), este effect cobre os outros casos. As ops são idempotentes:
  // persistMeta(null) sobre null é no-op; setIsFinishing(false) sobre false
  // idem; navegação é a mesma URL.
  //
  // Window detection via getCurrentWebviewWindow().label em vez de
  // window.location.pathname — pathname pode variar entre dev/prod ou se a
  // entrada da janela main muda (ex: /index.html vs /).
  useEffect(() => {
    if (!recording.savedMeetingId) return;
    const win = getCurrentWebviewWindow();
    if (win.label === "main") {
      window.location.hash = `#/meetings/${recording.savedMeetingId}`;
    }
    persistMeta(null);
    setIsFinishing(false);
  }, [recording.savedMeetingId, persistMeta]);

  // Listen for stop signal from overlay
  useTauriListener("nora://stop-and-save", () => stopAndSave(), [stopAndSave]);

  // Listen for cancel signal from overlay
  useTauriListener("nora://cancel-recording", () => cancel(), [cancel]);

  // Listen for "retry save" — usuário clicou em Tentar de novo após falha
  useTauriListener(
    "nora://retry-save",
    async () => {
      const m = metaRef.current;
      if (!m) {
        await emit("nora://save-result", {
          ok: false,
          error: "Sem metadados da reunião.",
        }).catch(() => {});
        return;
      }
      setIsFinishing(true);
      try {
        const result = await recording.saveMeeting(m.title);
        if (result.ok) {
          await emit("nora://save-result", {
            ok: true,
            meetingId: result.meetingId,
          }).catch(() => {});
          invoke("toggle_overlay", { show: false }).catch(() => {});
        } else {
          await emit("nora://save-result", {
            ok: false,
            error: result.error,
          }).catch(() => {});
        }
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        await emit("nora://save-result", { ok: false, error: msg }).catch(() => {});
      } finally {
        setIsFinishing(false);
      }
    },
    [recording],
  );

  // Listen for speaker rename from overlay drawer
  useTauriListener<{ speakerId: string; name: string }>(
    "nora://rename-speaker",
    (e) => {
      if (!e.payload?.speakerId) return;
      recording.renameSpeaker(e.payload.speakerId, e.payload.name || "");
    },
    [recording],
  );

  // Listen for "restart with new audio devices" from overlay drawer
  useTauriListener<{
    deviceName: string | null;
    captureSystemAudio: boolean;
    systemAudioDevice: string | null;
  }>(
    "nora://restart-recording",
    async (e) => {
      const opts = e.payload;
      if (!opts) return;
      try {
        await invoke("stop_recording").catch(() => {});
        await new Promise((r) => setTimeout(r, 120));
        await recording.startRecording({
          deviceName: opts.deviceName,
          captureSystemAudio: opts.captureSystemAudio,
          systemAudioDevice: opts.systemAudioDevice,
        });
      } catch (err) {
        console.error("[active-recording] restart failed:", err);
      }
    },
    [recording],
  );

  const value: ActiveRecordingState = useMemo(
    () => ({
      isRecording: recording.isRecording,
      duration: recording.duration,
      transcriptLines: recording.transcriptLines,
      partialText: recording.partialText,
      deviceName: recording.deviceName,
      sampleRate: recording.sampleRate,
      speakerMap: recording.speakerMap,
      getSpeakerName: recording.getSpeakerName,
      renameSpeaker: recording.renameSpeaker,
      devices: recording.devices,
      selectedDevice: recording.selectedDevice,
      setSelectedDevice: recording.setSelectedDevice,
      error: recording.error,
      isSaving: recording.isSaving,
      saveError: recording.saveError,
      savedMeetingId: recording.savedMeetingId,
      meta,
      isFinishing,
      start,
      stopAndSave,
      cancel,
    }),
    [recording, meta, isFinishing, start, stopAndSave, cancel],
  );

  return (
    <ActiveRecordingContext.Provider value={value}>
      {children}
    </ActiveRecordingContext.Provider>
  );
}

export function useActiveRecording() {
  const ctx = useContext(ActiveRecordingContext);
  if (!ctx) {
    throw new Error(
      "useActiveRecording must be used inside <ActiveRecordingProvider>",
    );
  }
  return ctx;
}
