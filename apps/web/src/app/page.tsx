import type { Metadata } from "next";
import { LandingPage } from "@/components/landing/landing-page";

export const metadata: Metadata = {
  title: "NORA — Conversas viram inteligência",
  description:
    "Plataforma de inteligência conversacional que transforma reuniões em decisões, ações e receita — com contexto do seu negócio.",
};

/**
 * Rota raiz / landing publica.
 *
 * Visitantes nao autenticados veem a landing. Usuarios logados sao
 * redirecionados pra /dashboard pelo middleware antes de chegar aqui.
 */
export default function HomePage() {
  return <LandingPage />;
}
