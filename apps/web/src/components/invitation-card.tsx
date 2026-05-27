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
import {
  Badge,
  Banner,
  Button,
  Card,
  EmptyState,
  Field,
  Input,
  Section,
  Select,
} from "@/components/core/ui";

type BadgeTone = "default" | "success" | "warn" | "danger" | "accent";

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

const STATUS_TONE: Record<InviteStatus, BadgeTone> = {
  PENDING: "warn",
  ACCEPTED: "success",
  EXPIRED: "default",
  REVOKED: "danger",
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

  const thStyle: React.CSSProperties = {
    padding: "8px 12px 8px 0",
    fontSize: 11,
    fontWeight: 600,
    letterSpacing: "0.06em",
    textTransform: "uppercase",
    color: "var(--muted)",
  };
  const tdStyle: React.CSSProperties = {
    padding: "10px 12px 10px 0",
    fontSize: 13,
    color: "var(--muted)",
    verticalAlign: "top",
  };

  return (
    <Section
      title="Convites"
      action={
        !showForm && (
          <Button
            variant="primary"
            size="sm"
            onClick={() => {
              setShowForm(true);
              setSuccess(null);
              setError(null);
            }}
          >
            Convidar usuario
          </Button>
        )
      }
    >
      <Card>
        <p style={{ fontSize: 13, color: "var(--muted)", margin: "0 0 16px", lineHeight: 1.5 }}>
          Convide usuarios por e-mail. O convidado recebe um link unico para criar conta.
        </p>

        {success && (
          <div style={{ marginBottom: 16 }}>
            <Banner tone="ok">{success}</Banner>
          </div>
        )}

        {showForm && (
          <form
            onSubmit={onSubmitInvite}
            noValidate
            style={{
              border: "1px solid var(--border)",
              borderRadius: "var(--radius-sm)",
              background: "var(--canvas)",
              padding: 16,
              marginBottom: 16,
            }}
          >
            <Field label="E-mail" htmlFor="invite-email" required>
              <Input
                id="invite-email"
                type="email"
                required
                value={formEmail}
                onChange={(e) => setFormEmail(e.target.value)}
                disabled={formSubmitting}
                placeholder="pessoa@empresa.com"
                autoComplete="off"
                spellCheck={false}
              />
            </Field>

            <div style={{ marginBottom: 16 }}>
              <span className="nora-label" style={{ display: "block" }}>
                Grupos
              </span>
              {groups.length === 0 ? (
                <p style={{ fontSize: 11.5, color: "var(--muted)", margin: 0 }}>
                  Nenhum grupo disponivel. O convite sera criado sem grupos.
                </p>
              ) : (
                <ul
                  style={{
                    listStyle: "none",
                    margin: 0,
                    padding: 6,
                    display: "flex",
                    flexDirection: "column",
                    gap: 2,
                    border: "1px solid var(--border)",
                    borderRadius: "var(--radius-sm)",
                    background: "var(--surface)",
                  }}
                >
                  {groups.map((g) => {
                    const checked = formGroupIds.includes(g.id);
                    return (
                      <li key={g.id}>
                        <label
                          style={{
                            display: "flex",
                            alignItems: "center",
                            gap: 8,
                            cursor: "pointer",
                            borderRadius: "var(--radius-sm)",
                            padding: "4px 6px",
                            fontSize: 13,
                          }}
                        >
                          <input
                            type="checkbox"
                            checked={checked}
                            disabled={formSubmitting}
                            onChange={() => toggleGroup(g.id)}
                            style={{ width: 16, height: 16, accentColor: "var(--accent)" }}
                          />
                          <span style={{ fontWeight: 500, color: "var(--ink)" }}>{g.name}</span>
                          {g.description && (
                            <span style={{ fontSize: 11.5, color: "var(--muted)" }}>
                              — {g.description}
                            </span>
                          )}
                        </label>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>

            <Field label="Expira em (dias)" htmlFor="invite-expires">
              <Input
                id="invite-expires"
                type="number"
                min={1}
                max={30}
                value={formExpiresInDays}
                onChange={(e) => setFormExpiresInDays(Number(e.target.value))}
                disabled={formSubmitting}
                style={{ width: 128 }}
              />
            </Field>

            {formError && (
              <div style={{ marginBottom: 16 }}>
                <Banner tone="error">{formError}</Banner>
              </div>
            )}

            <div style={{ display: "flex", gap: 8 }}>
              <Button type="submit" variant="primary" disabled={formSubmitting}>
                {formSubmitting ? "Enviando..." : "Enviar convite"}
              </Button>
              <Button type="button" onClick={onCloseForm} disabled={formSubmitting}>
                Cancelar
              </Button>
            </div>
          </form>
        )}

        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            alignItems: "center",
            gap: 8,
            marginBottom: 16,
          }}
        >
          <label
            htmlFor="invite-status-filter"
            style={{ fontSize: 12.5, color: "var(--muted)" }}
          >
            Filtrar por:
          </label>
          <Select
            id="invite-status-filter"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as InviteStatus | "ALL")}
            style={{ width: "auto" }}
          >
            {STATUS_FILTERS.map((f) => (
              <option key={f.value} value={f.value}>
                {f.label}
              </option>
            ))}
          </Select>
        </div>

        {error && (
          <div style={{ marginBottom: 16 }}>
            <Banner tone="error">{error}</Banner>
          </div>
        )}

        {loading ? (
          <div aria-busy="true" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <div style={{ height: 32, borderRadius: 4, background: "var(--chip)" }} />
            <div style={{ height: 32, borderRadius: 4, background: "var(--chip)" }} />
            <div style={{ height: 32, width: "75%", borderRadius: 4, background: "var(--chip)" }} />
          </div>
        ) : invites.length === 0 ? (
          <EmptyState>
            Nenhum convite com status{" "}
            {STATUS_FILTERS.find((f) => f.value === statusFilter)?.label.toLowerCase() ??
              statusFilter}
            .
          </EmptyState>
        ) : (
          <div style={{ overflowX: "auto" }}>
            <table style={{ width: "100%", borderCollapse: "collapse", textAlign: "left" }}>
              <thead>
                <tr style={{ borderBottom: "1px solid var(--border)" }}>
                  <th style={thStyle}>E-mail</th>
                  <th style={thStyle}>Status</th>
                  <th style={thStyle}>Convidado em</th>
                  <th style={thStyle}>Expira em</th>
                  <th style={thStyle}>Grupos</th>
                  <th style={{ ...thStyle, textAlign: "right" }}>Acoes</th>
                </tr>
              </thead>
              <tbody>
                {invites.map((inv) => (
                  <tr key={inv.id} style={{ borderBottom: "1px solid var(--border)" }}>
                    <td style={{ ...tdStyle, fontWeight: 500, color: "var(--ink)" }}>
                      {inv.email}
                    </td>
                    <td style={tdStyle}>
                      <Badge tone={STATUS_TONE[inv.status]}>{STATUS_LABEL[inv.status]}</Badge>
                    </td>
                    <td style={tdStyle}>{formatDate(inv.invitedAt)}</td>
                    <td style={tdStyle}>{formatDate(inv.expiresAt)}</td>
                    <td style={tdStyle}>
                      {inv.groupIds.length === 0 ? (
                        <span style={{ color: "var(--muted)" }}>—</span>
                      ) : (
                        <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                          {inv.groupIds.map((gid) => (
                            <span
                              key={gid}
                              title={gid}
                              style={{
                                fontSize: 11,
                                color: "var(--ink)",
                                background: "var(--chip)",
                                borderRadius: "var(--radius-pill)",
                                padding: "2px 8px",
                              }}
                            >
                              {groupNameById.get(gid) ?? gid}
                            </span>
                          ))}
                        </div>
                      )}
                    </td>
                    <td style={{ ...tdStyle, textAlign: "right" }}>
                      {inv.status === "PENDING" ? (
                        <Button variant="danger" size="sm" onClick={() => void onRevoke(inv)}>
                          Revogar
                        </Button>
                      ) : (
                        <span style={{ color: "var(--muted)" }}>—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </Section>
  );
}
