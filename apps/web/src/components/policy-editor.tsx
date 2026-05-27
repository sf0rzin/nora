"use client";

/**
 * PolicyEditor
 * -------------------------------------------------------------------------
 * Editor especializado para documentos de policy IAM estilo AWS (ADR 0007).
 *
 * Recursos:
 * - Monaco Editor com syntax highlight JSON nativo.
 * - JSON Schema NORA registrado (Effect / Action / Resource [/ Condition]).
 * - Squiggle vermelho em erros de schema/parse.
 * - Toolbar com botao "Formatar" e indicador de status (Válido / N erros).
 * - Mensagem de erro abaixo do editor com linha do primeiro problema.
 * - Tema dark (vs-dark) sincronizado com prefers-color-scheme do usuário.
 * - Fallback gracioso: cai para <textarea> se Monaco falhar de carregar
 *   (network, SSR esquisito, etc.). Sem quebrar a pagina.
 *
 * Contrato:
 *   <PolicyEditor
 *      value={policyDoc}
 *      onChange={(value, isValid) => setPolicyDoc(value)}
 *      readOnly={false}
 *   />
 *
 * - `value`: string com o JSON cru (mantemos string, nao objeto, para
 *   preservar formatacao e permitir JSON intermediário invalido).
 * - `onChange(value, isValid)`: o caller decide se desabilita o submit
 *   olhando `isValid` (combinacao de parse-ok + schema-ok).
 * - `readOnly`: bloqueia edicao.
 */

import dynamic from "next/dynamic";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import type { editor } from "monaco-editor";
import type { OnMount, OnChange, Monaco } from "@monaco-editor/react";

/**
 * JSON Schema (Draft-07) que descreve o documento de policy NORA.
 *
 * Reflete ADR 0007: Effect/Action/Resource[/Condition]. As patterns nao
 * pretendem exaustividade — sao um primeiro guard de "obviamente errado"
 * (ex: faltar `effect`, escrever `Action` ao invés de `action`). O backend
 * permanece a fonte da verdade.
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
            description: "Conditions estilo AWS (ex: stringEquals). Opcional.",
          },
        },
      },
    },
  },
} as const;

/**
 * Carregamento dinamico do Monaco. SSR desligado porque Monaco precisa de
 * `window` e `document`. Loading state mostra um placeholder enxuto.
 */
const MonacoEditor = dynamic(
  async () => {
    const mod = await import("@monaco-editor/react");
    return mod.Editor;
  },
  {
    ssr: false,
    loading: () => (
      <div
        role="status"
        aria-live="polite"
        style={{
          display: "flex",
          height: 400,
          width: "100%",
          alignItems: "center",
          justifyContent: "center",
          borderRadius: "var(--radius-sm)",
          border: "1px solid var(--border)",
          background: "var(--chip)",
          fontSize: 12,
          color: "var(--muted)",
        }}
      >
        Carregando editor…
      </div>
    ),
  },
);

export interface PolicyEditorProps {
  /** String JSON crua. Mantida como string para preservar formatacao do usuario. */
  value: string;
  /**
   * Callback chamado a cada mudanca. `isValid` reflete parse JSON OK + schema
   * sem erros — o caller deve usar essa flag para habilitar/desabilitar Save.
   */
  onChange: (value: string, isValid: boolean) => void;
  /** Bloqueia edicao. Default false. */
  readOnly?: boolean;
  /** Altura em px. Default 400. */
  height?: number;
}

interface MarkerSummary {
  line: number;
  column: number;
  message: string;
  severity: number;
}

