"use client";

/**
 * CorporateDomainCard (US32)
 * -------------------------------------------------------------------------
 * Bloco de configuracao do dominio corporativo do tenant. Renderizado no topo
 * da pagina settings/iam. Exibe estado atual e permite ao Root:
 *
 * - Setar um dominio (ex: `acme.com`) para restringir convites futuros (US06).
 * - Limpar a restricao (envia `null`), liberando convites para qualquer dominio.
 *
 * Regras importantes:
 * - Validacao client-side e UX-only — backend e fonte da verdade (ADR 0011).
 * - Em 422 (`TENANT_DOMAIN_INVALID`), mostra mensagem amigavel; outros erros
 *   sao mostrados crus.
 * - Skeleton enquanto carrega; mensagem clara em falha de fetch.
 * - Feedback inline (sem toast — projeto nao usa lib de toasts no MVP).
 */

import { useCallback, useEffect, useState } from "react";
import {
  ApiRequestError,
  getTenantDomain,
  updateTenantDomain,
  type TenantDomain,
} from "@/lib/api/client";
import { Banner, Button, Card, Field, Input, Section } from "@/components/core/ui";

/**
 * Regex de dominio (case-insensitive). Aceita "acme.com", "sub.acme.com.br".
 * Espelha a expectativa do backend (Tenant.isValidEmailDomain): pelo menos
 * dois labels separados por ponto, cada label comecando e terminando com
 * alfanumerico, hifens permitidos no meio.
 *
 * Backend e fonte da verdade — se passar daqui mas o backend rejeitar (422),
 * exibimos a mensagem do backend.
 */
const DOMAIN_REGEX = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/i;

/**
 * Valida o dominio com regras minimas (UX-only — backend re-valida).
 *
 * Retorna `null` quando ok ou uma mensagem de erro pronta para exibir.
 * Optamos por validacao manual ao inves de Zod aqui para nao puxar o bundle
 * de zod num componente pequeno — nenhum outro lugar do app importa zod hoje.
 */
function validateDomain(raw: string): string | null {
  const trimmed = raw.trim();
  if (trimmed.length === 0) return "Informe um dominio.";
  if (trimmed.length > 255) return "Dominio muito longo (max 255 caracteres).";
  if (!DOMAIN_REGEX.test(trimmed)) return "Dominio invalido. Use formato como `acme.com.br`.";
  return null;
}

type Status = "idle" | "loading" | "saving" | "loaded" | "error";

