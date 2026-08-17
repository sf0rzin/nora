"use client";

/**
 * PolicyEditor
 * -------------------------------------------------------------------------
 * Specialized editor for AWS-style IAM policy documents (ADR 0007).
 *
 * Since US42 it is one of TWO ways into the same document: `policy-form-editor.tsx`
 * is the form, this is the JSON. Neither replaces the other — the form refuses to
 * open anything it cannot represent exactly, and this is where those documents
 * are written.
 *
 * Features:
 * - Monaco Editor with native JSON syntax highlighting.
 * - NORA JSON Schema registered (Effect / Action / Resource [/ Condition]).
 * - Red squiggle on schema/parse errors.
 * - Toolbar with a "Formatar" button and status indicator (Válido / N erros).
 * - Error message below the editor with the line of the first problem.
 * - Dark theme (vs-dark) synced with the user's prefers-color-scheme.
 * - Graceful fallback: falls back to <textarea> if Monaco fails to load
 *   (network, weird SSR, etc.). Without breaking the page.
 *
 * Contract:
 *   <PolicyEditor
 *      value={policyDoc}
 *      onChange={(value, isValid) => setPolicyDoc(value)}
 *      readOnly={false}
 *   />
 *
 * - `value`: string with the raw JSON (we keep a string, not an object, to
 *   preserve formatting and allow invalid intermediate JSON).
 * - `onChange(value, isValid)`: the caller decides whether to disable submit
 *   by looking at `isValid` (combination of parse-ok + schema-ok).
 * - `readOnly`: blocks editing.
 */

import dynamic from "next/dynamic";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import type { editor } from "monaco-editor";
import type { OnMount, OnChange, Monaco } from "@monaco-editor/react";

/**
 * JSON Schema (Draft-07) describing the NORA policy document.
 *
 * Reflects ADR 0007: Effect/Action/Resource[/Condition]. The patterns do not
 * aim for exhaustiveness — they are a first guard against "obviously wrong"
 * (e.g. missing `effect`, writing `Action` instead of `action`). The backend
 * remains the source of truth.
 */
const POLICY_SCHEMA = {
  $schema: "http://json-schema.org/draft-07/schema#",
  title: "NoraPolicyDocument",
  type: "object",
  required: ["version", "statements"],
  additionalProperties: false,
  properties: {
    version: {
      type: "string",
      pattern: "^\\d{4}-\\d{2}-\\d{2}$",
      description: "Versao da policy no formato YYYY-MM-DD.",
    },
    statements: {
      type: "array",
      minItems: 1,
      description: "Lista de statements Effect/Action/Resource.",
      items: {
        type: "object",
        required: ["effect", "action", "resource"],
        additionalProperties: false,
        properties: {
          effect: {
            type: "string",
            enum: ["Allow", "Deny"],
            description: "Allow ou Deny. Deny vence em qualquer conflito.",
          },
          action: {
            type: "array",
            minItems: 1,
            description: "Lista de actions no padrao service:operation[:sub]. Wildcards permitidos.",
            items: {
              type: "string",
              pattern: "^[a-z]+:[a-z]+(:[a-z*]+)?$|^\\*$",
            },
          },
          resource: {
            type: "array",
            minItems: 1,
            description: "Lista de resources ARN-like (ex: nora:tenant/*:meeting/*).",
            items: { type: "string", minLength: 1 },
          },
          condition: {
            type: "object",
            description:
              "Conditions estilo AWS. Opcional. Apenas os cinco operadores que o avaliador " +
              "implementa: outro qualquer nao e avaliado e a policy nega.",
            // The five in `SUPPORTED_CONDITION_OPERATORS` (PolicyEvaluator.java). Restricted here
            // for the same reason the form offers no other (US42): an unsupported operator makes
            // the statement not match, so an Allow carrying one silently grants nothing.
            propertyNames: {
              enum: [
                "StringEquals",
                "StringIn",
                "StringLike",
                "DateGreaterThan",
                "DateLessThan",
              ],
            },
          },
        },
      },
    },
  },
} as const;

/**
 * Monaco runs its language services in web workers — the JSON schema validation
 * that draws the squiggles below is one of them. Serving Monaco from the
 * installed package (see `MonacoEditor`) means the workers stop coming from a
 * third party too, so the bundler emits them and Monaco has to be told where
 * they are. This page only ever asks for the `json` worker; any other label
 * falls back to the generic editor worker.
 */
