"use client";

import { useMemo, useState } from "react";

import { SERVICE_LABEL, type LlmModel, type ServiceBinding, type ServiceKey } from "@/lib/contracts";
import { MOCK_BINDINGS, MOCK_MODELS } from "@/lib/mock";

const SERVICES: ServiceKey[] = ["chat", "analysis", "multimodal"];

export default function ModelosPage() {
  const [models, setModels] = useState<LlmModel[]>(MOCK_MODELS);
  const [bindings, setBindings] = useState<ServiceBinding[]>(MOCK_BINDINGS);
  const [notice, setNotice] = useState<string | null>(null);

  const byId = useMemo(() => new Map(models.map((m) => [m.id, m])), [models]);

  function setBinding(service: ServiceKey, modelId: string) {
    const m = byId.get(modelId);
    // Guard: análise exige JSON Schema strict (ADR 0003). Bloqueia binding inválido.
    if (service === "analysis" && m && !m.supportsStrictJsonSchema) {
      setNotice(`"${m.label}" não suporta JSON Schema strict — não pode ser usado na Análise (ADR 0003).`);
      return;
    }
    setBindings((prev) => prev.map((b) => (b.service === service ? { ...b, modelId } : b)));
    setNotice(`(demo) Binding de ${SERVICE_LABEL[service]} → ${m?.label}. Persiste via PUT /admin/platform/config quando o backend subir.`);
  }

  function toggle(service: ServiceKey) {
    setBindings((prev) => prev.map((b) => (b.service === service ? { ...b, enabled: !b.enabled } : b)));
  }

  function removeModel(id: string) {
    if (bindings.some((b) => b.modelId === id)) {
      setNotice("Não dá pra remover um modelo em uso por algum serviço. Troque o binding antes.");
      return;
    }
    setModels((prev) => prev.filter((m) => m.id !== id));
    setNotice("(demo) Modelo removido localmente. Persiste via DELETE /admin/platform/models/{id}.");
  }

  return (
    <div style={{ maxWidth: 920, margin: "0 auto", padding: "48px 40px 80px" }}>
      <header style={{ marginBottom: 24 }}>
        <h1 style={{ fontFamily: "var(--display)", fontSize: 28, fontWeight: 500, letterSpacing: "-0.025em", margin: "0 0 6px" }}>
          Modelos &amp; IA
        </h1>
        <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.6 }}>
          Escolha qual modelo move cada serviço. Mudança vale em runtime, sem redeploy. Catálogo editável.
        </p>
      </header>

      <div style={{ marginBottom: 20, padding: "10px 14px", borderRadius: 9, background: "var(--accent-soft)", color: "var(--accent-ink)", fontSize: 12.5, lineHeight: 1.5 }}>
        Pré-visualização com dados de exemplo. As ações abaixo viram chamadas reais a <code style={mono}>/admin/platform/*</code> quando o backend de plataforma estiver no ar.
      </div>

      {notice && (
        <div style={{ marginBottom: 16, padding: "10px 14px", borderRadius: 9, border: "1px solid var(--border)", background: "var(--chip)", fontSize: 13 }}>
          {notice}
        </div>
      )}

      <h2 style={sectionLabel}>Binding por serviço</h2>
      <div style={{ border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden", marginBottom: 32 }}>
        {SERVICES.map((service, i) => {
          const b = bindings.find((x) => x.service === service);
          const compatible = models.filter((m) => (service === "multimodal" ? m.modality === "multimodal" : true));
          return (
            <div key={service} style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 16px", borderTop: i === 0 ? "none" : "1px solid var(--border)" }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 14 }}>{SERVICE_LABEL[service]}</div>
                <div style={{ fontSize: 11.5, color: "var(--muted)", fontFamily: "var(--mono)" }}>{service}</div>
              </div>
              <select
                value={b?.modelId ?? ""}
                onChange={(e) => setBinding(service, e.target.value)}
                style={select}
              >
                {compatible.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.label}
                    {service === "analysis" && !m.supportsStrictJsonSchema ? " (sem strict)" : ""}
                  </option>
                ))}
              </select>
              <button type="button" onClick={() => toggle(service)} style={{ ...pill, color: b?.enabled ? "var(--success)" : "var(--muted)" }}>
                <span style={{ width: 7, height: 7, borderRadius: "50%", background: b?.enabled ? "var(--success)" : "var(--border-strong)" }} />
                {b?.enabled ? "Ativo" : "Desligado"}
              </button>
            </div>
          );
        })}
      </div>

      <h2 style={sectionLabel}>Catálogo de modelos</h2>
      <div style={{ border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden" }}>
        {models.map((m, i) => (
          <div key={m.id} style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 16px", borderTop: i === 0 ? "none" : "1px solid var(--border)" }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 14, display: "flex", alignItems: "center", gap: 8 }}>
                {m.label}
                <span style={tag}>{m.modality === "multimodal" ? "multimodal" : "texto"}</span>
                {m.supportsStrictJsonSchema && <span style={{ ...tag, color: "var(--accent-ink)" }}>strict JSON</span>}
              </div>
              <div style={{ fontSize: 11.5, color: "var(--muted)", fontFamily: "var(--mono)", marginTop: 2 }}>
                {m.provider} · {m.model} · ${m.inputCostPer1M}/${m.outputCostPer1M} por 1M
                {m.cachedInputCostPer1M != null ? ` · cache $${m.cachedInputCostPer1M}` : ""}
              </div>
            </div>
            <button type="button" onClick={() => removeModel(m.id)} style={{ ...pill, color: "var(--danger)" }}>
              Remover
            </button>
          </div>
        ))}
      </div>
      <button
        type="button"
        onClick={() => setNotice("(demo) O formulário de adicionar modelo abre aqui — POST /admin/platform/models. UI completa na próxima fatia.")}
        style={{ marginTop: 12, ...pill, color: "var(--ink)" }}
      >
        + Adicionar modelo
      </button>
    </div>
  );
}

const mono: React.CSSProperties = { fontFamily: "var(--mono)", fontSize: "0.9em" };
const sectionLabel: React.CSSProperties = {
  fontFamily: "var(--mono)",
  fontSize: 10.5,
  fontWeight: 500,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  color: "var(--muted)",
  margin: "0 0 12px",
};
const select: React.CSSProperties = {
  fontFamily: "var(--sans)",
  fontSize: 13,
  color: "var(--ink)",
  background: "var(--canvas)",
  border: "1px solid var(--border)",
  borderRadius: 8,
  padding: "7px 10px",
  outline: "none",
};
const pill: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  gap: 6,
  fontSize: 12,
  background: "var(--canvas)",
  border: "1px solid var(--border)",
  borderRadius: 999,
  padding: "6px 12px",
  cursor: "pointer",
};
const tag: React.CSSProperties = {
  fontSize: 10,
  fontFamily: "var(--mono)",
  color: "var(--muted)",
  border: "1px solid var(--border)",
  borderRadius: 4,
  padding: "1px 5px",
  textTransform: "uppercase",
  letterSpacing: "0.04em",
};
