import { NoraLogo } from "@/components/brand/nora-logo";
import styles from "./landing.module.css";

/**
 * Footer da landing - 4 colunas (brand + 3 listas de links) + footbase.
 *
 * Logo aparece sem animacao on-mount (animate=false) — ja estamos no final
 * da pagina, a entrada animada do nav cobriu o branding momentaneo.
 */
export function LandingFooter() {
  return (
    <footer className={styles.footer}>
      <div className={styles.shell}>
        <div className={styles.foot}>
          <div className={styles.footBrand}>
            <NoraLogo size={32} animate={false} />
            <p>
              Negotiation Observability &amp; Revenue Assistant. Plataforma de inteligência
              conversacional para reuniões corporativas.
            </p>
          </div>
          <div>
            <h6 className={styles.footH6}>Produto</h6>
            <ul className={styles.footList}>
              <li>
                <a href="#produto">Web</a>
              </li>
              <li>
                <a href="#produto">Desktop</a>
              </li>
              <li>
                <a href="#produto">API · MCP</a>
              </li>
              <li>
                <a href="#">Mudanças</a>
              </li>
            </ul>
          </div>
          <div>
            <h6 className={styles.footH6}>Empresa</h6>
            <ul className={styles.footList}>
              <li>
                <a href="#">Manifesto</a>
              </li>
              <li>
                <a href="#empresa">Segurança</a>
              </li>
              <li>
                <a href="#empresa">LGPD</a>
              </li>
              <li>
                <a href="#">Carreiras</a>
              </li>
            </ul>
          </div>
          <div>
            <h6 className={styles.footH6}>Recursos</h6>
            <ul className={styles.footList}>
              <li>
                <a href="#">Documentação</a>
              </li>
              <li>
                <a href="#">Status</a>
              </li>
              <li>
                <a href="#">Suporte</a>
              </li>
              <li>
                <a href="#">Contato</a>
              </li>
            </ul>
          </div>
        </div>
        <div className={styles.footBase}>
          <span>© 2026 Nora — Projeto FIAP Challenge / Next 2026</span>
          <span>São Paulo · Brasil</span>
        </div>
      </div>
    </footer>
  );
}
