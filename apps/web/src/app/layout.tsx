import type { Metadata } from "next";
import { DM_Sans, JetBrains_Mono } from "next/font/google";
import "./globals.css";

// Design system v3 (Claude Design rebrand): DM Sans é a única família de UI
// (sans + display). JetBrains Mono fica reservada só para contextos de código
// (ex.: editor de policies). Ver docs/engineering/standards.md §8.
const dmSans = DM_Sans({
  subsets: ["latin"],
  variable: "--font-sans",
  display: "swap",
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: "NORA — Negotiation Observability & Revenue Assistant",
  description:
    "Plataforma de inteligência conversacional que transforma reuniões em decisões, ações e receita — com contexto do seu negócio.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="pt-BR" className={`${dmSans.variable} ${jetbrainsMono.variable}`}>
      <body>{children}</body>
    </html>
  );
}
