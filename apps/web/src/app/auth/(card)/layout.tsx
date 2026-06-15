import Link from "next/link";
import { NoraLogo } from "@/components/brand/nora-logo";

/**
 * Card chrome compartilhado pelas telas de auth "pequenas" (verify-email,
 * reset de senha, aceite de convite). Login e signup NÃO usam este layout —
 * são full-screen (componente AuthScreen), via route group à parte.
 */
export default function AuthCardLayout({ children }: { children: React.ReactNode }) {
  return (
    <main
      style={{
        minHeight: "100dvh",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: 28,
        background: "var(--sidebar)",
        padding: "40px 20px",
      }}
    >
      <Link href="/auth/login" aria-label="Nora">
        <NoraLogo size={24} animate={false} />
      </Link>

      <div
        style={{
          width: "100%",
          maxWidth: 400,
          background: "var(--canvas)",
          border: "1px solid var(--border)",
          borderRadius: 14,
          padding: 28,
          boxShadow: "0 12px 32px -18px rgba(15, 23, 42, 0.12)",
        }}
      >
        {children}
      </div>

      <footer
        style={{
          fontSize: 12,
          color: "var(--muted)",
          display: "flex",
          gap: 18,
        }}
      >
        <span>© 2026 Nora</span>
        <a href="#" style={{ color: "var(--muted)" }}>
          Privacidade
        </a>
        <a href="#" style={{ color: "var(--muted)" }}>
          Termos
        </a>
        <a href="#" style={{ color: "var(--muted)" }}>
          Status
        </a>
      </footer>
    </main>
  );
}
