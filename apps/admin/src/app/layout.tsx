import type { Metadata } from "next";
import { DM_Sans, JetBrains_Mono } from "next/font/google";

import { AdminShell } from "@/components/admin-shell";
import { checkAccess } from "@/lib/access";
import { getOperator } from "@/lib/operator";
import "./globals.css";

const dmSans = DM_Sans({ subsets: ["latin"], variable: "--font-sans", display: "swap" });
const jetbrainsMono = JetBrains_Mono({ subsets: ["latin"], variable: "--font-mono", display: "swap" });

export const metadata: Metadata = {
  title: "NORA — Console do Operador",
  description: "Control plane interno do NORA. Acesso restrito a operadores da plataforma.",
  robots: { index: false, follow: false },
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // Tier 2 (ADR 0025): valida o JWT do Cloudflare Access antes de renderizar qualquer página.
  // /healthz é route handler (não passa por layout) — segue livre pro probe do Container App.
  const access = await checkAccess();
  // Server-side: identidade do operador (Cloudflare Access em prod, fake em dev).
  const operator = getOperator();
  return (
    <html lang="pt-BR" className={`${dmSans.variable} ${jetbrainsMono.variable}`}>
      <body>
        {access.enforced && !access.ok ? (
          <main style={{ padding: "4rem 2rem", fontFamily: "var(--font-mono)", maxWidth: "40rem" }}>
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
