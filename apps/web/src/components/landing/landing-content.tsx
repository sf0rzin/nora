import Link from "next/link";

import { NoraLogo } from "@/components/brand/nora-logo";
import { ShaderOrb } from "@/components/brand/shader-orb";

function Check({ size = 14, strokeWidth = 2.4 }: { size?: number; strokeWidth?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <polyline points="20 6 9 17 4 12" />
    </svg>
  );
}

/**
 * Marks a line as planned rather than delivered. The page keeps the product
 * narrative (issue #456, option 2), so anything that is not built yet has to be
 * legible as roadmap at a glance instead of reading as a shipped feature.
 */
function RoadmapTag() {
  return <span className="roadmap-tag">Roadmap</span>;
}

// ── How it works ──
export function LandingHowItWorks() {
  return (
    <section id="como-funciona">
      <div className="container">
        <div style={{ maxWidth: 720 }}>
          <div className="section-label">Como funciona</div>
          <h2 className="section-title">Da transcrição ao action item, sem fricção.</h2>
          <p className="section-subtitle">
            Três passos. Aceita texto, áudio capturado em tempo real no Desktop, ou arquivo do seu
            app de videoconferência.
          </p>
        </div>

        <div className="steps-grid">
          <div className="step-card">
            <span className="step-num">01 · Suba</span>
            <h3>Transcrição em qualquer formato.</h3>
            <p>
              .txt, .vtt, .srt — ou capture áudio do sistema no app Desktop (Windows, macOS, Linux).
            </p>
            <div className="step-visual">
              <div className="mock-file">
                <div className="mock-file-icon">
                  <svg
                    width="14"
                    height="14"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                    <polyline points="14 2 14 8 20 8" />
                  </svg>
                </div>
                <span className="mock-file-name">discovery-totvs-14mai.vtt</span>
                <span className="mock-file-size">42 KB</span>
              </div>
            </div>
          </div>

          <div className="step-card">
            <span className="step-num">02 · Nora processa</span>
            <h3>Análise estruturada em 30s.</h3>
            <p>
              PII Shield redige dados pessoais. LLM extrai resumo, decisões e tasks com confiança
              calibrada.
            </p>
            <div className="step-visual">
              <div className="mock-orb-row">
                <div className="mock-orb-pulse">
                  <ShaderOrb size={36} speed={1.6} intensity={1} />
                </div>
                <div>
                  <div className="text">Detectando action items…</div>
                  <div className="sub">3 de 4 estágios · 78%</div>
                </div>
              </div>
            </div>
          </div>

          <div className="step-card">
            <span className="step-num">03 · Você recebe</span>
            <h3>Resumo navegável + integração.</h3>
            <p>
              Resumo, decisões, tasks com prioridade. Empurre pra Linear, GitHub ou Calendar pelos
              conectores OAuth.
            </p>
            <div className="step-visual">
              <div className="mock-summary">
                <h5>Resumo</h5>
                <div className="mock-line w90" />
                <div className="mock-line w70" />
                <div className="mock-line w50" style={{ marginBottom: 0 }} />
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

// ── Privacy ──
const PROMISES = [
  {
    title: "Infraestrutura própria",
    body: "Multi-tenancy com isolamento por tenant_id em toda consulta ao banco. Os dados de uma organização não são visíveis para outra.",
    icon: (
      <svg
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <circle cx="12" cy="12" r="9" />
        <path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" />
      </svg>
    ),
  },
  {
    title: "Nunca treinam modelos",
    body: "Suas reuniões não viram fine-tune de ninguém. Antes de qualquer chamada a um LLM externo, o PII Shield redige CPF, CNPJ, e-mail, telefone, cartão e nomes.",
    icon: (
      <svg
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <circle cx="12" cy="12" r="9" />
        <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
      </svg>
    ),
  },
  {
    title: "Direito ao esquecimento",
    body: "Apagar uma reunião é definitivo: transcrição, participantes, tags e análises somem junto, sem trâmite e sem ligação. LGPD Art. 18.",
    icon: (
      <svg
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M3 6h18" />
        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
        <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
      </svg>
    ),
  },
  {
    title: "Trilha de auditoria",
    body: "Login, cadastro, troca de senha, upload e reprocessamento de reunião ficam registrados com autor, alvo e horário — visíveis na tela de IAM (ADR 0007).",
    icon: (
      <svg
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M9 11l3 3L22 4" />
        <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
      </svg>
    ),
  },
];

export function LandingPrivacy() {
  return (
    <section id="privacidade" className="privacy">
      <div className="container">
        <div className="privacy-head">
          <div className="section-label">Privacidade · LGPD-first</div>
          <h2 className="section-title">
            Sua privacidade não é uma feature.
            <br />É o ponto de partida.
          </h2>
          <p className="section-subtitle">
            Não é compliance teatro: o PII Shield, a retenção, o audit log e o direito ao
            esquecimento existem antes do produto fazer qualquer coisa. ADRs públicos, decisões
            registradas, código auditável.
          </p>
        </div>

        <div className="privacy-grid">
          {PROMISES.map((p) => (
            <div className="privacy-card" key={p.title}>
              <div className="privacy-card-icon">{p.icon}</div>
              <h3>{p.title}</h3>
              <p>{p.body}</p>
            </div>
          ))}
        </div>

        {/* Only numbers the code backs, and none that goes stale on its own — the ADR
            count and the audio-retention window were both fiction. */}
        <div className="privacy-strip">
          <div className="privacy-stat">
            <span className="privacy-stat-num">0</span>
            <span className="privacy-stat-lbl">áudio guardado no servidor</span>
          </div>
          <div className="privacy-stat">
            <span className="privacy-stat-num">0</span>
            <span className="privacy-stat-lbl">dados de cliente em treinamento</span>
          </div>
          <div className="privacy-stat">
            <span className="privacy-stat-num">RLS</span>
            <span className="privacy-stat-lbl">isolamento por tenant aplicado no banco</span>
          </div>
          <div className="privacy-stat">
            <span className="privacy-stat-num">100%</span>
            <span className="privacy-stat-lbl">PT-BR, LGPD nativo</span>
          </div>
        </div>
      </div>
    </section>
  );
}

// ── Pricing ──
// No quota is enforced anywhere in the API, so the plan list stops quoting one.
const CORE_FEATURES = [
  "Resumo + decisões + action items",
  "Productivity Score opt-in",
  "PII Shield pessoal completo",
  "Reuniões e projetos sem limite de cota",
  "Integrações OAuth: Calendar, Linear, GitHub, Notion, Slack, Trello, Todoist, Telegram",
  "Servidor MCP somente leitura pros seus agentes",
  "Desktop app (Windows, macOS, Linux)",
];

export function LandingPricing() {
  return (
    <section id="planos" className="tight">
      <div className="container">
        <div style={{ maxWidth: 720 }}>
          <div className="section-label">Planos</div>
          <h2 className="section-title">Core grátis. Enterprise quando o time chegar.</h2>
          <p className="section-subtitle">
            Adoção PLG: começa pelo indivíduo, escala pra empresa. Sem armadilha de upgrade.
          </p>
        </div>

        <div className="pricing-grid">
          <div className="pricing-card">
            <div className="pricing-head">
              <span className="badge">Core</span>
              <h3>Copiloto pessoal</h3>
              <p>Pra profissionais individuais que vivem em reuniões.</p>
            </div>
            <div className="pricing-price">
              <span className="num">Grátis</span>
              <span className="unit">pra sempre</span>
            </div>
            <ul className="pricing-features">
              {CORE_FEATURES.map((f) => (
                <li key={f}>
                  <Check />
                  {f}
                </li>
              ))}
            </ul>
            <Link
              href="/auth/signup"
              className="btn btn-ghost btn-lg"
              style={{ justifyContent: "center" }}
            >
              Começar grátis
            </Link>
          </div>

          <div className="pricing-card highlighted">
            <div className="pricing-head">
              <span className="badge ent">Enterprise</span>
              <RoadmapTag />
              <h3>Motor de receita pra equipes</h3>
              <p>Pra times comerciais e empresas que vivem em conversas com cliente.</p>
            </div>
            <div className="pricing-price">
              <span className="num">A definir</span>
              <span className="unit">a Nora ainda não é comercializada</span>
            </div>
            <ul className="pricing-features">
              <li>
                <Check />
                Tudo do Core
              </li>
              <li>
                <Check />
                <span>
                  <strong>Product Context</strong> — catálogo de produtos, concorrentes, ICP e
                  objeções
                </span>
              </li>
              <li>
                <Check />
                <span>
                  <strong>Customer Confidence</strong> — score de saúde por conta
                </span>
              </li>
              <li>
                <Check />
                <span>
                  <strong>IAM granular</strong> estilo AWS · Groups, Policies, Conditions
                </span>
              </li>
              <li>
                <Check />
                Multi-tenancy isolado por tenant_id, com RLS no Postgres
              </li>
              <li>
                <Check />
                <span>
                  Glossário do workspace via RAG <RoadmapTag />
                </span>
              </li>
            </ul>
            {/* No sales channel exists, so the card carries a note instead of a
                button that only scrolls the page back to the signup section. */}
            <p className="pricing-note">
              Sem canal comercial por enquanto: o plano Enterprise é a direção do produto, não uma
              assinatura que dá pra fechar hoje.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}

// ── FAQ ──
const FAQS = [
  {
    q: "Nora grava minhas reuniões?",
    a: "Não. A Nora processa transcrições que você sobe; no app Desktop, a captura e a transcrição acontecem na sua máquina — o áudio não passa pela infraestrutura da Nora, só o texto. No servidor existe uma purga automática por idade da reunião, mas ela vem desligada por padrão: enquanto ninguém configurar uma janela de retenção, nada é apagado sozinho. Apagar uma reunião em definitivo é uma ação sua, a qualquer momento.",
  },
  {
    q: "E a LGPD? Meus dados podem ser usados pra treinar modelos?",
    a: "Nunca. Antes de qualquer envio a um LLM externo, o PII Shield detecta e redige CPF, CNPJ, e-mail, telefone, cartão e nomes brasileiros. Nenhum dado seu treina modelos de terceiros. E o direito ao esquecimento (Art. 18) é exercido reunião a reunião: apagar uma é permanente e leva junto transcrição, participantes e análises.",
  },
  {
    q: "Funciona com Google Meet, Zoom, Teams?",
    a: "Sim. Você pode subir o arquivo de transcrição que essas ferramentas exportam (.txt, .vtt, .srt) ou usar o app Desktop, que captura o áudio do sistema independente da ferramenta de chamada.",
  },
  {
    q: "Em quais idiomas funciona?",
    a: "PT-BR nativo, com suporte secundário a EN-US. O modelo entende gírias, regionalismos e siglas brasileiras (ADR, MCP, CPF, etc.) sem precisar traduzir.",
  },
  {
    q: "Qual a diferença pro Gong / Otter / Fireflies?",
    a: "Gong e Clari são caros, em inglês e usam conhecimento genérico. Otter e Fireflies só transcrevem. Nora aprende o contexto do seu workspace (produtos, concorrentes, ICP, objeções) e devolve análise estruturada — em português, com conformidade LGPD.",
  },
  {
    q: "Posso integrar com Linear, GitHub, Slack?",
    a: "Sim, por OAuth: você conecta a sua conta na tela de Integrações e os fluxos escrevem por lá. Hoje existem conectores de Google (Gmail + Calendar), Microsoft (Outlook + Calendar), Slack, Telegram, GitHub, Notion, Todoist, Linear e Trello. Jira, Salesforce e Pipedrive estão no roadmap, sem data. O servidor MCP da Nora é o caminho contrário: ele expõe cinco ferramentas somente leitura pro seu agente consultar reuniões, tasks e Customer Confidence — MCP não escreve nada nas suas ferramentas.",
  },
  {
    q: "Onde os dados ficam armazenados?",
    a: "Em servidor próprio, autogerenciado. Nenhum serviço da aplicação fica acessível diretamente da internet: as portas que o stack publica ficam restritas ao loopback da máquina, e todo o acesso público entra por um túnel de saída. O isolamento entre organizações é feito por tenant_id em toda consulta ao banco. Você pode apagar qualquer reunião em definitivo a qualquer momento; exportação em massa dos seus dados ainda está no roadmap.",
  },
];

export function LandingFAQ() {
  return (
    <section id="faq">
      <div className="container">
        <div style={{ maxWidth: 720 }}>
          <div className="section-label">FAQ</div>
          <h2 className="section-title">Perguntas que costumam aparecer.</h2>
        </div>
        <div className="faq-list">
          {FAQS.map((f) => (
            <details className="faq-item" key={f.q}>
              <summary>
                <span>{f.q}</span>
                <span className="plus">+</span>
              </summary>
              <div className="answer">{f.a}</div>
            </details>
          ))}
        </div>
      </div>
    </section>
  );
}

// ── Final CTA ──
export function LandingFinalCTA() {
  return (
    <section id="cta" style={{ padding: "20px 0 80px" }}>
      <div className="container">
        <div className="cta">
          <div className="cta-inner">
            <h2>Sua próxima reunião pode terminar com o resumo pronto.</h2>
            <p>
              Grátis pra começar. Sem cartão. Sem armadilha de upgrade. Em PT-BR, com a sua
              privacidade no centro.
            </p>
            <div style={{ display: "flex", gap: 10, justifyContent: "center", flexWrap: "wrap" }}>
              <Link href="/auth/signup" className="btn btn-primary btn-lg">
                Começar grátis
                <svg
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <line x1="5" y1="12" x2="19" y2="12" />
                  <polyline points="12 5 19 12 12 19" />
                </svg>
              </Link>
              {/* There is nobody to schedule a demo with; the demo that exists is the
                  interactive section further up the page. */}
              <a href="#demo" className="btn btn-ghost btn-lg">
                Ver a demonstração
              </a>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

// ── Footer ──
const REPO_URL = "https://github.com/sf0rzin/nora";

export function LandingFooter() {
  return (
    <footer>
      <div className="container">
        <div className="footer-inner">
          <div className="footer-brand">
            <NoraLogo size={22} animate={false} />
            <p>
              Inteligência conversacional pra reuniões. Em português, com conformidade LGPD nativa.
            </p>
          </div>
          <div className="footer-col">
            <h4>Produto</h4>
            <ul>
              <li>
                <a href="#produto">Recursos</a>
              </li>
              <li>
                <a href="#planos">Planos</a>
              </li>
              <li>
                <a href="#demo">Demonstração</a>
              </li>
              <li>
                <a href="#faq">FAQ</a>
              </li>
            </ul>
          </div>
          {/* Every link here points at something that exists. The old "Empresa" column
              (Sobre, Carreiras, Blog, Contato) and the Termos/Status entries were
              href="#" placeholders for pages and documents that were never written. */}
          <div className="footer-col">
            <h4>Projeto</h4>
            <ul>
              <li>
                <a href={REPO_URL} target="_blank" rel="noopener noreferrer">
                  Repositório
                </a>
              </li>
              <li>
                <a
                  href={`${REPO_URL}/tree/main/docs/adr`}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  ADRs
                </a>
              </li>
              <li>
                <a
                  href={`${REPO_URL}/tree/main/docs/challenge`}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Challenge FIAP
                </a>
              </li>
            </ul>
          </div>
          <div className="footer-col">
            <h4>Recursos</h4>
            <ul>
              <li>
                <a href={`${REPO_URL}/tree/main/docs`} target="_blank" rel="noopener noreferrer">
                  Documentação
                </a>
              </li>
              <li>
                <a href="#privacidade">Privacidade · LGPD</a>
              </li>
            </ul>
          </div>
        </div>
        <div className="footer-meta">
          <span>© 2026 Nora · Construído em São Paulo</span>
          <span>Código e decisões de arquitetura abertos no repositório</span>
        </div>
      </div>
    </footer>
  );
}
