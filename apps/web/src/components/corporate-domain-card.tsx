"use client";

/**
 * CorporateDomainCard (US32)
 * -------------------------------------------------------------------------
 * Configuration block for the tenant corporate domain. Rendered at the top of
 * the settings/iam page. Shows the current state and lets the Root:
 *
 * - Set a domain (e.g.: `acme.com`) to restrict future invites (US06).
 * - Clear the restriction (sends `null`), allowing invites for any domain.
 *
 * Important rules:
 * - Client-side validation is UX-only — the backend is the source of truth (ADR 0011).
 * - On 422 (`TENANT_DOMAIN_INVALID`), shows a friendly message; other errors
 *   are shown raw.
 * - Skeleton while loading; clear message on fetch failure.
 * - Inline feedback (no toast — the project does not use a toast lib in the MVP).
 */

import { useCallback, useEffect, useState } from "react";
import {
  ApiRequestError,
  getTenantDomain,
  updateTenantDomain,
  type TenantDomain,
} from "@/lib/api/client";

/**
 * Domain regex (case-insensitive). Accepts "acme.com", "sub.acme.com.br".
 * Mirrors the backend expectation (Tenant.isValidEmailDomain): at least
 * two labels separated by a dot, each label starting and ending with an
 * alphanumeric, hyphens allowed in the middle.
 *
 * The backend is the source of truth — if it gets past here but the backend
 * rejects it (422), we show the backend message.
 */
const DOMAIN_REGEX = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/i;

/**
 * Validates the domain with minimal rules (UX-only — the backend re-validates).
 *
 * Returns `null` when ok or an error message ready to display.
 * We went with manual validation instead of Zod here so as not to pull the zod
 * bundle into a small component — nowhere else in the app imports zod today.
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

  // Clears transient messages while editing.
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
      // The backend validates too (defense in depth). 422 -> TENANT_DOMAIN_INVALID.
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
    // Normalize to lowercase on the client (the backend normalizes too, but
    // reflecting it here gives immediate feedback).
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
    <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-5">
      <header className="space-y-1">
        <h2 className="text-lg font-medium">Dominio corporativo</h2>
        <p className="text-sm text-slate-500">
          Restringe convites futuros a e-mails desse dominio. Usuarios ja existentes
          nao sao afetados. Deixe vazio (ou clique em &quot;Limpar restricao&quot;) para
          permitir qualquer dominio.
        </p>
      </header>

      {isLoading ? (
        <div className="space-y-2" aria-busy="true" aria-live="polite">
          <div className="h-9 w-full max-w-md animate-pulse rounded-md bg-slate-100" />
          <div className="h-3 w-2/3 animate-pulse rounded bg-slate-100" />
        </div>
      ) : fetchError ? (
        <div className="space-y-2">
          <p
            className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            role="alert"
          >
            {fetchError}
          </p>
          <button
            type="button"
            onClick={() => void load()}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50"
          >
            Tentar novamente
          </button>
        </div>
      ) : (
        <form className="space-y-3" onSubmit={onSubmit} noValidate>
          <div className="space-y-1">
            <label htmlFor="allowed-email-domain" className="text-sm font-medium text-slate-700">
              Dominio permitido
            </label>
            <div className="flex flex-wrap items-center gap-2">
              <input
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
                className="w-full max-w-sm rounded-md border border-slate-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
              />
              <button
                type="submit"
                disabled={isSaving || !input.trim()}
                className="rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-400"
              >
                {isSaving ? "Salvando..." : "Salvar"}
              </button>
              {hasDomain && (
                <button
                  type="button"
                  onClick={onClearRestriction}
                  disabled={isSaving}
                  className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  Limpar restricao
                </button>
              )}
            </div>
            <p className="text-xs text-slate-500">
              {hasDomain ? (
                <>
                  Atualmente:{" "}
                  <span className="font-mono text-slate-700">{current?.allowedEmailDomain}</span>
                </>
              ) : (
                <>Nenhuma restricao ativa.</>
              )}
            </p>
          </div>

          {formError && (
            <p
              id="domain-error"
              role="alert"
              className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            >
              {formError}
            </p>
          )}
          {success && (
            <p
              role="status"
              aria-live="polite"
              className="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-800"
            >
              {success}
            </p>
          )}
        </form>
      )}
    </section>
  );
}
