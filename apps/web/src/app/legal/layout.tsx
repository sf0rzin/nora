import Link from "next/link";
import { NoraLogo } from "@/components/brand/nora-logo";

export default function LegalLayout({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ minHeight: "100vh", background: "var(--canvas)", color: "var(--ink)" }}>
      <header style={{ borderBottom: "1px solid var(--border)" }}>
        <div
          style={{
            maxWidth: 760,
            margin: "0 auto",
            padding: "18px 24px",
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <Link
            href="/"
            style={{ display: "flex", alignItems: "center", gap: 8, textDecoration: "none" }}
            aria-label="NORA — início"
          >
            <NoraLogo size={22} animate={false} />
          </Link>
          <Link href="/" style={{ fontSize: 13, color: "var(--muted)", textDecoration: "none" }}>
            ← Voltar ao site
          </Link>
        </div>
      </header>
      <main style={{ maxWidth: 760, margin: "0 auto", padding: "48px 24px 96px" }}>{children}</main>
    </div>
  );
}
