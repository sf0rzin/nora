"use client";

import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import {
  ApiRequestError,
  changePassword,
  deleteAccount,
  getMe,
  getTenant,
  getTenantContext,
  logoutAllSessions,
  renameTenant,
  resendVerificationEmail,
  updateMe,
  upsertTenantContext,
  type MeResponse,
  type TenantContextDto,
  type TenantInfo,
} from "@/lib/api/client";
import {
  clearLocalSession,
  getCurrentUser,
  updateSessionUser,
  type SessionUser,
} from "@/lib/auth";
import { Avatar } from "@/components/core/avatar";

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

// ── seções do hub (sem IAM: Core é individual) ──
const SECTIONS = ["conta", "seguranca", "workspace", "contexto"] as const;
type Section = (typeof SECTIONS)[number];

const SECTION_LABELS: Record<Section, string> = {
  conta: "Conta",
  seguranca: "Segurança",
  workspace: "Workspace",
  contexto: "Contexto da empresa",
};

function CheckIcon() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <polyline points="20 6 9 17 4 12" />
    </svg>
  );
}

// ── força de senha (porte do protótipo §4.3) ──
const STRENGTH_LABELS = ["—", "Fraca", "Razoável", "Boa", "Forte"];
const STRENGTH_COLORS = [
  "var(--chip)",
  "var(--danger)",
  "var(--warn)",
  "var(--accent)",
  "var(--success)",
];

function strengthOf(pw: string): number {
  if (!pw) return 0;
  let s = 0;
  if (pw.length >= 8) s++;
  if (pw.length >= 12) s++;
  if (/[A-Z]/.test(pw) && /[a-z]/.test(pw)) s++;
  if (/\d/.test(pw) && /[^A-Za-z0-9]/.test(pw)) s++;
  return s;
}

export default function TenantContextPage() {
  // ── navegação interna do hub (hash) ──
  const [section, setSection] = useState<Section>("conta");

  useEffect(() => {
    const fromHash = (location.hash || "").slice(1) as Section;
    if (SECTIONS.includes(fromHash)) setSection(fromHash);
  }, []);

  function goTo(next: Section) {
    setSection(next);
    if (typeof history !== "undefined") {
      history.replaceState(null, "", `#${next}`);
    }
  }

  return (
    <div className="set-layout">
      <style>{SET_STYLES}</style>

      <nav className="set-nav" aria-label="Seções de configurações">
        {SECTIONS.map((s) => (
          <a
            key={s}
            href={`#${s}`}
            className={s === section ? "is-active" : undefined}
            aria-current={s === section ? "page" : undefined}
            onClick={(e) => {
              e.preventDefault();
              goTo(s);
            }}
          >
            {SECTION_LABELS[s]}
          </a>
        ))}
      </nav>

      <div className="set-pane">
        {section === "conta" && <AccountSection />}
        {section === "seguranca" && <SecuritySection />}
        {section === "workspace" && <WorkspaceSection />}
        {section === "contexto" && <CompanyContextSection />}
      </div>
    </div>
  );
}

/* ─────────────────────────────────────────────────────────────
   Conta — perfil REAL (GET /auth/me + PATCH /users/me). O cookie
   nora_user dá o fallback instantâneo enquanto o /auth/me carrega;
   o /auth/me é a fonte de verdade (traz emailVerified, que o cookie
   não tem). Salvar atualiza o cookie pra sidebar refletir na hora.
   ───────────────────────────────────────────────────────────── */
