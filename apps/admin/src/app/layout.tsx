import type { Metadata } from "next";
import { DM_Sans, JetBrains_Mono } from "next/font/google";

import { AdminShell } from "@/components/admin-shell";
import { getOperator } from "@/lib/operator";
import "./globals.css";

const dmSans = DM_Sans({ subsets: ["latin"], variable: "--font-sans", display: "swap" });
const jetbrainsMono = JetBrains_Mono({ subsets: ["latin"], variable: "--font-mono", display: "swap" });

export const metadata: Metadata = {
  title: "NORA — Console do Operador",
  description: "Control plane interno do NORA. Acesso restrito a operadores da plataforma.",
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // Server-side: lê a identidade do operador injetada pelo Easy Auth (Entra).
  const operator = getOperator();
  return (
    <html lang="pt-BR" className={`${dmSans.variable} ${jetbrainsMono.variable}`}>
      <body>
        <AdminShell operator={operator}>{children}</AdminShell>
      </body>
    </html>
  );
}
