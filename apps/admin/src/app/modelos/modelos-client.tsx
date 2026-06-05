"use client";

import { useMemo, useState } from "react";

import { SERVICE_LABEL, type LlmModel, type Modality, type ServiceBinding, type ServiceKey } from "@/lib/contracts";

import { addModelAction, bindServiceAction, removeModelAction } from "./actions";

const SERVICES: ServiceKey[] = ["chat", "analysis", "multimodal"];

type Notice = { kind: "ok" | "err"; text: string } | null;

export function ModelosClient({
  initialModels,
  initialBindings,
}: {
  initialModels: LlmModel[];
  initialBindings: ServiceBinding[];
}) {
  const [models, setModels] = useState<LlmModel[]>(initialModels);
  const [bindings, setBindings] = useState<ServiceBinding[]>(initialBindings);
  const [notice, setNotice] = useState<Notice>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [showAdd, setShowAdd] = useState(false);

  const byId = useMemo(() => new Map(models.map((m) => [m.id, m])), [models]);

  async function setBinding(service: ServiceKey, modelId: string) {
    const m = byId.get(modelId);
    // Guard: análise exige JSON Schema strict (ADR 0003). Bloqueia binding inválido antes do round-trip.
    if (service === "analysis" && m && !m.supportsStrictJsonSchema) {
      setNotice({ kind: "err", text: `"${m.label}" não suporta JSON Schema strict — não pode mover a Análise (ADR 0003).` });
      return;
    }
    const current = bindings.find((b) => b.service === service);
    const enabled = current?.enabled ?? true;
    setBusy(`bind:${service}`);
    const res = await bindServiceAction(service, modelId, enabled);
    setBusy(null);
    if (res.ok) {
      setBindings((prev) => prev.map((b) => (b.service === service ? { ...b, modelId } : b)));
      setNotice({ kind: "ok", text: `${SERVICE_LABEL[service]} agora usa "${m?.label}". Vale em runtime, sem redeploy.` });
    } else {
      setNotice({ kind: "err", text: `Falha ao trocar o modelo: ${res.error}` });
    }
  }

  async function toggle(service: ServiceKey) {
    const current = bindings.find((b) => b.service === service);
    if (!current) return;
    setBusy(`bind:${service}`);
    const res = await bindServiceAction(service, current.modelId, !current.enabled);
    setBusy(null);
    if (res.ok) {
      setBindings((prev) => prev.map((b) => (b.service === service ? { ...b, enabled: !b.enabled } : b)));
    } else {
      setNotice({ kind: "err", text: `Falha ao alternar: ${res.error}` });
    }
  }

  async function removeModel(id: string) {
    if (bindings.some((b) => b.modelId === id)) {
      setNotice({ kind: "err", text: "Não dá pra remover um modelo em uso por algum serviço. Troque o binding antes." });
      return;
    }
    setBusy(`del:${id}`);
    const res = await removeModelAction(id);
    setBusy(null);
    if (res.ok) {
      setModels((prev) => prev.filter((m) => m.id !== id));
      setNotice({ kind: "ok", text: "Modelo removido do catálogo." });
    } else {
      setNotice({ kind: "err", text: `Falha ao remover: ${res.error}` });
    }
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

      {notice && (
        <div
          style={{
            marginBottom: 16,
            padding: "10px 14px",
            borderRadius: 9,
            border: "1px solid var(--border)",
            background: notice.kind === "err" ? "var(--danger-soft, var(--chip))" : "var(--accent-soft)",
            color: notice.kind === "err" ? "var(--danger)" : "var(--accent-ink)",
            fontSize: 13,
          }}
        >
          {notice.text}
        </div>
      )}

      <h2 style={sectionLabel}>Binding por serviço</h2>
      <div style={{ border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden", marginBottom: 32 }}>
        {SERVICES.map((service, i) => {
          const b = bindings.find((x) => x.service === service);
          const compatible = models.filter((m) => (service === "multimodal" ? m.modality === "multimodal" : true));
          const rowBusy = busy === `bind:${service}`;
          return (
            <div key={service} style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 16px", borderTop: i === 0 ? "none" : "1px solid var(--border)", opacity: rowBusy ? 0.6 : 1 }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 14 }}>{SERVICE_LABEL[service]}</div>
                <div style={{ fontSize: 11.5, color: "var(--muted)", fontFamily: "var(--mono)" }}>{service}</div>
              </div>
              <select
                value={b?.modelId ?? ""}
                disabled={rowBusy}
                onChange={(e) => setBinding(service, e.target.value)}
                style={select}
              >
                {b == null && <option value="">— sem binding —</option>}
                {compatible.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.label}
                    {service === "analysis" && !m.supportsStrictJsonSchema ? " (sem strict)" : ""}
                  </option>
                ))}
              </select>
              <button type="button" disabled={rowBusy} onClick={() => toggle(service)} style={{ ...pill, color: b?.enabled ? "var(--success)" : "var(--muted)" }}>
                <span style={{ width: 7, height: 7, borderRadius: "50%", background: b?.enabled ? "var(--success)" : "var(--border-strong)" }} />
                {b?.enabled ? "Ativo" : "Desligado"}
              </button>
            </div>
          );
        })}
      </div>

      <h2 style={sectionLabel}>Catálogo de modelos</h2>
      <div style={{ border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden" }}>
        {models.length === 0 && (
          <div style={{ padding: "16px", fontSize: 13, color: "var(--muted)" }}>Catálogo vazio — adicione o primeiro modelo abaixo.</div>
        )}
        {models.map((m, i) => (
          <div key={m.id} style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 16px", borderTop: i === 0 ? "none" : "1px solid var(--border)", opacity: busy === `del:${m.id}` ? 0.6 : 1 }}>
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
            <button type="button" disabled={busy === `del:${m.id}`} onClick={() => removeModel(m.id)} style={{ ...pill, color: "var(--danger)" }}>
              Remover
            </button>
          </div>
        ))}
      </div>

      {showAdd ? (
        <AddModelForm
          onCancel={() => setShowAdd(false)}
          onAdded={(text) => {
            setShowAdd(false);
            setNotice({ kind: "ok", text });
          }}
          onError={(text) => setNotice({ kind: "err", text })}
        />
      ) : (
        <button type="button" onClick={() => setShowAdd(true)} style={{ marginTop: 12, ...pill, color: "var(--ink)" }}>
          + Adicionar modelo
        </button>
      )}
    </div>
  );
}

function AddModelForm({
  onCancel,
  onAdded,
  onError,
}: {
  onCancel: () => void;
  onAdded: (text: string) => void;
  onError: (text: string) => void;
}) {
  const [provider, setProvider] = useState("");
  const [model, setModel] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [modality, setModality] = useState<Modality>("text");
  const [strict, setStrict] = useState(false);
  const [priceIn, setPriceIn] = useState("0");
  const [priceOut, setPriceOut] = useState("0");
  const [saving, setSaving] = useState(false);

  async function submit() {
    if (!provider.trim() || !model.trim() || !displayName.trim()) {
      onError("Preencha provider, model e nome.");
      return;
    }
    setSaving(true);
    const res = await addModelAction({
      provider: provider.trim(),
      model: model.trim(),
      displayName: displayName.trim(),
      modality,
      supportsStrictJsonSchema: strict,
      priceInputPerMTok: Number(priceIn) || 0,
      priceOutputPerMTok: Number(priceOut) || 0,
    });
    setSaving(false);
    if (res.ok) onAdded(`Modelo "${displayName.trim()}" adicionado ao catálogo.`);
    else onError(`Falha ao adicionar: ${res.error}`);
  }

  return (
    <div style={{ marginTop: 12, border: "1px solid var(--border)", borderRadius: 12, padding: 16, display: "grid", gap: 10 }}>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
        <input placeholder="provider (ex.: openai)" value={provider} onChange={(e) => setProvider(e.target.value)} style={input} />
        <input placeholder="model (ex.: gpt-4o-mini)" value={model} onChange={(e) => setModel(e.target.value)} style={input} />
      </div>
      <input placeholder="nome amigável (ex.: GPT-4o mini)" value={displayName} onChange={(e) => setDisplayName(e.target.value)} style={input} />
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
        <input placeholder="custo input / 1M (USD)" inputMode="decimal" value={priceIn} onChange={(e) => setPriceIn(e.target.value)} style={input} />
        <input placeholder="custo output / 1M (USD)" inputMode="decimal" value={priceOut} onChange={(e) => setPriceOut(e.target.value)} style={input} />
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 16, fontSize: 13 }}>
        <label style={{ display: "flex", alignItems: "center", gap: 6 }}>
          <select value={modality} onChange={(e) => setModality(e.target.value as Modality)} style={select}>
            <option value="text">texto</option>
            <option value="multimodal">multimodal</option>
          </select>
        </label>
        <label style={{ display: "flex", alignItems: "center", gap: 6, cursor: "pointer" }}>
          <input type="checkbox" checked={strict} onChange={(e) => setStrict(e.target.checked)} />
          Suporta JSON Schema strict
        </label>
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 4 }}>
        <button type="button" disabled={saving} onClick={submit} style={{ ...pill, color: "var(--accent-ink)" }}>
          {saving ? "Salvando…" : "Salvar modelo"}
        </button>
        <button type="button" disabled={saving} onClick={onCancel} style={{ ...pill, color: "var(--muted)" }}>
          Cancelar
        </button>
      </div>
    </div>
  );
}

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
const input: React.CSSProperties = {
  fontFamily: "var(--sans)",
  fontSize: 13,
  color: "var(--ink)",
  background: "var(--canvas)",
  border: "1px solid var(--border)",
  borderRadius: 8,
  padding: "8px 11px",
  outline: "none",
  width: "100%",
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
