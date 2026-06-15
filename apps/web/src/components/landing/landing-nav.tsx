import Link from "next/link";

import { NoraLogo } from "@/components/brand/nora-logo";

const NAV_LINKS = [
  { href: "#produto", label: "Produto" },
  { href: "#como-funciona", label: "Como funciona" },
  { href: "#privacidade", label: "Privacidade" },
  { href: "#planos", label: "Planos" },
  { href: "#faq", label: "FAQ" },
];

/** Top nav sticky (blur via CSS). Logo + âncoras + Entrar/Começar grátis. */
export function LandingNav() {
  return (
    <nav className="nav">
      <div className="nav-inner">
        <div className="nav-left">
          <Link href="/" aria-label="Nora — início" style={{ display: "inline-flex" }}>
            <NoraLogo size={22} />
          </Link>
          <div className="nav-links">
            {NAV_LINKS.map((l) => (
              <a key={l.href} href={l.href}>
                {l.label}
              </a>
            ))}
          </div>
        </div>
        <div className="nav-right">
          <Link href="/auth/login" className="btn btn-ghost">
            Entrar
          </Link>
          <Link href="/auth/signup" className="btn btn-primary">
            Começar grátis
          </Link>
        </div>
      </div>
    </nav>
  );
}