interface MonacoWorkerEnvironment {
  getWorker: (workerId: string, label: string) => Worker;
}

function configureMonacoWorkers(): void {
  const scope = globalThis as unknown as { MonacoEnvironment?: MonacoWorkerEnvironment };
  scope.MonacoEnvironment = {
    getWorker: (_workerId, label) =>
      label === "json"
        ? new Worker(
            new URL("monaco-editor/esm/vs/language/json/json.worker.js", import.meta.url),
            { type: "module", name: label },
          )
        : new Worker(new URL("monaco-editor/esm/vs/editor/editor.worker.js", import.meta.url), {
            type: "module",
            name: label,
          }),
  };
}

/**
 * Dynamic loading of Monaco. SSR off because Monaco needs `window` and
 * `document`. Loading state shows a lean placeholder.
 *
 * Monaco comes from the `monaco-editor` package installed in this app. Left
 * unconfigured, `@monaco-editor/loader` fetches it from a public CDN instead,
 * which means the code running in this authenticated IAM page is whatever that
 * host serves at that moment — not what package-lock.json pins and not what the
 * `dompurify` override in package.json applies to. Pointing the loader at the
 * local package makes the reviewed, pinned bytes the ones that execute, and
 * drops the runtime dependency on a third-party host being reachable.
 *
 * Order matters: `loader.config` has to run before the first <Editor> mounts,
 * because the loader picks its source on the first `init()`. Doing it inside
 * this factory guarantees that. The `monaco-editor` import has to stay in here
 * as well — at module scope it would be evaluated while this client component
 * is rendered on the server, where there is no `window`.
 */
const MonacoEditor = dynamic(
  async () => {
    const [mod, monaco] = await Promise.all([
      import("@monaco-editor/react"),
      import("monaco-editor"),
    ]);
    configureMonacoWorkers();
    mod.loader.config({ monaco });
    return mod.Editor;
  },
  {
    ssr: false,
    loading: () => (
      <div
        className="flex h-[400px] w-full items-center justify-center rounded-md border border-slate-200 bg-slate-50 text-xs text-slate-500"
        role="status"
        aria-live="polite"
      >
        Carregando editor…
      </div>
    ),
  },
);

export interface PolicyEditorProps {
  /** Raw JSON string. Kept as a string to preserve the user's formatting. */
  value: string;
  /**
   * Callback called on every change. `isValid` reflects JSON parse OK + schema
   * with no errors — the caller should use this flag to enable/disable Save.
   */
  onChange: (value: string, isValid: boolean) => void;
  /** Blocks editing. Default false. */
  readOnly?: boolean;
  /** Height in px. Default 400. */
  height?: number;
}

interface MarkerSummary {
  line: number;
  column: number;
  message: string;
  severity: number;
}

/**
 * Tries to detect a dark theme via media query. Used to choose between
 * `vs` (light) and `vs-dark`. The color tokens from globals.css `.dark` are
 * still handled by Tailwind in the rest of the UI; Monaco has its own theme.
 */
function useIsDarkTheme(): boolean {
  const [isDark, setIsDark] = useState(false);
  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const update = () => setIsDark(mq.matches);
    update();
    mq.addEventListener("change", update);
    return () => mq.removeEventListener("change", update);
  }, []);
  return isDark;
}

