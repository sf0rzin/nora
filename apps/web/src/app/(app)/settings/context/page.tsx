"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiRequestError,
  getMe,
  getTenantContext,
  upsertTenantContext,
  type TenantContextDto,
} from "@/lib/api/client";
import {
  Banner,
  Button,
  Card,
  EmptyState,
  Field,
  Input,
  PageHeader,
  Section,
  Textarea,
} from "@/components/core/ui";

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
  const router = useRouter();
  // Enterprise route guard: Contexto do tenant é uma feature Enterprise. Core
  // (plan !== ENTERPRISE) é redirecionado pro dashboard. null = ainda checando.
  const [allowed, setAllowed] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    getMe()
      .then((m) => {
        if (cancelled) return;
        if (m.plan !== "ENTERPRISE") {
          router.replace("/dashboard");
        } else {
          setAllowed(true);
        }
      })
      .catch(() => {
        if (!cancelled) router.replace("/dashboard");
      });
    return () => {
      cancelled = true;
    };
  }, [router]);

  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [version, setVersion] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getTenantContext()
      .then((dto) => {
        if (!cancelled) {
          setForm(fromDto(dto));
          setVersion(dto.version);
        }
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
      setVersion(saved.version);
      setMessage("Contexto salvo com sucesso.");
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : "Falha ao salvar contexto.");
    } finally {
      setSaving(false);
    }
  }

  // Bloqueia render enquanto o guard checa o plano (e durante o redirect do Core).
  if (allowed === null) {
    return <EmptyState>…</EmptyState>;
  }

  if (loading) {
    return <EmptyState>Carregando contexto…</EmptyState>;
  }

  return (
    <div style={{ maxWidth: 768 }}>
      <PageHeader
        title="Contexto do tenant"
        subtitle={
          version != null
            ? `Esses dados são enviados ao motor de NLP em toda análise de reunião. · Versão ${version}`
            : "Esses dados são enviados ao motor de NLP em toda análise de reunião."
        }
      />

      <Card>
        <form onSubmit={onSubmit}>
          <Field label="Nome da empresa" htmlFor="ctx-company-name" required>
            <Input
              id="ctx-company-name"
              required
              value={form.companyName}
              onChange={(e) => update("companyName", e.target.value)}
            />
          </Field>

          <div
            style={{
              display: "grid",
              gridTemplateColumns: "1fr 1fr",
              gap: 16,
            }}
          >
            <Field label="Indústria" htmlFor="ctx-industry">
              <Input
                id="ctx-industry"
                value={form.industry}
                onChange={(e) => update("industry", e.target.value)}
                placeholder="ex: Software B2B"
              />
            </Field>
            <Field label="ICP (Ideal Customer Profile)" htmlFor="ctx-icp">
              <Input
                id="ctx-icp"
                value={form.idealCustomerProfile}
                onChange={(e) => update("idealCustomerProfile", e.target.value)}
                placeholder="ex: midmarket varejo no Brasil"
              />
            </Field>
          </div>

          <Field label="Proposta de valor" htmlFor="ctx-value-proposition">
            <Textarea
              id="ctx-value-proposition"
              rows={3}
              value={form.valueProposition}
              onChange={(e) => update("valueProposition", e.target.value)}
            />
          </Field>

          <Field
            label="Concorrentes (um por linha)"
            htmlFor="ctx-competitors"
            hint="Ex: Concorrente A&#10;Concorrente B"
          >
            <Textarea
              id="ctx-competitors"
              rows={3}
              value={form.competitors}
              onChange={(e) => update("competitors", e.target.value)}
            />
          </Field>

          <Field label="Objection handling (um por linha)" htmlFor="ctx-objection-handling">
            <Textarea
              id="ctx-objection-handling"
              rows={3}
              value={form.objectionHandling}
              onChange={(e) => update("objectionHandling", e.target.value)}
            />
          </Field>

          <Section
            title="Produtos"
            action={
              <Button size="sm" onClick={addProduct}>
                + Adicionar produto
              </Button>
            }
          >
            {form.products.length === 0 && (
              <p style={{ fontSize: 13, color: "var(--muted)", margin: "4px 0 0" }}>
                Nenhum produto cadastrado.
              </p>
            )}

            <div style={{ display: "flex", flexDirection: "column", gap: 12, marginTop: 12 }}>
              {form.products.map((p, i) => (
                <div
                  key={i}
                  style={{
                    border: "1px solid var(--border)",
                    borderRadius: "var(--radius-sm)",
                    padding: 12,
                    display: "flex",
                    flexDirection: "column",
                    gap: 8,
                  }}
                >
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <Input
                      style={{ flex: 1 }}
                      value={p.name}
                      onChange={(e) => updateProduct(i, { name: e.target.value })}
                      placeholder="Nome do produto"
                      aria-label={`Nome do produto ${i + 1}`}
                    />
                    <Button
                      variant="danger"
                      size="sm"
                      onClick={() => removeProduct(i)}
                    >
                      Remover
                    </Button>
                  </div>
                  <Textarea
                    rows={2}
                    value={p.description}
                    onChange={(e) => updateProduct(i, { description: e.target.value })}
                    placeholder="Descrição"
                    aria-label={`Descrição do produto ${i + 1}`}
                  />
                  <Textarea
                    rows={2}
                    value={p.keyDifferentiators}
                    onChange={(e) => updateProduct(i, { keyDifferentiators: e.target.value })}
                    placeholder="Diferenciais (um por linha)"
                    aria-label={`Diferenciais do produto ${i + 1}`}
                  />
                </div>
              ))}
            </div>
          </Section>

          {error && (
            <div style={{ marginBottom: 16 }}>
              <Banner tone="error">{error}</Banner>
            </div>
          )}
          {message && (
            <div style={{ marginBottom: 16 }}>
              <Banner tone="ok">{message}</Banner>
            </div>
          )}

          <div>
            <Button type="submit" variant="primary" disabled={saving}>
              {saving ? "Salvando…" : "Salvar contexto"}
            </Button>
          </div>
        </form>
      </Card>
    </div>
  );
}
