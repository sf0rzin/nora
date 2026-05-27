"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import {
  ApiRequestError,
  getMe,
  requestPasswordReset,
  type MeResponse,
} from "@/lib/api/client";
import { clearSession } from "@/lib/auth";
import {
  Badge,
  Banner,
  Button,
  Card,
  EmptyState,
  PageHeader,
  Section,
} from "@/components/core/ui";

export default function AccountPage() {
  const router = useRouter();
  const [me, setMe] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [resetSent, setResetSent] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [resetError, setResetError] = useState<string | null>(null);

  const [signingOut, setSigningOut] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    getMe()
      .then((m) => {
        if (active) setMe(m);
      })
      .catch((e) => {
        if (!active) return;
        if (e instanceof ApiRequestError) setError(e.message);
        else setError("Falha ao carregar sua conta.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  async function handlePasswordReset() {
    if (!me || resetting || resetSent) return;
    setResetting(true);
    setResetError(null);
    try {
      await requestPasswordReset(me.email);
      setResetSent(true);
    } catch (e) {
      if (e instanceof ApiRequestError) setResetError(e.message);
      else setResetError("Não foi possível enviar o link agora. Tente de novo.");
    } finally {
      setResetting(false);
    }
  }

  async function handleSignOut() {
    if (signingOut) return;
    setSigningOut(true);
    await clearSession();
    router.replace("/auth/login");
    router.refresh();
  }

  const planLabel = me ? (me.plan === "ENTERPRISE" ? "Enterprise" : "Core") : "";

  return (
    <div>
      <PageHeader
        title="Conta"
        subtitle="Seu perfil, segurança e privacidade na NORA."
      />

      {error && (
        <div style={{ marginBottom: 16 }}>
          <Banner tone="error">{error}</Banner>
        </div>
      )}

      {loading ? (
        <EmptyState>Carregando…</EmptyState>
      ) : !me ? (
        <EmptyState>Não foi possível carregar sua conta.</EmptyState>
      ) : (
        <>
          <Section title="Perfil">
            <Card>
              <dl style={{ display: "flex", flexDirection: "column", gap: 14, margin: 0 }}>
                <ProfileRow label="Nome">
                  <span style={{ fontSize: 14, color: "var(--ink)" }}>{me.displayName}</span>
                </ProfileRow>
                <ProfileRow label="E-mail">
                  <span
                    style={{
                      display: "inline-flex",
                      alignItems: "center",
                      gap: 8,
                      fontSize: 14,
                      color: "var(--ink)",
                    }}
                  >
                    {me.email}
                    {me.emailVerified && <Badge tone="success">verificado</Badge>}
                  </span>
                </ProfileRow>
                <ProfileRow label="Workspace">
                  <span style={{ fontSize: 14, color: "var(--ink)" }}>{me.tenantName}</span>
                </ProfileRow>
                <ProfileRow label="Plano">
                  <Badge tone="accent">{planLabel}</Badge>
                </ProfileRow>
              </dl>
            </Card>
          </Section>

          <Section title="Segurança">
            <Card>
              <p style={{ fontSize: 13.5, color: "var(--muted)", margin: "0 0 14px", lineHeight: 1.5 }}>
                Para trocar sua senha, enviamos um link de redefinição pro seu e-mail.
                Você não precisa da senha atual.
              </p>
              {resetError && (
                <div style={{ marginBottom: 12 }}>
                  <Banner tone="error">{resetError}</Banner>
                </div>
              )}
              {resetSent ? (
                <Banner tone="ok">Enviamos um link de redefinição pro seu e-mail.</Banner>
              ) : (
                <Button
                  variant="primary"
                  size="sm"
                  onClick={handlePasswordReset}
                  disabled={resetting}
                >
                  {resetting ? "Enviando…" : "Trocar senha"}
                </Button>
              )}
            </Card>
          </Section>

          <Section title="Privacidade">
            <Card>
              <p style={{ fontSize: 13.5, color: "var(--ink)", margin: "0 0 12px", lineHeight: 1.5 }}>
                PII Shield ativo — dados sensíveis são redigidos antes de qualquer IA.
              </p>
              <div style={{ display: "flex", flexWrap: "wrap", gap: 16, fontSize: 13 }}>
                <Link
                  href={"/legal/privacidade" as Route}
                  style={{ color: "var(--accent-ink)", textDecoration: "none" }}
                >
                  Política de privacidade
                </Link>
                <Link
                  href={"/legal/termos" as Route}
                  style={{ color: "var(--accent-ink)", textDecoration: "none" }}
                >
                  Termos de uso
                </Link>
              </div>
            </Card>
          </Section>

          <Section title="Sessão">
            <Card>
              <p style={{ fontSize: 13.5, color: "var(--muted)", margin: "0 0 14px", lineHeight: 1.5 }}>
                Encerrar a sessão neste dispositivo.
              </p>
              <Button
                variant="danger"
                size="sm"
                onClick={handleSignOut}
                disabled={signingOut}
              >
                {signingOut ? "Saindo…" : "Sair"}
              </Button>
            </Card>
          </Section>
        </>
      )}
    </div>
  );
}

function ProfileRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div
      style={{
        display: "flex",
        flexWrap: "wrap",
        alignItems: "baseline",
        gap: 8,
      }}
    >
      <dt
        style={{
          width: 110,
          flexShrink: 0,
          fontSize: 12,
          color: "var(--muted)",
          letterSpacing: "0.01em",
        }}
      >
        {label}
      </dt>
      <dd style={{ margin: 0, minWidth: 0 }}>{children}</dd>
    </div>
  );
}