export default function CorporateDomainCard() {
  const [status, setStatus] = useState<Status>("loading");
  const [current, setCurrent] = useState<TenantDomain | null>(null);
  const [input, setInput] = useState("");
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const load = useCallback(async () => {
    setStatus("loading");
    setFetchError(null);
    try {
      const dto = await getTenantDomain();
      setCurrent(dto);
      setInput(dto.allowedEmailDomain ?? "");
      setStatus("loaded");
    } catch (err) {
      setFetchError(
        err instanceof ApiRequestError
          ? err.message
          : "Nao foi possivel carregar a configuracao de dominio.",
      );
      setStatus("error");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Limpa mensagens transientes ao editar.
  function onInputChange(value: string) {
    setInput(value);
    if (formError) setFormError(null);
    if (success) setSuccess(null);
  }

  async function persist(payload: { allowedEmailDomain: string | null }) {
    setStatus("saving");
    setFormError(null);
    setSuccess(null);
    try {
      const resp = await updateTenantDomain(payload);
      setCurrent({ tenantId: resp.tenantId, allowedEmailDomain: resp.allowedEmailDomain });
      setInput(resp.allowedEmailDomain ?? "");
      setSuccess(
        resp.allowedEmailDomain
          ? `Dominio salvo: ${resp.allowedEmailDomain}`
          : "Restricao de dominio removida.",
      );
      setStatus("loaded");
    } catch (err) {
      // Backend valida tambem (defesa em profundidade). 422 -> TENANT_DOMAIN_INVALID.
      if (err instanceof ApiRequestError && err.status === 422) {
        setFormError("Dominio invalido. Use formato como `acme.com.br`.");
      } else if (err instanceof ApiRequestError) {
        setFormError(
          err.payload?.message ?? `Falha ao salvar (${err.status}). Tente novamente.`,
        );
      } else {
        setFormError("Falha ao salvar. Tente novamente.");
      }
      setStatus("loaded");
    }
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setSuccess(null);
    const trimmed = input.trim();
    const validationError = validateDomain(trimmed);
    if (validationError) {
      setFormError(validationError);
      return;
    }
    // Normaliza para lowercase no client (backend tambem normaliza, mas
    // refletir aqui da feedback imediato).
    void persist({ allowedEmailDomain: trimmed.toLowerCase() });
  }

  function onClearRestriction() {
    setFormError(null);
    setSuccess(null);
    void persist({ allowedEmailDomain: null });
  }

  const isSaving = status === "saving";
  const isLoading = status === "loading";
  const hasDomain = Boolean(current?.allowedEmailDomain);

  return (
    <Section title="Dominio corporativo">
      <Card>
        <p style={{ fontSize: 13, color: "var(--muted)", margin: "0 0 16px", lineHeight: 1.5 }}>
          Restringe convites futuros a e-mails desse dominio. Usuarios ja existentes nao sao
          afetados. Deixe vazio (ou clique em &quot;Limpar restricao&quot;) para permitir qualquer
          dominio.
        </p>

        {isLoading ? (
          <div
            aria-busy="true"
            aria-live="polite"
            style={{ display: "flex", flexDirection: "column", gap: 8 }}
          >
            <div
              style={{
                height: 38,
                maxWidth: 360,
                borderRadius: "var(--radius-sm)",
                background: "var(--chip)",
              }}
            />
            <div
              style={{ height: 12, width: "60%", borderRadius: 4, background: "var(--chip)" }}
            />
          </div>
        ) : fetchError ? (
          <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-start", gap: 12 }}>
            <Banner tone="error">{fetchError}</Banner>
            <Button onClick={() => void load()}>Tentar novamente</Button>
          </div>
        ) : (
          <form onSubmit={onSubmit} noValidate>
            <Field label="Dominio permitido" htmlFor="allowed-email-domain">
              <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 8 }}>
                <Input
                  id="allowed-email-domain"
                  name="allowedEmailDomain"
                  type="text"
                  value={input}
                  onChange={(e) => onInputChange(e.target.value)}
                  placeholder="acme.com"
                  autoComplete="off"
                  spellCheck={false}
                  disabled={isSaving}
                  aria-invalid={formError ? "true" : "false"}
                  aria-describedby={formError ? "domain-error" : undefined}
                  style={{ flex: 1, minWidth: 220, maxWidth: 360 }}
                />
                <Button type="submit" variant="primary" disabled={isSaving || !input.trim()}>
                  {isSaving ? "Salvando..." : "Salvar"}
                </Button>
                {hasDomain && (
                  <Button type="button" onClick={onClearRestriction} disabled={isSaving}>
                    Limpar restricao
                  </Button>
                )}
              </div>
              <p style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 8 }}>
                {hasDomain ? (
                  <>
                    Atualmente:{" "}
                    <span
                      style={{
                        fontFamily: "var(--mono)",
                        color: "var(--ink)",
                        background: "var(--chip)",
                        borderRadius: "var(--radius-sm)",
                        padding: "1px 6px",
                      }}
                    >
                      {current?.allowedEmailDomain}
                    </span>
                  </>
                ) : (
                  <>Nenhuma restricao ativa.</>
                )}
              </p>
            </Field>

            {formError && (
              <div id="domain-error" style={{ marginTop: 4 }}>
                <Banner tone="error">{formError}</Banner>
              </div>
            )}
            {success && (
              <div style={{ marginTop: 4 }}>
                <Banner tone="ok">{success}</Banner>
              </div>
            )}
          </form>
        )}
      </Card>
    </Section>
  );
}
