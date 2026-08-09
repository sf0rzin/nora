/**
 * Pass-through: the auth routes no longer share chrome here.
 * - login/signup → full-screen (AuthScreen).
 * - verify-email/reset/invites → card via route group `(card)/layout.tsx`.
 */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return children;
}
