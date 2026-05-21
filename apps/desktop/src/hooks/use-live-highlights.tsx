import { createContext, useContext, useState, useCallback, useEffect, useRef, type ReactNode } from "react";
import { emit, listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";

export interface LiveHighlightItem {
  text: string;
  confidence: number;
  sourceQuote: string;
}

export interface LiveTaskItem {
  title: string;
  assignee: string | null;
  priority: string;
  sourceQuote: string;
}

export interface LiveHighlights {
  decisions: LiveHighlightItem[];
  nextSteps: LiveHighlightItem[];
  observations: LiveHighlightItem[];
  tasks: LiveTaskItem[];
}

export interface LiveAnalysisTelemetry {
  chunkSeq: number;
  latencyMs: number;
  success: boolean;
  itemsCount: number;
}

interface LiveHighlightsContextType {
  highlights: LiveHighlights;
  isAnalyzing: boolean;
  lastUpdatedAt: number | null;
  lastLatencyMs: number | null;
  error: string | null;
  chunkSeq: number;
  clearHighlights: () => void;
}

const emptyHighlights: LiveHighlights = {
  decisions: [],
  nextSteps: [],
  observations: [],
  tasks: [],
};

const LiveHighlightsContext = createContext<LiveHighlightsContextType | null>(null);

export function LiveHighlightsProvider({ children }: { children: ReactNode }) {
  const [highlights, setHighlights] = useState<LiveHighlights>(emptyHighlights);
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null);
  const [lastLatencyMs, setLastLatencyMs] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [chunkSeq, setChunkSeq] = useState(0);

  const clearHighlights = useCallback(() => {
    setHighlights(emptyHighlights);
    setIsAnalyzing(false);
    setLastUpdatedAt(null);
    setLastLatencyMs(null);
    setError(null);
    setChunkSeq(0);
  }, []);

  useEffect(() => {
    // Cleanup defensivo: se o componente desmonta antes da promise `listen()` resolver,
    // guardamos o "alreadyUnmounted" flag para impedir o `fn()` em cleanup já tardio.
    let mounted = true;
    const handles: Array<Promise<() => void>> = [];

    const subscribe = <T,>(event: string, cb: (e: { payload: T }) => void) => {
      const h = listen<T>(event, cb);
      handles.push(h as Promise<() => void>);
    };

    subscribe<LiveHighlights>("live-analysis", (event) => {
      const { highlights: newHighlights, chunkSeq: seq } = event.payload as unknown as {
        highlights: LiveHighlights;
        chunkSeq: number;
      };
      if (!mounted) return;
      setHighlights((prev) => mergeHighlights(prev, newHighlights));
      setLastUpdatedAt(Date.now());
      setChunkSeq(seq);
      setIsAnalyzing(false);
      setError(null);
    });

    subscribe<LiveAnalysisTelemetry>("live-analysis-telemetry", (event) => {
      if (!mounted) return;
      setLastLatencyMs(event.payload.latencyMs);
    });

    subscribe("clear-highlights", () => {
      if (mounted) clearHighlights();
    });

    // Eventos Tauri (cross-window) emitidos pelo `triggerAnalysis` para acender
    // o spinner também na janela overlay — DOM CustomEvent não atravessa janelas.
    subscribe("live-analysis-start", () => {
      if (mounted) setIsAnalyzing(true);
    });
    subscribe("live-analysis-end", () => {
      if (mounted) setIsAnalyzing(false);
    });

    return () => {
      mounted = false;
      handles.forEach((h) => h.then((fn) => fn()).catch(() => {}));
    };
  }, [clearHighlights]);

  return (
    <LiveHighlightsContext.Provider
      value={{ highlights, isAnalyzing, lastUpdatedAt, lastLatencyMs, error, chunkSeq, clearHighlights }}
    >
      {children}
    </LiveHighlightsContext.Provider>
  );
}

export function useLiveHighlights() {
  const context = useContext(LiveHighlightsContext);
  if (!context) {
    throw new Error("useLiveHighlights must be used within LiveHighlightsProvider");
  }
  return context;
}

