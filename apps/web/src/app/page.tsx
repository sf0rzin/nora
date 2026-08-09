import type { Metadata } from "next";
import { LandingPage } from "@/components/landing/landing-page";

export const metadata: Metadata = {
  title: "Nora — Conversas viram inteligência",
  description:
    "A Nora escuta, transcreve e extrai oportunidades, riscos e próximos passos das suas reuniões — calibrada ao catálogo da sua empresa, não a um genérico.",
};

/**
 * Root route / public landing.
 *
 * Unauthenticated visitors see the landing. Logged-in users are
 * redirected to /dashboard by the middleware before getting here.
 */
export default function HomePage() {
  return <LandingPage />;
}
