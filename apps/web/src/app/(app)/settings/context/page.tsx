"use client";

import { useEffect, useState } from "react";
import {
  ApiRequestError,
  getTenantContext,
  upsertTenantContext,
  type TenantContextDto,
} from "@/lib/api/client";

interface ProductForm {
  name: string;
  description: string;
  keyDifferentiators: string; // string separada por linha
}

interface FormState {
  companyName: string;
  industry: string;
  valueProposition: string;
  idealCustomerProfile: string;
  competitors: string;
  objectionHandling: string;
  products: ProductForm[];
}

const EMPTY_PRODUCT: ProductForm = { name: "", description: "", keyDifferentiators: "" };

const EMPTY_FORM: FormState = {
  companyName: "",
  industry: "",
  valueProposition: "",
  idealCustomerProfile: "",
  competitors: "",
  objectionHandling: "",
  products: [],
};

function fromDto(dto: TenantContextDto): FormState {
  return {
    companyName: dto.companyName ?? "",
    industry: dto.industry ?? "",
    valueProposition: dto.valueProposition ?? "",
    idealCustomerProfile: dto.idealCustomerProfile ?? "",
    competitors: (dto.competitors ?? []).join("\n"),
    objectionHandling: (dto.objectionHandling ?? []).join("\n"),
    products: (dto.products ?? []).map((p) => ({
      name: p.name,
      description: p.description ?? "",
      keyDifferentiators: (p.keyDifferentiators ?? []).join("\n"),
    })),
  };
}

function splitLines(s: string): string[] {
  return s
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean);
}

export default function TenantContextPage() {
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getTenantContext()
      .then((dto) => {
        if (!cancelled) setForm(fromDto(dto));
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiRequestError && err.status === 404) {
          // ainda nao configurado; segue com form vazio
        } else {
          setError(err instanceof Error ? err.message : "Falha ao carregar contexto.");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((s) => ({ ...s, [key]: value }));
  }

  function updateProduct(idx: number, patch: Partial<ProductForm>) {
    setForm((s) => ({
      ...s,
      products: s.products.map((p, i) => (i === idx ? { ...p, ...patch } : p)),
    }));
  }

  function addProduct() {
    setForm((s) => ({ ...s, products: [...s.products, { ...EMPTY_PRODUCT }] }));
  }

  function removeProduct(idx: number) {
    setForm((s) => ({ ...s, products: s.products.filter((_, i) => i !== idx) }));
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setMessage(null);
    setSaving(true);
    try {
      const saved = await upsertTenantContext({
        companyName: form.companyName.trim(),
        industry: form.industry.trim() || undefined,
        valueProposition: form.valueProposition.trim() || undefined,
        idealCustomerProfile: form.idealCustomerProfile.trim() || undefined,
        competitors: splitLines(form.competitors),
        objectionHandling: splitLines(form.objectionHandling),
        products: form.products
          .filter((p) => p.name.trim())
          .map((p) => ({
            name: p.name.trim(),
            description: p.description.trim() || undefined,
            keyDifferentiators: splitLines(p.keyDifferentiators),
          })),
      });
      setForm(fromDto(saved));
      setMessage("Contexto salvo com sucesso.");
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : "Falha ao salvar contexto.");
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return <p className="text-sm text-slate-500">Carregando contexto…</p>;
  }

  return (
    <div className="max-w-3xl space-y-6">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Contexto do tenant</h1>
        <p className="text-sm text-slate-500">
          Esses dados são enviados ao motor de NLP em toda análise de reunião.
        </p>
      </header>

      <form onSubmit={onSubmit} className="space-y-5 rounded-lg border border-slate-200 bg-white p-6">
        <Field label="Nome da empresa" required>
          <input
            required
            value={form.companyName}
            onChange={(e) => update("companyName", e.target.value)}
            className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
          />
        </Field>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <Field label="Indústria">
            <input
              value={form.industry}
              onChange={(e) => update("industry", e.target.value)}
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
              placeholder="ex: Software B2B"
            />
          </Field>
          <Field label="ICP (Ideal Customer Profile)">
            <input
              value={form.idealCustomerProfile}
              onChange={(e) => update("idealCustomerProfile", e.target.value)}
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
              placeholder="ex: midmarket varejo no Brasil"
            />
          </Field>
        </div>

        <Field label="Proposta de valor">
          <textarea
            rows={3}
            value={form.valueProposition}
            onChange={(e) => update("valueProposition", e.target.value)}
            className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
          />
        </Field>

        <Field
          label="Concorrentes (um por linha)"
          hint="Ex: Concorrente A&#10;Concorrente B"
        >
          <textarea
            rows={3}
            value={form.competitors}
            onChange={(e) => update("competitors", e.target.value)}
            className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
          />
        </Field>

        <Field label="Objection handling (um por linha)">
          <textarea
            rows={3}
            value={form.objectionHandling}
            onChange={(e) => update("objectionHandling", e.target.value)}
            className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
          />
        </Field>

        <fieldset className="space-y-3">
          <div className="flex items-center justify-between">
            <legend className="text-sm font-medium text-slate-700">Produtos</legend>
            <button
              type="button"
              onClick={addProduct}
              className="rounded-md border border-slate-300 bg-white px-2 py-1 text-xs hover:bg-slate-50"
            >
              + Adicionar produto
            </button>
          </div>

          {form.products.length === 0 && (
            <p className="text-sm text-slate-500">Nenhum produto cadastrado.</p>
          )}

          {form.products.map((p, i) => (
            <div key={i} className="space-y-2 rounded-md border border-slate-200 p-3">
              <div className="flex items-center justify-between gap-2">
                <input
                  value={p.name}
                  onChange={(e) => updateProduct(i, { name: e.target.value })}
                  placeholder="Nome do produto"
                  className="flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
                />
                <button
                  type="button"
                  onClick={() => removeProduct(i)}
                  className="rounded-md border border-slate-300 bg-white px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                >
                  Remover
                </button>
              </div>
              <textarea
                rows={2}
                value={p.description}
                onChange={(e) => updateProduct(i, { description: e.target.value })}
                placeholder="Descrição"
                className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
              />
              <textarea
                rows={2}
                value={p.keyDifferentiators}
                onChange={(e) => updateProduct(i, { keyDifferentiators: e.target.value })}
                placeholder="Diferenciais (um por linha)"
                className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
              />
            </div>
          ))}
        </fieldset>

        {error && <p className="text-sm text-red-600">{error}</p>}
        {message && <p className="text-sm text-green-700">{message}</p>}

        <div>
          <button
            type="submit"
            disabled={saving}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-50"
          >
            {saving ? "Salvando…" : "Salvar contexto"}
          </button>
        </div>
      </form>

      
    </div>
  );
}

function Field({
  label,
  hint,
  required,
  children,
}: {
  label: string;
  hint?: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <label className="text-sm font-medium text-slate-700">
        {label}
        {required && <span className="text-red-600"> *</span>}
      </label>
      {children}
      {hint && <p className="text-xs text-slate-500 whitespace-pre-line">{hint}</p>}
    </div>
  );
}
