'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import type { Route } from 'next';
import { Suspense, useState } from 'react';
import { login, ApiRequestError } from '@/lib/api/client';
import { setSession } from '@/lib/auth';
import { NoraLogo } from '@/components/brand/nora-logo';
import { OrbAnimation } from '@/components/login/orb-animation';
import { SoundwaveBars } from '@/components/login/soundwave-bars';
import styles from './login.module.css';

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}

/**
 * Aceita apenas paths internos (`/x...`) e rejeita protocol-relative (`//evil.com`)
 * e URLs absolutas. `as Route` é compile-time only — sem validação runtime, atacante
 * monta `?next=https://evil.com` e router.replace redireciona pra fora do app.
 */
function safeNextPath(raw: string | null): Route {
  if (!raw) return '/dashboard' as Route;
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) {
    return '/dashboard' as Route;
  }
  return raw as Route;
}

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const next = safeNextPath(params.get('next'));
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const r = await login(email, password);
      setSession(
        { userId: r.userId, tenantId: r.tenantId, email: r.email, displayName: r.displayName },
        r.expiresInSeconds,
      );
      router.replace(next);
      router.refresh();
    } catch (err) {
      const msg = err instanceof ApiRequestError ? err.message : 'Falha ao entrar';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className={styles.shell}>
      {/* ── LEFT: art panel (paleta clara editorial) ── */}
      <aside className={styles.art}>
        <div className={styles.artGrid} aria-hidden="true" />

        <div className={styles.artLogo}>
          <NoraLogo size={28} variant="brand" />
        </div>

        <OrbAnimation>
          <SoundwaveBars />
        </OrbAnimation>

        <div className={styles.artFoot}>
          <span>Inteligência conversacional</span>
          <span>v.1</span>
        </div>
      </aside>

      {/* ── RIGHT: form ── */}
      <main className={styles.formWrap}>
        <form onSubmit={onSubmit} className={styles.form}>
          <Link href="/" className={styles.back}>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
              <path
                d="M11 7H3M7 3L3 7l4 4"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            Voltar
          </Link>

          <div className={styles.head}>
            <h1>Bem-vindo de volta</h1>
            <p>Entre na sua conta para continuar.</p>
          </div>

          <div className={styles.sso}>
            <button
              type="button"
              disabled
              aria-disabled="true"
              title="Em breve — SSO corporativo (Entra ID/SAML) chega pós-MVP"
              className={`${styles.ssoBtn} ${styles.ssoBtnDisabled}`}
            >
              <svg
                className={styles.ssoIcon}
                viewBox="0 0 23 23"
                xmlns="http://www.w3.org/2000/svg"
                aria-hidden="true"
              >
                <rect x="1" y="1" width="10" height="10" fill="#F25022" />
                <rect x="12" y="1" width="10" height="10" fill="#7FBA00" />
                <rect x="1" y="12" width="10" height="10" fill="#00A4EF" />
                <rect x="12" y="12" width="10" height="10" fill="#FFB900" />
              </svg>
              Continuar com Microsoft
              <span className={styles.ssoBadge}>Em breve</span>
            </button>
          </div>

          <div className={styles.divider}>ou</div>

          <div className={styles.fields}>
            <div className={styles.field}>
              <label className={styles.fieldLabel} htmlFor="login-email">
                E-mail
              </label>
              <div className={styles.fieldInputWrap}>
                <input
                  id="login-email"
                  className={styles.fieldInput}
                  type="email"
                  autoComplete="email"
                  placeholder="você@empresa.com"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
              </div>
            </div>

            <div className={styles.field}>
              <label className={styles.fieldLabel} htmlFor="login-password">
                <span>Senha</span>
                <Link href="/auth/password/reset/request">Esqueci minha senha</Link>
              </label>
              <div className={styles.fieldInputWrap}>
                <input
                  id="login-password"
                  className={`${styles.fieldInput} ${styles.fieldInputWithIcon}`}
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  placeholder="••••••••"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
                <button
                  type="button"
                  className={styles.fieldToggle}
                  aria-label={showPassword ? 'Ocultar senha' : 'Mostrar senha'}
                  aria-pressed={showPassword}
                  onClick={() => setShowPassword((v) => !v)}
                >
                  {showPassword ? (
                    <svg
                      width="16"
                      height="16"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.7"
                      aria-hidden="true"
                    >
                      <path d="M3 3l18 18" strokeLinecap="round" />
                      <path d="M10.6 6.2A11 11 0 0 1 12 6c6.5 0 10 6 10 6a16 16 0 0 1-3 3.4M6.1 6.1A16 16 0 0 0 2 12s3.5 6 10 6a11 11 0 0 0 4.6-1" />
                      <path d="M14.1 14.1A3 3 0 0 1 9.9 9.9" />
                    </svg>
                  ) : (
                    <svg
                      width="16"
                      height="16"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.7"
                      aria-hidden="true"
                    >
                      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
                      <circle cx="12" cy="12" r="3" />
                    </svg>
                  )}
                </button>
              </div>
            </div>
          </div>

          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}

          <button type="submit" disabled={loading} className={styles.submit}>
            {loading ? 'Entrando…' : 'Entrar'}
            {!loading && (
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
                <path
                  d="M3 7h8M7 3l4 4-4 4"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            )}
          </button>

          <div className={styles.foot}>
            <span>
              Não tem conta? <Link href="/auth/signup">Falar com vendas</Link>
            </span>
            <span className={styles.footMeta}>SSO · SAML</span>
          </div>
        </form>
      </main>
    </div>
  );
}