export function useLiveAnalysisTrigger() {
  const isAnalyzingRef = useRef(false);
  const lastAnalyzedCountRef = useRef(0);
  const lastAnalyzedTimeRef = useRef(Date.now());
  const chunkSeqRef = useRef(0);

  // setIsAnalyzing precisa ser exposto pelo Provider (não é). Em vez de prop-drill,
  // disparamos um custom event que o Provider escuta. Antes `setIsAnalyzing(true)`
  // nunca era chamado em lugar nenhum → spinner do overlay nunca aparecia.
  const triggerAnalysis = useCallback(
    async (transcriptLines: { text: string }[], currentHighlights: LiveHighlights, force = false) => {
      if (isAnalyzingRef.current) return;
      if (transcriptLines.length === 0) return;

      const newLines = transcriptLines.length - lastAnalyzedCountRef.current;
      const timeSinceLastAnalysis = Date.now() - lastAnalyzedTimeRef.current;
      const newChars = transcriptLines
        .slice(lastAnalyzedCountRef.current)
        .reduce((acc, l) => acc + l.text.length, 0);

      if (!force && newLines < 3 && newChars < 100 && timeSinceLastAnalysis < 15000) {
        return;
      }

      const snapshotLen = transcriptLines.length;
      const transcriptChunk = transcriptLines
        .slice(lastAnalyzedCountRef.current)
        .map((l) => l.text)
        .join("\n");
      if (transcriptChunk.length < 30) {
        return;
      }

      isAnalyzingRef.current = true;
      // Comunica ao Provider: spinner do overlay liga.
      // Usamos Tauri `emit` (não window.dispatchEvent) para que o evento atravesse
      // janelas — DOM CustomEvent só dispara na window que chamou dispatchEvent,
      // então a janela overlay (separada da main) jamais recebia.
      void emit("live-analysis-start");
      const nextSeq = chunkSeqRef.current + 1;
      chunkSeqRef.current = nextSeq;
      lastAnalyzedTimeRef.current = Date.now();

      let ok = false;
      try {
        await invoke("analyze_live", {
          request: {
            transcriptChunk,
            chunkSeq: nextSeq,
            previousHighlights: hasAnyItem(currentHighlights) ? currentHighlights : null,
            language: "pt-BR",
          },
        });
        ok = true;
      } catch (e) {
        console.error("[live-analysis] trigger failed:", e);
      } finally {
        isAnalyzingRef.current = false;
        if (ok) {
          // Só avança o cursor em caso de sucesso — falha transitória NÃO
          // faz o sistema "pular" os trechos não analisados.
          lastAnalyzedCountRef.current = snapshotLen;
        } else {
          void emit("live-analysis-end");
        }
      }
    },
    [],
  );

  const resetTrigger = useCallback(() => {
    lastAnalyzedCountRef.current = 0;
    lastAnalyzedTimeRef.current = Date.now();
    chunkSeqRef.current = 0;
    isAnalyzingRef.current = false;
  }, []);

  return { triggerAnalysis, resetTrigger };
}

function hasAnyItem(h: LiveHighlights): boolean {
  return h.decisions.length > 0 || h.nextSteps.length > 0 || h.observations.length > 0 || h.tasks.length > 0;
}

function normalizeText(text: string): string {
  return text
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9\s]/g, "")
    .trim();
}

function isDuplicate(newText: string, existing: LiveHighlightItem[]): boolean {
  const normNew = normalizeText(newText);
  const newTokens = new Set(normNew.split(/\s+/).filter(Boolean));
  if (newTokens.size === 0) return true;

  for (const item of existing) {
    const normOld = normalizeText(item.text);
    if (normNew === normOld) return true;
    if (normNew.includes(normOld) || normOld.includes(normNew)) return true;
    const oldTokens = new Set(normOld.split(/\s+/).filter(Boolean));
    if (oldTokens.size === 0) continue;
    const overlap = newTokens.size <= oldTokens.size
      ? [...newTokens].filter((t) => oldTokens.has(t)).length / newTokens.size
      : [...oldTokens].filter((t) => newTokens.has(t)).length / oldTokens.size;
    if (overlap >= 0.7) return true;
  }
  return false;
}

function mergeItems(existing: LiveHighlightItem[], incoming: LiveHighlightItem[]): LiveHighlightItem[] {
  const result = [...existing];
  for (const item of incoming) {
    if (!isDuplicate(item.text, result)) {
      result.push(item);
    }
  }
  return result;
}

function mergeTasks(existing: LiveTaskItem[], incoming: LiveTaskItem[]): LiveTaskItem[] {
  const result = [...existing];
  for (const task of incoming) {
    const normNew = normalizeText(task.title);
    const isDup = result.some((t) => {
      const normOld = normalizeText(t.title);
      if (normNew === normOld) return true;
      const newTokens = new Set(normNew.split(/\s+/).filter(Boolean));
      const oldTokens = new Set(normOld.split(/\s+/).filter(Boolean));
      if (newTokens.size === 0 || oldTokens.size === 0) return false;
      const overlap = [...newTokens].filter((tok) => oldTokens.has(tok)).length / Math.min(newTokens.size, oldTokens.size);
      return overlap >= 0.7;
    });
    if (!isDup) result.push(task);
  }
  return result;
}

function mergeHighlights(prev: LiveHighlights, incoming: LiveHighlights): LiveHighlights {
  return {
    decisions: mergeItems(prev.decisions, incoming.decisions),
    nextSteps: mergeItems(prev.nextSteps, incoming.nextSteps),
    observations: mergeItems(prev.observations, incoming.observations),
    tasks: mergeTasks(prev.tasks, incoming.tasks),
  };
}