function AccountSection() {
  const [me, setMe] = useState<MeResponse | null>(null);
  const [fallback, setFallback] = useState<SessionUser | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [displayName, setDisplayName] = useState("");
  // Não sobrescreve o que o usuário já digitou quando o /auth/me resolve.
  const dirtyRef = useRef(false);

  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [resending, setResending] = useState(false);
  const [resendNote, setResendNote] = useState<string | null>(null);

  useEffect(() => {
    const u = getCurrentUser();
    setFallback(u);
    if (u?.displayName) {
      setDisplayName((prev) => (dirtyRef.current ? prev : u.displayName));
    }
    let cancelled = false;
    getMe()
      .then((m) => {
        if (cancelled) return;
        setMe(m);
        setDisplayName((prev) => (dirtyRef.current ? prev : m.displayName));
      })
      .catch((err) => {
        if (cancelled) return;
        setLoadError(err instanceof ApiRequestError ? err.message : "Falha ao carregar o perfil.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Sucesso discreto: o "Salvo" some sozinho depois de alguns segundos.
  useEffect(() => {
    if (!saved) return;
    const t = setTimeout(() => setSaved(false), 3000);
    return () => clearTimeout(t);
  }, [saved]);

  const email = me?.email ?? fallback?.email ?? "";
  const loading = me === null && fallback === null && loadError === null;

  async function onSave(e: React.FormEvent) {
    e.preventDefault();
    const name = displayName.trim();
    if (!name) {
      setError("O nome de exibição não pode ficar vazio.");
      return;
    }
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const updated = await updateMe({ displayName: name });
      setMe(updated);
      setDisplayName(updated.displayName);
      dirtyRef.current = false;
      // Reflete na sidebar/orb na hora (cookie nora_user + evento).
      updateSessionUser({ displayName: updated.displayName });
      setSaved(true);
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : "Falha ao salvar as alterações.");
    } finally {
      setSaving(false);
    }
  }

  async function onResend() {
    if (!email) return;
    setResending(true);
    setResendNote(null);
    try {
      await resendVerificationEmail(email);
      // 202 sempre (anti-enumeração) — mensagem única em qualquer caso.
      setResendNote(
        "Se este e-mail estiver cadastrado e ainda não verificado, enviamos um novo link. Confira sua caixa de entrada.",
      );
    } catch {
      setResendNote("Não foi possível reenviar agora. Tente de novo em instantes.");
    } finally {
      setResending(false);
    }
  }

  if (loading) {
    return (
      <section className="section">
        <div className="section-head">
          <h2 className="sec-label">Perfil</h2>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <div className="skel" style={{ width: 44, height: 44, borderRadius: "50%" }} />
          <div className="skel" style={{ height: 38, maxWidth: 320 }} />
          <div className="skel" style={{ height: 38, maxWidth: 440 }} />
        </div>
      </section>
    );
  }

  return (
    <section className="section">
      <div className="section-head">
        <h2 className="sec-label">Perfil</h2>
      </div>
      <form onSubmit={onSave} style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
          <Avatar seed={email} size={44} />
          <div style={{ fontSize: 12.5, color: "var(--muted)", lineHeight: 1.5 }}>
            Seu avatar é gerado a partir da conta.
            <br />
            Upload de foto chega depois do piloto.
          </div>
        </div>

        {loadError && <div className="notice notice--danger">{loadError}</div>}

        <div className="field">
          <label className="field-label" htmlFor="acc-name">
            Nome de exibição
          </label>
          <input
            className="input"
            id="acc-name"
            value={displayName}
            onChange={(e) => {
              dirtyRef.current = true;
              setDisplayName(e.target.value);
            }}
            style={{ maxWidth: 320 }}
          />
          <div className="field-help">
            É como a NORA se refere a você nas análises e no chat.
          </div>
        </div>

        <div className="field">
          <label className="field-label" htmlFor="acc-email">
            E-mail
          </label>
          <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
            <input
              className="input"
              id="acc-email"
              value={email}
              disabled
              style={{
                flex: 1,
                minWidth: 280,
                maxWidth: 440,
                color: "var(--muted)",
                background: "var(--sidebar)",
              }}
            />
            {me === null ? (
              <span className="skel" style={{ width: 80, height: 20, borderRadius: 999 }} />
            ) : me.emailVerified ? (
              <span
                className="chip"
                style={{ background: "var(--accent-soft)", color: "var(--success)" }}
              >
                Verificado
              </span>
            ) : (
              <>
                <span className="chip">Não verificado</span>
                <button
                  className="btn btn-ghost btn-sm"
                  type="button"
                  disabled={resending}
                  onClick={onResend}
                >
                  {resending ? "Enviando…" : "Reenviar verificação"}
                </button>
              </>
            )}
          </div>
          {resendNote && (
            <div className="notice notice--accent" style={{ marginTop: 4 }}>
              {resendNote}
            </div>
          )}
          <div className="field-help">
            O e-mail é o identificador da conta e não pode ser trocado no Core.
          </div>
        </div>

        {error && <div className="notice notice--danger">{error}</div>}

        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <button className="btn btn-primary btn-sm" type="submit" disabled={saving}>
            {saving ? "Salvando…" : "Salvar alterações"}
          </button>
          {saved && (
            <span
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                color: "var(--success)",
                fontSize: 12.5,
              }}
            >
              <CheckIcon />
              Salvo
            </span>
          )}
        </div>
      </form>
    </section>
  );
}

/* ─────────────────────────────────────────────────────────────
   Segurança — trocar senha REAL (POST /auth/password/change: revoga
   todas as sessões e reemite cookies pro dispositivo atual) + sair
   de todos os dispositivos (POST /auth/logout-all). O medidor de
   força continua client-side (UX); a policy de verdade é do backend.
   ───────────────────────────────────────────────────────────── */
function SecuritySection() {
  const router = useRouter();
  const [pwCur, setPwCur] = useState("");
  const [pwNew, setPwNew] = useState("");
  const [pwConf, setPwConf] = useState("");
  const [changing, setChanging] = useState(false);
  /** Erro específico do campo "Senha atual" (401 INVALID_CREDENTIALS). */
  const [curError, setCurError] = useState<string | null>(null);
  /** Erro geral (400 policy etc.) — mensagem do backend. */
  const [pwError, setPwError] = useState<string | null>(null);
  const [pwSuccess, setPwSuccess] = useState(false);

  const [logoutConfirm, setLogoutConfirm] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);

  const strength = useMemo(() => strengthOf(pwNew), [pwNew]);
  const mismatch = pwConf.length > 0 && pwConf !== pwNew;
  const canSubmit = pwCur.length > 0 && pwNew.length > 0 && pwNew === pwConf && !changing;

  async function onChangePassword(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setChanging(true);
    setCurError(null);
    setPwError(null);
    setPwSuccess(false);
    try {
      await changePassword({ currentPassword: pwCur, newPassword: pwNew });
      // 204: backend revogou TODAS as sessões mas reemitiu cookies pra cá —
      // este dispositivo continua logado.
      setPwCur("");
      setPwNew("");
      setPwConf("");
      setPwSuccess(true);
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 401) {
        setCurError("Senha atual incorreta.");
      } else if (err instanceof ApiRequestError) {
        setPwError(err.message);
      } else {
        setPwError("Falha ao atualizar a senha.");
      }
    } finally {
      setChanging(false);
    }
  }

  async function onLogoutAll() {
    setLoggingOut(true);
    setLogoutError(null);
    try {
      await logoutAllSessions();
      // Backend já limpou os cookies httpOnly — só resta o estado local.
      clearLocalSession();
      router.replace("/auth/login" as Route);
    } catch (err) {
      setLogoutError(
        err instanceof ApiRequestError ? err.message : "Falha ao encerrar as sessões.",
      );
      setLoggingOut(false);
    }
  }

  return (
    <>
      <section className="section">
        <div className="section-head">
          <h2 className="sec-label">Trocar senha</h2>
        </div>
        <form
          onSubmit={onChangePassword}
          style={{ display: "flex", flexDirection: "column", gap: 16, maxWidth: 360 }}
        >
          <div className="field">
            <label className="field-label" htmlFor="pw-cur">
              Senha atual
            </label>
            <input
              className="input"
              id="pw-cur"
              type="password"
              placeholder="••••••••"
              autoComplete="current-password"
              value={pwCur}
              onChange={(e) => {
                setPwCur(e.target.value);
                if (curError) setCurError(null);
              }}
            />
            {curError && <div className="field-help is-err">{curError}</div>}
          </div>

          <div className="field">
            <label className="field-label" htmlFor="pw-new">
              Nova senha
            </label>
            <input
              className="input"
              id="pw-new"
              type="password"
              placeholder="••••••••"
              autoComplete="new-password"
              value={pwNew}
              onChange={(e) => setPwNew(e.target.value)}
            />
            <div className="pw-bars">
              {[0, 1, 2, 3].map((i) => (
                <span
                  key={i}
                  style={{ background: i < strength ? STRENGTH_COLORS[strength] : "var(--chip)" }}
                />
              ))}
            </div>
            <div className="pw-meta">
              <span>{STRENGTH_LABELS[strength]}</span>
              <span>{pwNew.length} caracteres</span>
            </div>
          </div>

          <div className="field">
            <label className="field-label" htmlFor="pw-conf">
              Confirme a nova senha
            </label>
            <input
              className="input"
              id="pw-conf"
              type="password"
              placeholder="••••••••"
              autoComplete="new-password"
              value={pwConf}
              onChange={(e) => setPwConf(e.target.value)}
            />
            {mismatch && <div className="field-help is-err">As senhas não coincidem.</div>}
          </div>

          {pwError && <div className="notice notice--danger">{pwError}</div>}
          {pwSuccess && (
            <div className="notice notice--accent">
              Senha atualizada. As outras sessões foram encerradas — este dispositivo continua
              conectado.
            </div>
          )}

          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <button className="btn btn-primary btn-sm" type="submit" disabled={!canSubmit}>
              {changing ? "Atualizando…" : "Atualizar senha"}
            </button>
          </div>
        </form>
      </section>

      <section className="section">
        <div className="section-head">
          <h2 className="sec-label">Sessões</h2>
        </div>
        <div
          className="card"
          style={{ display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap" }}
        >
          <div style={{ flex: 1, minWidth: 220 }}>
            <div style={{ fontSize: 13.5, fontWeight: 500 }}>Sair de todos os dispositivos</div>
            <div style={{ fontSize: 12.5, color: "var(--muted)", lineHeight: 1.5, marginTop: 3 }}>
              Encerra todas as sessões ativas, inclusive esta. Você precisará entrar de novo.
            </div>
          </div>
          <button
            className="btn btn-ghost btn-sm"
            type="button"
            disabled={loggingOut}
            onClick={() => setLogoutConfirm(true)}
          >
            Sair de tudo
          </button>
        </div>
        {logoutConfirm && (
          <div className="notice" style={{ marginTop: 10 }}>
            Tem certeza? Todas as sessões serão encerradas agora — inclusive esta.
            <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
              <button
                className="btn btn-primary btn-sm"
                type="button"
                disabled={loggingOut}
                onClick={onLogoutAll}
              >
                {loggingOut ? "Encerrando…" : "Sim, sair de tudo"}
              </button>
              <button
                className="btn btn-ghost btn-sm"
                type="button"
                disabled={loggingOut}
                onClick={() => {
                  setLogoutConfirm(false);
                  setLogoutError(null);
                }}
              >
                Cancelar
              </button>
            </div>
            {logoutError && (
              <div className="field-help is-err" style={{ marginTop: 8 }}>
                {logoutError}
              </div>
            )}
          </div>
        )}
      </section>
    </>
  );
}

/* ─────────────────────────────────────────────────────────────
   Workspace — nome + slug + plano REAIS (GET /tenant + PUT
   /tenant/name) e zona de perigo REAL (DELETE /users/me: apaga
   conta + workspace + todos os dados, LGPD). Typed-confirm do
   e-mail é a 1ª etapa de atrito; a senha confirma de verdade.
   ───────────────────────────────────────────────────────────── */
function formatPlan(plan: string): string {
  const p = plan.trim();
  if (!p) return "Core";
  return p.charAt(0).toUpperCase() + p.slice(1).toLowerCase();
}

function WorkspaceSection() {
  const [tenant, setTenant] = useState<TenantInfo | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [wsName, setWsName] = useState("");
  // Não sobrescreve o que o usuário já digitou quando o GET /tenant resolve.
  const dirtyRef = useRef(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [email, setEmail] = useState("");
  const [delOpen, setDelOpen] = useState(false);
  const [delInput, setDelInput] = useState("");
  const [delPw, setDelPw] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [delError, setDelError] = useState<string | null>(null);

  useEffect(() => {
    setEmail(getCurrentUser()?.email ?? "");
    let cancelled = false;
    getTenant()
      .then((t) => {
        if (cancelled) return;
        setTenant(t);
        setWsName((prev) => (dirtyRef.current ? prev : t.name));
      })
      .catch((err) => {
        if (cancelled) return;
        setLoadError(
          err instanceof ApiRequestError ? err.message : "Falha ao carregar o workspace.",
        );
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Sucesso discreto: o "Salvo" some sozinho depois de alguns segundos.
  useEffect(() => {
    if (!saved) return;
    const t = setTimeout(() => setSaved(false), 3000);
    return () => clearTimeout(t);
  }, [saved]);

  const tenantLoading = tenant === null && loadError === null;
  const delMatches = email.length > 0 && delInput.trim().toLowerCase() === email.toLowerCase();
  const canDelete = delMatches && delPw.length > 0 && !deleting;

  async function onSave(e: React.FormEvent) {
    e.preventDefault();
    const name = wsName.trim();
    if (!name) {
      setError("O nome do workspace não pode ficar vazio.");
      return;
    }
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const updated = await renameTenant({ name });
      setTenant(updated);
      setWsName(updated.name);
      dirtyRef.current = false;
      setSaved(true);
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : "Falha ao renomear o workspace.");
    } finally {
      setSaving(false);
    }
  }

  async function onDelete() {
    if (!canDelete) return;
    setDeleting(true);
    setDelError(null);
    try {
      await deleteAccount({ password: delPw });
      // 204: conta + workspace + dados apagados; backend já limpou os cookies.
      clearLocalSession();
      window.location.assign("/");
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 401) {
        setDelError("Senha incorreta.");
      } else if (err instanceof ApiRequestError) {
        // 409 ACCOUNT_TENANT_SHARED e afins: a message do backend explica.
        setDelError(err.message);
      } else {
        setDelError("Falha ao excluir a conta. Tente de novo.");
      }
      setDeleting(false);
    }
  }

  return (
    <>
      <section className="section">
        <div className="section-head">
          <h2 className="sec-label">Workspace</h2>
        </div>
        <form onSubmit={onSave} style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          {loadError && <div className="notice notice--danger">{loadError}</div>}

          <div className="field">
            <label className="field-label" htmlFor="ws-name">
              Nome do workspace
            </label>
            {tenantLoading ? (
              <div className="skel" style={{ height: 38, maxWidth: 320 }} />
            ) : (
              <input
                className="input"
                id="ws-name"
                value={wsName}
                onChange={(e) => {
                  dirtyRef.current = true;
                  setWsName(e.target.value);
                }}
                style={{ maxWidth: 320 }}
              />
            )}
            <div className="field-help">
              {tenant ? (
                <>
                  Endereço interno (slug): <strong>{tenant.slug}</strong> — fixo, definido no
                  cadastro.
                </>
              ) : (
                "Definido no cadastro — dá pra renomear quando quiser."
              )}
            </div>
          </div>

          <div className="field">
            <span className="field-label">Plano</span>
            {tenantLoading ? (
              <div className="skel" style={{ height: 64, maxWidth: 460, borderRadius: 12 }} />
            ) : (
              <div
                className="card"
                style={{ display: "flex", alignItems: "center", gap: 14, maxWidth: 460 }}
              >
                <span className="plan-badge" style={{ fontSize: 11, padding: "3px 9px" }}>
                  {tenant ? formatPlan(tenant.plan) : "Core"}
                </span>
                <div style={{ flex: 1, fontSize: 12.5, color: "var(--muted)", lineHeight: 1.5 }}>
                  Workspace individual: reuniões, chat e projetos ilimitados pra uma pessoa. Times
                  e permissões são do Enterprise.
                </div>
              </div>
            )}
          </div>

          {error && <div className="notice notice--danger">{error}</div>}

          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <button
              className="btn btn-primary btn-sm"
              type="submit"
              disabled={saving || tenantLoading}
            >
              {saving ? "Salvando…" : "Salvar alterações"}
            </button>
            {saved && (
              <span
                style={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 6,
                  color: "var(--success)",
                  fontSize: 12.5,
                }}
              >
                <CheckIcon />
                Salvo
              </span>
            )}
          </div>
        </form>
      </section>

      <section className="section">
        <div className="section-head">
          <h2 className="sec-label">Zona de perigo</h2>
        </div>
        <div className="danger-card">
          <div style={{ fontSize: 13.5, fontWeight: 500, color: "var(--danger)" }}>
            Excluir conta e todos os dados
          </div>
          <p
            style={{
              fontSize: 12.5,
              color: "var(--muted)",
              lineHeight: 1.55,
              margin: "6px 0 12px",
            }}
          >
            Apaga sua conta, o workspace e TUDO que está nele — reuniões, transcrições, análises,
            action items, sessões de chat e fluxos — definitivamente. É o seu direito pela LGPD
            (direito ao esquecimento) e não tem volta.
          </p>
          {!delOpen ? (
            <button
              className="btn btn-ghost btn-sm"
              type="button"
              style={{ color: "var(--danger)" }}
              onClick={() => setDelOpen(true)}
            >
              Quero excluir minha conta
            </button>
          ) : (
            <div
              style={{
                marginTop: 0,
                paddingTop: 14,
                borderTop: "1px solid var(--border)",
              }}
            >
              <div
                style={{
                  fontSize: 12.5,
                  color: "var(--ink)",
                  lineHeight: 1.55,
                  marginBottom: 10,
                }}
              >
                Para confirmar, digite seu e-mail (<strong>{email || "seu e-mail"}</strong>):
              </div>
              <input
                className="input"
                placeholder="Digite seu e-mail"
                value={delInput}
                onChange={(e) => setDelInput(e.target.value)}
                style={{ width: "100%", maxWidth: 320, marginBottom: 10 }}
              />
              {delMatches && (
                <div className="field" style={{ maxWidth: 320, marginBottom: 10 }}>
                  <label className="field-label" htmlFor="del-pw">
                    Confirme com a sua senha
                  </label>
                  <input
                    className="input"
                    id="del-pw"
                    type="password"
                    placeholder="••••••••"
                    autoComplete="current-password"
                    value={delPw}
                    onChange={(e) => {
                      setDelPw(e.target.value);
                      if (delError) setDelError(null);
                    }}
                  />
                  <div className="field-help is-err">
                    Última etapa: a exclusão é imediata e irreversível.
                  </div>
                </div>
              )}
              {delError && (
                <div className="notice notice--danger" style={{ marginBottom: 10 }}>
                  {delError}
                </div>
              )}
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                <button
                  className="btn btn-danger btn-sm"
                  type="button"
                  disabled={!canDelete}
                  onClick={onDelete}
                >
                  {deleting ? "Excluindo…" : "Excluir definitivamente"}
                </button>
                <button
                  className="btn btn-ghost btn-sm"
                  type="button"
                  disabled={deleting}
                  onClick={() => {
                    setDelOpen(false);
                    setDelInput("");
                    setDelPw("");
                    setDelError(null);
                  }}
                >
                  Cancelar
                </button>
              </div>
            </div>
          )}
        </div>
      </section>
    </>
  );
}

/* ─────────────────────────────────────────────────────────────
   Contexto da empresa — LÓGICA REAL (GET/PUT /tenant/context).
   Apenas re-skin: classes do design system, sem tocar no fluxo
   de dados/API.
   ───────────────────────────────────────────────────────────── */
function CompanyContextSection() {
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
    return (
      <section className="section">
        <div className="section-head">
          <h2 className="sec-label">Contexto da empresa</h2>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <div className="skel" style={{ height: 38, maxWidth: 320 }} />
          <div className="skel" style={{ height: 38 }} />
          <div className="skel" style={{ height: 72 }} />
        </div>
      </section>
    );
  }

  return (
    <section className="section">
      <div className="section-head">
        <h2 className="sec-label">Contexto da empresa</h2>
      </div>
      <p
        style={{
          fontSize: 13,
          color: "var(--muted)",
          lineHeight: 1.6,
          margin: "0 0 18px",
        }}
      >
        Esses dados entram em toda análise de reunião — quanto mais contexto, mais precisa a NORA
        fica sobre riscos, concorrentes e oportunidades.
      </p>

      <form onSubmit={onSubmit} style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <div className="field">
          <label className="field-label" htmlFor="ctx-company">
            Nome da empresa <span className="req">*</span>
          </label>
          <input
            className="input"
            id="ctx-company"
            required
            value={form.companyName}
            onChange={(e) => update("companyName", e.target.value)}
            style={{ maxWidth: 320 }}
          />
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
          <div className="field">
            <label className="field-label" htmlFor="ctx-industry">
              Indústria
            </label>
            <input
              className="input"
              id="ctx-industry"
              value={form.industry}
              onChange={(e) => update("industry", e.target.value)}
              placeholder="ex.: Software B2B"
            />
          </div>
          <div className="field">
            <label className="field-label" htmlFor="ctx-icp">
              ICP
            </label>
            <input
              className="input"
              id="ctx-icp"
              value={form.idealCustomerProfile}
              onChange={(e) => update("idealCustomerProfile", e.target.value)}
              placeholder="ex.: mid-market varejo"
            />
          </div>
        </div>

        <div className="field">
          <label className="field-label" htmlFor="ctx-value">
            Proposta de valor
          </label>
          <textarea
            className="textarea"
            id="ctx-value"
            rows={3}
            value={form.valueProposition}
            onChange={(e) => update("valueProposition", e.target.value)}
          />
        </div>

        <div className="field">
          <label className="field-label" htmlFor="ctx-comp">
            Concorrentes{" "}
            <span style={{ fontWeight: 400, color: "var(--muted)" }}>(um por linha)</span>
          </label>
          <textarea
            className="textarea"
            id="ctx-comp"
            rows={3}
            value={form.competitors}
            onChange={(e) => update("competitors", e.target.value)}
          />
        </div>

        <div className="field">
          <label className="field-label" htmlFor="ctx-obj">
            Objection handling{" "}
            <span style={{ fontWeight: 400, color: "var(--muted)" }}>(um por linha)</span>
          </label>
          <textarea
            className="textarea"
            id="ctx-obj"
            rows={3}
            value={form.objectionHandling}
            onChange={(e) => update("objectionHandling", e.target.value)}
          />
        </div>

        <div className="field">
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <span className="field-label">Produtos</span>
            <button className="btn btn-ghost btn-sm" type="button" onClick={addProduct}>
              + Adicionar produto
            </button>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            {form.products.length === 0 ? (
              <p style={{ fontSize: 13, color: "var(--muted)", margin: 0 }}>
                Nenhum produto cadastrado.
              </p>
            ) : (
              form.products.map((p, i) => (
                <div
                  key={i}
                  className="card"
                  style={{ display: "flex", flexDirection: "column", gap: 8 }}
                >
                  <div style={{ display: "flex", gap: 8 }}>
                    <input
                      className="input"
                      value={p.name}
                      onChange={(e) => updateProduct(i, { name: e.target.value })}
                      placeholder="Nome do produto"
                      style={{ flex: 1 }}
                    />
                    <button
                      className="btn btn-ghost btn-sm"
                      type="button"
                      onClick={() => removeProduct(i)}
                      style={{ color: "var(--danger)" }}
                    >
                      Remover
                    </button>
                  </div>
                  <textarea
                    className="textarea"
                    rows={2}
                    value={p.description}
                    onChange={(e) => updateProduct(i, { description: e.target.value })}
                    placeholder="Descrição"
                  />
                  <textarea
                    className="textarea"
                    rows={2}
                    value={p.keyDifferentiators}
                    onChange={(e) => updateProduct(i, { keyDifferentiators: e.target.value })}
                    placeholder="Diferenciais (um por linha)"
                  />
                </div>
              ))
            )}
          </div>
        </div>

        {error && <div className="notice notice--danger">{error}</div>}
        {message && (
          <span
            className="saved-note is-show"
            style={{ display: "inline-flex", alignItems: "center", gap: 6, color: "var(--success)" }}
          >
            <CheckIcon />
            {message}
          </span>
        )}

        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <button className="btn btn-primary btn-sm" type="submit" disabled={saving}>
            {saving ? "Salvando…" : "Salvar contexto"}
          </button>
        </div>
      </form>
    </section>
  );
}

/* ── estilos exclusivos do hub (não há classe em components.css) ── */
const SET_STYLES = `
.set-layout { display: flex; gap: 40px; align-items: flex-start; }
.set-nav {
  width: 180px; flex-shrink: 0; display: flex; flex-direction: column; gap: 1px;
  position: sticky; top: 24px;
}
.set-nav a {
  padding: 7px 10px; border-radius: 7px; font-size: 13px; color: var(--muted);
  transition: background 120ms ease, color 120ms ease; cursor: pointer;
}
.set-nav a:hover { background: var(--sidebar); color: var(--ink); }
.set-nav a.is-active { background: var(--accent-soft); color: var(--accent-ink); font-weight: 500; }
.set-pane { flex: 1; min-width: 0; max-width: 560px; }
@media (max-width: 860px) {
  .set-layout { flex-direction: column; gap: 20px; }
  .set-nav { width: 100%; flex-direction: row; flex-wrap: wrap; gap: 4px; position: static; }
  .set-nav a { border: 1px solid var(--border); border-radius: 999px; padding: 6px 13px; font-size: 12.5px; }
  .set-nav a.is-active { border-color: transparent; }
}
.pw-bars { display: flex; gap: 4px; margin-top: 8px; }
.pw-bars span { height: 3px; flex: 1; border-radius: 2px; background: var(--chip); transition: background 160ms ease; }
.pw-meta { display: flex; justify-content: space-between; font-size: 11px; color: var(--muted); margin-top: 5px; }
.danger-card { border: 1px solid var(--danger); border-radius: 12px; padding: 16px 18px; background: var(--chip); }
`;