export default function PolicyEditor({
  value,
  onChange,
  readOnly = false,
  height = 400,
}: PolicyEditorProps) {
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const monacoRef = useRef<Monaco | null>(null);
  const errorRegionId = useId();
  const [markers, setMarkers] = useState<MarkerSummary[]>([]);
  const [parseError, setParseError] = useState<string | null>(null);
  const [monacoFailed, setMonacoFailed] = useState(false);
  const isDark = useIsDarkTheme();

  /**
   * Validates the JSON syntactically. Schema validation proper runs inside
   * Monaco and arrives via onValidate -> markers. Here we only capture
   * "parsed or not" for cases where Monaco has not mounted yet.
   */
  const parseValid = useMemo(() => {
    if (!value || !value.trim()) return false;
    try {
      JSON.parse(value);
      return true;
    } catch {
      return false;
    }
  }, [value]);

  const schemaValid = markers.filter((m) => m.severity >= 8).length === 0;
  const isValid = parseValid && schemaValid;

  /**
   * We keep a ref to the onChange callback to avoid registering Monaco
   * handlers on every render. The caller expects to be notified on each
   * keystroke with the current isValid.
   */
  const onChangeRef = useRef(onChange);
  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  // Re-notifies the parent whenever validity changes (without user typing,
  // e.g. schema redefined or theme). Ensures Save reflects the real state.
  useEffect(() => {
    onChangeRef.current(value, isValid);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isValid]);

  const handleMount: OnMount = useCallback((mountedEditor, monaco) => {
    editorRef.current = mountedEditor;
    monacoRef.current = monaco;

    // Registers the NORA schema for inline validation with squiggle.
    try {
      monaco.languages.json.jsonDefaults.setDiagnosticsOptions({
        validate: true,
        allowComments: false,
        schemas: [
          {
            uri: "https://nora.local/schemas/policy.json",
            fileMatch: ["*"],
            schema: POLICY_SCHEMA,
          },
        ],
      });
    } catch {
      // If the schema fails to register (unlikely), the editor still works
      // as plain JSON. Do not break the page.
    }
  }, []);

  const handleChange: OnChange = useCallback((next) => {
    const safe = next ?? "";
    // Re-parses for fast feedback — the markers state covers the schema.
    let nextIsValid = false;
    try {
      if (safe.trim()) {
        JSON.parse(safe);
        nextIsValid = true;
      }
      setParseError(null);
    } catch (err) {
      nextIsValid = false;
      setParseError(err instanceof Error ? err.message : "JSON inválido");
    }
    // Schema markers will be updated by onValidate; here we combine with the
    // current state of the markers ref to give immediate feedback.
    onChangeRef.current(safe, nextIsValid && monacoRef.current
      ? markersOk(monacoRef.current, editorRef.current)
      : nextIsValid);
  }, []);

  /**
   * Monaco's `onValidate` delivers the full array of markers (parse + schema).
   * We convert it into a lightweight shape to render the error list.
   */
  const handleValidate = useCallback((monacoMarkers: editor.IMarker[]) => {
    const summarized: MarkerSummary[] = monacoMarkers.map((m) => ({
      line: m.startLineNumber,
      column: m.startColumn,
      message: m.message,
      severity: m.severity,
    }));
    setMarkers(summarized);
  }, []);

  /**
   * "Formatar" button: calls Monaco's native action. Indents with 2 spaces
   * (JSON language service default).
   */
  const handleFormat = useCallback(() => {
    const ed = editorRef.current;
    if (!ed) return;
    ed.getAction("editor.action.formatDocument")?.run();
  }, []);

  /**
   * Captures errors from Monaco's dynamic import / loader and turns on the
   * fallback. In production it will rarely fire — it is here so the user is
   * not left with a blank page on a bundling or worker problem.
   *
   * Coverage:
   * 1. `window.error` listener for synchronous loader failures (script error).
   * 2. `window.unhandledrejection` listener for rejected promises (e.g.
   *    Monaco's dynamic import fails).
   * 3. Watchdog: if `onMount` is not called within 12s (slow device, or a chunk
   *    that never arrives), assume failure and turn on the fallback.
   */
  useEffect(() => {
    if (typeof window === "undefined") return;

    const isMonacoRelated = (msg: string) => {
      const s = msg.toLowerCase();
      return s.includes("monaco") || s.includes("@monaco-editor");
    };

    const errorHandler = (e: ErrorEvent) => {
      if (isMonacoRelated(e?.message ?? "") || isMonacoRelated(e?.filename ?? "")) {
        setMonacoFailed(true);
      }
    };
    const rejectionHandler = (e: PromiseRejectionEvent) => {
      const reason = e?.reason;
      const msg = typeof reason === "string" ? reason : reason?.message ?? "";
      const stack = reason?.stack ?? "";
      if (isMonacoRelated(msg) || isMonacoRelated(stack)) {
        setMonacoFailed(true);
      }
    };

    window.addEventListener("error", errorHandler);
    window.addEventListener("unhandledrejection", rejectionHandler);

    const watchdog = window.setTimeout(() => {
      if (!editorRef.current) {
        setMonacoFailed(true);
      }
    }, 12_000);

    return () => {
      window.removeEventListener("error", errorHandler);
      window.removeEventListener("unhandledrejection", rejectionHandler);
      window.clearTimeout(watchdog);
    };
  }, []);

  // ------- Fallback: raw textarea if Monaco failed -------
  if (monacoFailed) {
    return (
      <div className="space-y-2">
        <div className="flex items-center justify-between rounded-t-md border border-b-0 border-amber-300 bg-amber-50 px-2 py-1 text-xs text-amber-800">
          <span>
            Editor avançado indisponível — usando textarea simples. Salve mesmo assim, se necessario.
          </span>
        </div>
        <textarea
          value={value}
          onChange={(e) => onChangeRef.current(e.target.value, isFallbackValid(e.target.value))}
          rows={Math.max(12, Math.floor(height / 24))}
          readOnly={readOnly}
          spellCheck={false}
          className="w-full rounded-md border border-slate-300 px-3 py-2 font-mono text-xs"
          aria-label="Documento de policy JSON"
        />
        {parseError && (
          <p className="text-xs text-red-600" role="alert">
            {parseError}
          </p>
        )}
      </div>
    );
  }

  const firstError = markers.find((m) => m.severity >= 8) ?? null;
  const errorCount = markers.filter((m) => m.severity >= 8).length;

  return (
    <div className="space-y-2">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-t-md border border-b-0 border-slate-300 bg-slate-50 px-2 py-1.5 text-xs">
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleFormat}
            disabled={readOnly || !parseValid}
            className="rounded-md border border-slate-300 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-50"
          >
            Formatar
          </button>
          <span className="text-slate-400">JSON · UTF-8</span>
        </div>
        <div
          className={
            isValid
              ? "rounded-md bg-emerald-50 px-2 py-0.5 font-medium text-emerald-700"
              : "rounded-md bg-red-50 px-2 py-0.5 font-medium text-red-700"
          }
          aria-live="polite"
        >
          {isValid ? "✓ Válido" : `✗ ${errorCount || (parseValid ? 0 : 1)} erro(s)`}
        </div>
      </div>

      <div className="overflow-hidden rounded-b-md border border-slate-300">
        <MonacoEditor
          height={`${height}px`}
          defaultLanguage="json"
          language="json"
          value={value}
          theme={isDark ? "vs-dark" : "vs"}
          onMount={handleMount}
          onChange={handleChange}
          onValidate={handleValidate}
          options={{
            readOnly,
            minimap: { enabled: false },
            fontSize: 13,
            lineNumbers: "on",
            scrollBeyondLastLine: false,
            automaticLayout: true,
            tabSize: 2,
            formatOnPaste: true,
            wordWrap: "on",
            renderLineHighlight: "line",
            // Schema validation is handled via setDiagnosticsOptions in handleMount.
          }}
        />
      </div>

      {/* Error message below */}
      {parseError && (
        <p className="text-xs text-red-600" role="alert" id={errorRegionId}>
          JSON inválido: {parseError}
        </p>
      )}
      {!parseError && firstError && (
        <p className="text-xs text-red-600" role="alert" id={errorRegionId}>
          Linha {firstError.line}, coluna {firstError.column}: {firstError.message}
          {errorCount > 1 && ` (e mais ${errorCount - 1})`}
        </p>
      )}
    </div>
  );
}

// ---------------- Helpers ----------------

function isFallbackValid(value: string): boolean {
  if (!value.trim()) return false;
  try {
    JSON.parse(value);
    return true;
  } catch {
    return false;
  }
}

/**
 * Queries the model's current markers to decide combined validity.
 * Used inside handleChange because the React state `markers` can lag by
 * 1 tick — monaco itself has the most recent source.
 */
function markersOk(
  monaco: Monaco,
  ed: editor.IStandaloneCodeEditor | null,
): boolean {
  if (!ed) return true; // no editor ready yet — trust the parse
  const model = ed.getModel();
  if (!model) return true;
  const all = monaco.editor.getModelMarkers({ resource: model.uri });
  return all.filter((m) => m.severity >= 8).length === 0;
}
