import type { Metadata } from "next";
import { LandingPage } from "@/components/landing/landing-page";

export const metadata: Metadata = {
  title: "NORA — Conversas viram inteligência",
  description:
    "A NORA escuta, transcreve e extrai oportunidades, riscos e próximos passos das suas reuniões — calibrada ao catálogo da sua empresa, não a um genérico.",
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
