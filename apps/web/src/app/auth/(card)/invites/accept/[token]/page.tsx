'use client';

/**
 * Public invite acceptance page (US06, ADR 0011).
 *
 * Flow:
 *  1. User clicks a link sent by e-mail: /auth/invites/accept/{token}
 *  2. Form asks for displayName (optional) + password + confirmation.
 *  3. POST /iam/invites/{token}/accept — backend creates the user and returns LoginResponse.
 *  4. Frontend persists the session via setSession() and redirects to /dashboard.
 *
 * Notes:
 *  - The `token` in the URL is the credential. We never log, show or expose it
 *    outside the accept payload.
 *  - Client-side validation mirrors PasswordPolicy.java (min 10, max 128,
 *    >=1 letter, >=1 digit). Backend is the source of truth.
 *  - Error messages are mapped by HTTP status (404/409/410/422/403).
 */

import { useParams, useRouter } from 'next/navigation';
import { useState } from 'react';
import { ApiRequestError, acceptInvite } from '@/lib/api/client';
import { setSession } from '@/lib/auth';
import { PASSWORD_MIN, PASSWORD_MAX } from '@/lib/password-policy';

const DISPLAY_NAME_MAX = 120;

function validatePassword(raw: string): string | null {
  if (raw.length < PASSWORD_MIN || raw.length > PASSWORD_MAX) {
    return `A senha deve ter entre ${PASSWORD_MIN} e ${PASSWORD_MAX} caracteres.`;
  }
  let hasLetter = false;
  let hasDigit = false;
  for (const c of raw) {
    if (/[A-Za-z]/.test(c)) hasLetter = true;
    else if (/[0-9]/.test(c)) hasDigit = true;
    if (hasLetter && hasDigit) break;
  }
  if (!hasLetter || !hasDigit) return 'A senha deve conter letras e numeros.';
  return null;
}

function mapAcceptError(err: unknown): string {
  if (!(err instanceof ApiRequestError)) {
    return 'Erro ao aceitar o convite. Tente novamente.';
  }
  switch (err.status) {
    case 404:
      return 'Convite nao encontrado ou link invalido.';
    case 409:
      return 'Este convite ja foi aceito.';
    case 410:
      return 'Convite expirou. Solicite um novo ao administrador.';
    case 422:
      return err.payload?.message ?? 'Dados invalidos.';
    case 403:
      return 'Acesso negado.';
    default:
      return 'Erro ao aceitar o convite. Tente novamente.';
  }
}

export default function AcceptInvitePage() {
  const params = useParams<{ token: string }>();
  const router = useRouter();
  const token = typeof params?.token === 'string' ? params.token : '';

  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!token) {
      setError('Convite nao encontrado ou link invalido.');
      return;
    }

    const trimmedName = displayName.trim();
    if (trimmedName.length > DISPLAY_NAME_MAX) {
      setError(`O nome deve ter no maximo ${DISPLAY_NAME_MAX} caracteres.`);
      return;
    }

    const passwordError = validatePassword(password);
    if (passwordError) {
      setError(passwordError);
      return;
    }
    if (password !== passwordConfirm) {
      setError('As senhas nao coincidem.');
      return;
    }

    setLoading(true);
    try {
      const resp = await acceptInvite(token, {
        password,
        ...(trimmedName.length > 0 ? { displayName: trimmedName } : {}),
      });
      setSession(
        {
          userId: resp.userId,
          tenantId: resp.tenantId,
          email: resp.email,
          displayName: resp.displayName,
        },
        resp.expiresInSeconds,
      );
      router.push('/dashboard');
      router.refresh();
    } catch (err) {
      setError(mapAcceptError(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <div className="space-y-1">
        <h2 className="text-lg font-medium text-slate-800">Aceitar convite</h2>
        <p className="text-sm text-slate-500">Crie sua senha para acessar o tenant.</p>
      </div>

      <div className="space-y-1.5">
        <label htmlFor="displayName" className="text-sm font-medium text-slate-700">
          Seu nome <span className="text-slate-400">(opcional)</span>
        </label>
        <input
          id="displayName"
          name="displayName"
          type="text"
          value={displayName}
          maxLength={DISPLAY_NAME_MAX}
          onChange={(e) => setDisplayName(e.target.value)}
          disabled={loading}
          autoComplete="name"
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
          placeholder="Como deseja ser chamado"
        />
      </div>

      <div className="space-y-1.5">
        <label htmlFor="password" className="text-sm font-medium text-slate-700">
          Senha
        </label>
        <input
          id="password"
          name="password"
          type="password"
          required
          value={password}
          minLength={PASSWORD_MIN}
          maxLength={PASSWORD_MAX}
          onChange={(e) => setPassword(e.target.value)}
          disabled={loading}
          autoComplete="new-password"
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
          placeholder="Min 10 caracteres, com letras e numeros"
        />
      </div>

      <div className="space-y-1.5">
        <label htmlFor="passwordConfirm" className="text-sm font-medium text-slate-700">
          Confirmar senha
        </label>
        <input
          id="passwordConfirm"
          name="passwordConfirm"
          type="password"
          required
          value={passwordConfirm}
          minLength={PASSWORD_MIN}
          maxLength={PASSWORD_MAX}
          onChange={(e) => setPasswordConfirm(e.target.value)}
          disabled={loading}
          autoComplete="new-password"
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
          placeholder="Repita a senha"
        />
      </div>

      {error && (
        <p
          role="alert"
          className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {error}
        </p>
      )}

      <button
        type="submit"
        disabled={loading}
        className="w-full rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-slate-800 disabled:opacity-50"
      >
        {loading ? 'Aceitando...' : 'Aceitar convite'}
      </button>
    </form>
  );
}
