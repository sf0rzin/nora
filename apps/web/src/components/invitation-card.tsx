"use client";

/**
 * InvitationCard (US06, ADR 0011)
 * -------------------------------------------------------------------------
 * Bloco de gestao de convites no settings/iam. Renderizado entre o
 * CorporateDomainCard e a lista de Groups. Permite ao admin com IAM
 * `iam:user:invite` / `iam:invite:read` / `iam:invite:revoke`:
 *
 * - Filtrar convites por status (PENDING por padrao).
 * - Listar convites com email, status, datas e grupos.
 * - Criar novo convite via form inline (email + grupos + expiresInDays).
 * - Revogar convite PENDING via confirm() nativo.
 *
 * Decisoes de UX:
 * - Filtro de status como <select>: mais simples que tabs sem lib de UI.
 * - Multi-select de grupos via checkboxes em lista, sem dropdown — quantidade
 *   de grupos costuma ser pequena no MVP e isso evita lib de combobox.
 * - Datas formatadas via Intl.DateTimeFormat('pt-BR') — sem date-fns.
 * - Confirm de revogacao via window.confirm() nativo — sem dialog lib.
 * - Validacao client-side e UX-only; backend e fonte da verdade (ADR 0011).
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ApiRequestError,
  type GroupDto,
  type Invite,
  type InviteStatus,
  inviteUser,
  listGroups,
  listInvites,
  revokeInvite,
} from "@/lib/api/client";

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const STATUS_FILTERS: ReadonlyArray<{ value: InviteStatus | "ALL"; label: string }> = [
  { value: "PENDING", label: "Pendentes" },
  { value: "ACCEPTED", label: "Aceitos" },
  { value: "EXPIRED", label: "Expirados" },
  { value: "REVOKED", label: "Revogados" },
  { value: "ALL", label: "Todos" },
];

const STATUS_LABEL: Record<InviteStatus, string> = {
  PENDING: "Pendente",
  ACCEPTED: "Aceito",
  EXPIRED: "Expirado",
  REVOKED: "Revogado",
};

const STATUS_CLASSES: Record<InviteStatus, string> = {
  PENDING: "bg-amber-100 text-amber-800",
  ACCEPTED: "bg-green-100 text-green-800",
  EXPIRED: "bg-slate-100 text-slate-600",
  REVOKED: "bg-red-100 text-red-700",
};

const DATE_FORMATTER = new Intl.DateTimeFormat("pt-BR", {
  dateStyle: "short",
  timeStyle: "short",
});

function formatDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return DATE_FORMATTER.format(d);
}

function mapInviteError(err: unknown): string {
  if (!(err instanceof ApiRequestError)) {
    return "Erro ao enviar convite. Tente novamente.";
  }
  // 422 — backend devolve mensagem util. Detecta caso especial de domain.
  if (err.status === 422) {
    const msg = err.payload?.message ?? "";
    const code = err.payload?.code ?? "";
    if (code === "EMAIL_DOMAIN_NOT_ALLOWED" || /domain/i.test(msg)) {
      return "E-mail nao corresponde ao dominio corporativo configurado.";
    }
    return msg || "Dados invalidos.";
  }
  if (err.status === 403) return "Voce nao tem permissao para convidar.";
  if (err.status === 409) return "Ja existe convite pendente para esse e-mail.";
  return err.payload?.message ?? "Erro ao enviar convite. Tente novamente.";
}

export default function InvitationCard() {
  const [statusFilter, setStatusFilter] = useState<InviteStatus | "ALL">("PENDING");
  const [invites, setInvites] = useState<Invite[]>([]);
  const [groups, setGroups] = useState<GroupDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form inline state.
  const [showForm, setShowForm] = useState(false);
  const [formEmail, setFormEmail] = useState("");
  const [formGroupIds, setFormGroupIds] = useState<string[]>([]);
  const [formExpiresInDays, setFormExpiresInDays] = useState(7);
  const [formError, setFormError] = useState<string | null>(null);
  const [formSubmitting, setFormSubmitting] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);

  const groupNameById = useMemo(() => {
    const m = new Map<string, string>();
    for (const g of groups) m.set(g.id, g.name);
    return m;
  }, [groups]);

  const loadInvites = useCallback(async (filter: InviteStatus | "ALL") => {
    setLoading(true);
    setError(null);
    try {
      const resp = await listInvites(filter === "ALL" ? undefined : filter);
      setInvites(resp.items);
    } catch (err) {
      setError(
        err instanceof ApiRequestError
          ? err.message
          : "Nao foi possivel carregar os convites.",
      );
    } finally {
      setLoading(false);
    }
  }, []);

  // Carrega grupos 1x (lookup id -> name) + invites na montagem.
  useEffect(() => {
    void (async () => {
      try {
        const gs = await listGroups();
        setGroups(gs);
      } catch {
        // Lookup de nomes e best-effort — sem grupos, mostramos o id.
      }
    })();
  }, []);

  useEffect(() => {
    void loadInvites(statusFilter);
  }, [statusFilter, loadInvites]);

  function toggleGroup(id: string) {
    setFormGroupIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  }

  function resetForm() {
    setFormEmail("");
    setFormGroupIds([]);
    setFormExpiresInDays(7);
    setFormError(null);
  }

  function onCloseForm() {
    setShowForm(false);
    resetForm();
  }

  async function onSubmitInvite(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setSuccess(null);

    const email = formEmail.trim().toLowerCase();
    if (!EMAIL_REGEX.test(email)) {
      setFormError("Informe um e-mail valido.");
      return;
    }
    const expires = Number(formExpiresInDays);
    if (!Number.isInteger(expires) || expires < 1 || expires > 30) {
      setFormError("Expiracao deve estar entre 1 e 30 dias.");
      return;
    }

    setFormSubmitting(true);
    try {
      await inviteUser({
        email,
        groupIds: formGroupIds,
        expiresInDays: expires,
      });
      setSuccess(`Convite enviado para ${email}.`);
      resetForm();
      setShowForm(false);
      await loadInvites(statusFilter);
    } catch (err) {
      setFormError(mapInviteError(err));
    } finally {
      setFormSubmitting(false);
    }
  }

  async function onRevoke(invite: Invite) {
    if (
      typeof window !== "undefined" &&
      !window.confirm(`Revogar convite para ${invite.email}?`)
    ) {
      return;
    }
    setError(null);
    setSuccess(null);
    try {
      await revokeInvite(invite.id);
      setSuccess(`Convite revogado: ${invite.email}.`);
      await loadInvites(statusFilter);
    } catch (err) {
      setError(
        err instanceof ApiRequestError ? err.message : "Falha ao revogar o convite.",
      );
    }
  }

  return (
    <section className="space-y-4 rounded-lg border border-slate-200 bg-white p-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h2 className="text-lg font-medium">Convites</h2>
          <p className="text-sm text-slate-500">
            Convide usuarios por e-mail. O convidado recebe um link unico para
            criar conta.
          </p>
        </div>
        {!showForm && (
          <button
            type="button"
            onClick={() => {
              setShowForm(true);
              setSuccess(null);
              setError(null);
            }}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-800"
          >
            Convidar usuario
          </button>
        )}
      </header>

      {success && (
        <p
          role="status"
          aria-live="polite"
          className="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-800"
        >
          {success}
        </p>
      )}

      {showForm && (
        <form
          onSubmit={onSubmitInvite}
          className="space-y-3 rounded-md border border-slate-200 bg-slate-50 p-4"
          noValidate
        >
          <div className="space-y-1.5">
            <label htmlFor="invite-email" className="text-sm font-medium text-slate-700">
              E-mail
            </label>
            <input
              id="invite-email"
              type="email"
              required
              value={formEmail}
              onChange={(e) => setFormEmail(e.target.value)}
              disabled={formSubmitting}
              placeholder="pessoa@empresa.com"
              autoComplete="off"
              spellCheck={false}
              className="w-full rounded-md border border-slate-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
            />
          </div>

          <div className="space-y-1.5">
            <span className="text-sm font-medium text-slate-700">Grupos</span>
            {groups.length === 0 ? (
              <p className="text-xs text-slate-500">
                Nenhum grupo disponivel. O convite sera criado sem grupos.
              </p>
            ) : (
              <ul className="space-y-1 rounded-md border border-slate-200 bg-white p-2 text-sm">
                {groups.map((g) => {
                  const checked = formGroupIds.includes(g.id);
                  return (
                    <li key={g.id}>
                      <label className="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 hover:bg-slate-50">
                        <input
                          type="checkbox"
                          checked={checked}
                          disabled={formSubmitting}
                          onChange={() => toggleGroup(g.id)}
                          className="h-4 w-4"
                        />
                        <span className="font-medium">{g.name}</span>
                        {g.description && (
                          <span className="text-xs text-slate-500">— {g.description}</span>
                        )}
                      </label>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          <div className="space-y-1.5">
            <label
              htmlFor="invite-expires"
              className="text-sm font-medium text-slate-700"
            >
              Expira em (dias)
            </label>
            <input
              id="invite-expires"
              type="number"
              min={1}
              max={30}
              value={formExpiresInDays}
              onChange={(e) => setFormExpiresInDays(Number(e.target.value))}
              disabled={formSubmitting}
              className="w-32 rounded-md border border-slate-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
            />
          </div>

          {formError && (
            <p
              role="alert"
              className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            >
              {formError}
            </p>
          )}

          <div className="flex gap-2">
            <button
              type="submit"
              disabled={formSubmitting}
              className="rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-400"
            >
              {formSubmitting ? "Enviando..." : "Enviar convite"}
            </button>
            <button
              type="button"
              onClick={onCloseForm}
              disabled={formSubmitting}
              className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              Cancelar
            </button>
          </div>
        </form>
      )}

      <div className="flex flex-wrap items-center gap-2 text-sm">
        <label htmlFor="invite-status-filter" className="text-slate-600">
          Filtrar por:
        </label>
        <select
          id="invite-status-filter"
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as InviteStatus | "ALL")}
          className="rounded-md border border-slate-300 px-2 py-1 text-sm focus:border-slate-500 focus:outline-none"
        >
          {STATUS_FILTERS.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </select>
      </div>

      {error && (
        <p
          role="alert"
          className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {error}
        </p>
      )}

      {loading ? (
        <div className="space-y-2" aria-busy="true">
          <div className="h-8 w-full animate-pulse rounded bg-slate-100" />
          <div className="h-8 w-full animate-pulse rounded bg-slate-100" />
          <div className="h-8 w-3/4 animate-pulse rounded bg-slate-100" />
        </div>
      ) : invites.length === 0 ? (
        <p className="text-sm text-slate-500">
          Nenhum convite com status{" "}
          {STATUS_FILTERS.find((f) => f.value === statusFilter)?.label.toLowerCase() ?? statusFilter}.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-200 text-xs uppercase text-slate-500">
              <tr>
                <th className="py-2 pr-3 font-medium">E-mail</th>
                <th className="py-2 pr-3 font-medium">Status</th>
                <th className="py-2 pr-3 font-medium">Convidado em</th>
                <th className="py-2 pr-3 font-medium">Expira em</th>
                <th className="py-2 pr-3 font-medium">Grupos</th>
                <th className="py-2 pr-3 font-medium text-right">Acoes</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {invites.map((inv) => (
                <tr key={inv.id} className="align-top">
                  <td className="py-2 pr-3 font-medium text-slate-800">{inv.email}</td>
                  <td className="py-2 pr-3">
                    <span
                      className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_CLASSES[inv.status]}`}
                    >
                      {STATUS_LABEL[inv.status]}
                    </span>
                  </td>
                  <td className="py-2 pr-3 text-slate-600">{formatDate(inv.invitedAt)}</td>
                  <td className="py-2 pr-3 text-slate-600">{formatDate(inv.expiresAt)}</td>
                  <td className="py-2 pr-3">
                    {inv.groupIds.length === 0 ? (
                      <span className="text-xs text-slate-400">—</span>
                    ) : (
                      <div className="flex flex-wrap gap-1">
                        {inv.groupIds.map((gid) => (
                          <span
                            key={gid}
                            className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700"
                            title={gid}
                          >
                            {groupNameById.get(gid) ?? gid}
                          </span>
                        ))}
                      </div>
                    )}
                  </td>
                  <td className="py-2 pr-3 text-right">
                    {inv.status === "PENDING" ? (
                      <button
                        type="button"
                        onClick={() => void onRevoke(inv)}
                        className="text-xs text-red-600 hover:underline"
                      >
                        Revogar
                      </button>
                    ) : (
                      <span className="text-xs text-slate-400">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
