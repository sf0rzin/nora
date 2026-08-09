import type { Metadata } from "next";
import { DM_Sans } from "next/font/google";

import { AdminShell } from "@/components/admin-shell";
import { checkAccess } from "@/lib/access";
import { getOperator } from "@/lib/operator";
import "./globals.css";

// Single typeface DM Sans (Core design decision, 2026-06-09): no mono font in the console.
const dmSans = DM_Sans({ subsets: ["latin"], variable: "--font-sans", display: "swap" });

export const metadata: Metadata = {
  title: "Nora — Console do Operador",
  description: "Control plane interno do Nora. Acesso restrito a operadores da plataforma.",
  robots: { index: false, follow: false },
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // Tier 2 (ADR 0025): validates the Cloudflare Access JWT before rendering any page.
  // /healthz is a route handler (does not go through layout) — stays free for the Container App probe.
  const access = await checkAccess();
  // Server-side: operator identity (Cloudflare Access in prod, fake in dev).
  const operator = await getOperator();
  return (
    <html lang="pt-BR" className={dmSans.variable}>
      <body>
        {access.enforced && !access.ok ? (
          <main style={{ padding: "4rem 2rem", fontFamily: "var(--font-sans)", maxWidth: "40rem" }}>
            <h1 style={{ fontSize: "1.5rem", marginBottom: "0.5rem" }}>403 — Acesso negado</h1>
            <p style={{ opacity: 0.7 }}>
              Esta requisição não traz uma asserção válida do Cloudflare Access. O console do
              operador só é acessível via <code>admin.nora.systems</code> após login autorizado.
            </p>
          </main>
        ) : (
          <AdminShell operator={operator}>{children}</AdminShell>
        )}
      </body>
    </html>
  );
}