/**
 * Tenta detectar tema dark via media query. Usado para escolher entre
 * `vs` (light) e `vs-dark`. Os tokens de cor do globals.css `.dark` ainda
 * sao tratados pelo Tailwind no resto da UI; o Monaco tem tema proprio.
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
   * Valida sintaticamente o JSON. Schema validation propriamente dita roda
   * dentro do Monaco e chega via onValidate -> markers. Aqui so capturamos
   * "parseou ou nao" para casos onde Monaco ainda nao montou.
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
   * Mantemos uma ref pro callback de onChange para evitar registrar handlers
   * do Monaco a cada render. O caller espera ser notificado a cada keystroke
   * com o isValid corrente.
   */
  const onChangeRef = useRef(onChange);
  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  // Re-notifica o parent sempre que validade mudar (sem digitacao do usuário,
  // ex: schema redefinido ou tema). Garante que Save reflita estado real.
  useEffect(() => {
    onChangeRef.current(value, isValid);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isValid]);

  const handleMount: OnMount = useCallback((mountedEditor, monaco) => {
    editorRef.current = mountedEditor;
    monacoRef.current = monaco;

    // Registra schema NORA para validacao inline com squiggle.
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
      // Se o schema falhar de registrar (improvavel), o editor ainda funciona
      // como JSON puro. Nao quebrar a pagina.
    }
  }, []);

  const handleChange: OnChange = useCallback((next) => {
    const safe = next ?? "";
    // Re-parseia para feedback rapido — o markers state cobre schema.
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
    // Schema markers serao atualizados pelo onValidate; aqui combinamos com o
    // estado atual do markers ref pra dar feedback imediato.
    onChangeRef.current(safe, nextIsValid && monacoRef.current
      ? markersOk(monacoRef.current, editorRef.current)
      : nextIsValid);
  }, []);

  /**
   * `onValidate` do Monaco entrega o array completo de markers (parse + schema).
   * Convertimos em forma leve para renderizar a lista de erros.
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
   * Botao "Formatar": chama a acao nativa do Monaco. Indenta com 2 espacos
   * (default do JSON language service).
   */
  const handleFormat = useCallback(() => {
    const ed = editorRef.current;
    if (!ed) return;
    ed.getAction("editor.action.formatDocument")?.run();
  }, []);

  /**
   * Captura erros do dynamic import / loader do Monaco e ativa fallback.
   * Em produção raramente vai disparar — esta aqui para nao deixar o usuário
   * com a pagina em branco se houver problema de CDN ou bundling.
   *
   * Cobertura:
   * 1. Listener `window.error` para falhas síncronas do loader (script error).
   * 2. Listener `window.unhandledrejection` para promises rejeitadas (ex:
   *    import dinâmico do Monaco falha).
   * 3. Watchdog: se o `onMount` nao for chamado em 12s (tipico CDN slow ou
   *    bloqueio de rede), assume falha e ativa fallback.
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

  // ------- Fallback: textarea cru se Monaco falhou -------
  if (monacoFailed) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            borderRadius: "var(--radius-sm)",
            border: "1px solid oklch(0.85 0.07 70)",
            background: "oklch(0.96 0.04 70)",
            padding: "8px 12px",
            fontSize: 12,
            color: "var(--warn)",
          }}
        >
          <span>
            Editor avançado indisponível — usando textarea simples. Salve mesmo assim, se
            necessario.
          </span>
        </div>
        <textarea
          value={value}
          onChange={(e) => onChangeRef.current(e.target.value, isFallbackValid(e.target.value))}
          rows={Math.max(12, Math.floor(height / 24))}
          readOnly={readOnly}
          spellCheck={false}
          className="nora-textarea"
          style={{ fontFamily: "var(--mono)", fontSize: 12 }}
          aria-label="Documento de policy JSON"
        />
        {parseError && (
          <p style={{ fontSize: 11.5, color: "var(--danger)", margin: 0 }} role="alert">
            {parseError}
          </p>
        )}
      </div>
    );
  }

  const firstError = markers.find((m) => m.severity >= 8) ?? null;
  const errorCount = markers.filter((m) => m.severity >= 8).length;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {/* Toolbar */}
      <div
        style={{
          display: "flex",
          flexWrap: "wrap",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 8,
          borderRadius: "var(--radius-sm) var(--radius-sm) 0 0",
          border: "1px solid var(--border)",
          borderBottom: "none",
          background: "var(--chip)",
          padding: "8px 10px",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <button
            type="button"
            onClick={handleFormat}
            disabled={readOnly || !parseValid}
            className="nora-btn nora-btn--ghost nora-btn--sm"
          >
            Formatar
          </button>
          <span style={{ fontFamily: "var(--mono)", fontSize: 11.5, color: "var(--muted)" }}>
            JSON · UTF-8
          </span>
        </div>
        <span
          aria-live="polite"
          style={{
            fontSize: 11.5,
            fontWeight: 500,
            borderRadius: "var(--radius-pill)",
            padding: "2px 9px",
            background: isValid ? "oklch(0.94 0.05 155)" : "oklch(0.95 0.04 25)",
            color: isValid ? "var(--success)" : "var(--danger)",
          }}
        >
          {isValid ? "✓ Válido" : `✗ ${errorCount || (parseValid ? 0 : 1)} erro(s)`}
        </span>
      </div>

      <div
        style={{
          overflow: "hidden",
          borderRadius: "0 0 var(--radius-sm) var(--radius-sm)",
          border: "1px solid var(--border)",
        }}
      >
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
            // Schema validation é tratada via setDiagnosticsOptions em handleMount.
          }}
        />
      </div>

      {/* Mensagem de erro abaixo */}
      {parseError && (
        <p
          style={{ fontSize: 11.5, color: "var(--danger)", margin: 0 }}
          role="alert"
          id={errorRegionId}
        >
          JSON inválido: {parseError}
        </p>
      )}
      {!parseError && firstError && (
        <p
          style={{ fontSize: 11.5, color: "var(--danger)", margin: 0 }}
          role="alert"
          id={errorRegionId}
        >
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
 * Consulta os markers atuais do model para decidir validade combinada.
 * Usado dentro de handleChange porque o `markers` do React state pode estar
 * defasado em 1 tick — o monaco em si tem a fonte mais recente.
 */
function markersOk(
  monaco: Monaco,
  ed: editor.IStandaloneCodeEditor | null,
): boolean {
  if (!ed) return true; // sem editor pronto ainda — confia no parse
  const model = ed.getModel();
  if (!model) return true;
  const all = monaco.editor.getModelMarkers({ resource: model.uri });
  return all.filter((m) => m.severity >= 8).length === 0;
}
