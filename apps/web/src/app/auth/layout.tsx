/**
 * Pass-through: as rotas de auth não compartilham mais chrome aqui.
 * - login/signup → full-screen (AuthScreen).
 * - verify-email/reset/invites → card via route group `(card)/layout.tsx`.
 */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return children;
}
