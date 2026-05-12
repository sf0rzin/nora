"use client";

/**
 * IAM section — tree + policy.json + eval list em grid de 3 colunas.
 * Portado de nora-sections-2.jsx (linhas 599-760).
 */
export function IAMSection() {
  return (
    <section className="section" id="iam" data-screen-label="07 IAM">
      <div className="container">
        <div className="iam-head">
          <div className="eyebrow">IAM · Estilo AWS</div>
          <h2 className="h-section">
            Sem hierarquia fixa.
            <br />
            <span style={{ color: "var(--fg-muted)" }}>
              Cada tenant define seus grupos e políticas.
            </span>
          </h2>
          <p className="h-sub">
            Modelo Root + Users + Groups + Policies. Documentos JSON com Effect, Action, Resource e
            Condition. Avaliação Deny-first. Default Deny. Zero <em>Manager</em>, zero{" "}
            <em>Viewer</em> hardcoded.
          </p>
        </div>

        <div className="iam-grid">
          <div className="iam-tree">
            <div className="iam-tree-item iam-tree-item--root">
              <span className="iam-tree-tag mono">root</span>
              <span>Owner do tenant · bypass total</span>
            </div>
            <div className="iam-tree-item">
              <span className="iam-tree-tag mono">user</span>
              <span>Mariana A. · Vendas-SP</span>
            </div>
            <div className="iam-tree-item">
              <span className="iam-tree-tag mono">user</span>
              <span>Pedro L. · Eng-Backend</span>
            </div>
            <div className="iam-tree-item iam-tree-item--group">
              <span className="iam-tree-tag mono">group</span>
              <span>Vendas-SP</span>
            </div>
            <div className="iam-tree-item iam-tree-item--group">
              <span className="iam-tree-tag mono">group</span>
              <span>Eng-Backend</span>
            </div>
            <div className="iam-tree-item iam-tree-item--policy">
              <span className="iam-tree-tag mono">policy</span>
              <span>MeetingAnalystAccess</span>
            </div>
          </div>

          <div className="iam-policy">
            <div className="iam-policy-head">
              <span className="kicker">policy.json</span>
              <span className="iam-policy-meta">v · 2026-05-07</span>
            </div>
            <pre className="iam-policy-code">{`{
  "version": "2026-05-07",
  "statements": [
    {
      "effect": "Allow",
      "action": [
        "meeting:read",
        "analysis:read"
      ],
      "resource": [
        "nora:tenant/acme:meeting/*"
      ],
      "condition": {
        "stringEquals": {
          "nora:Department": "sales"
        }
      }
    }
  ]
}`}</pre>
          </div>

          <div className="iam-eval">
            <div className="kicker">Avaliação · Deny-first</div>
            <ol className="iam-eval-list">
              <li>
                É Root? <span className="mono">Allow</span> sem avaliar políticas.
              </li>
              <li>Coletar policies do user + grupos.</li>
              <li>
                Algum{" "}
                <span className="mono" style={{ color: "var(--danger)" }}>
                  Deny
                </span>{" "}
                aplicável? Vence.
              </li>
              <li>
                Pelo menos um{" "}
                <span className="mono" style={{ color: "var(--success)" }}>
                  Allow
                </span>{" "}
                casa Action+Resource+Condition?
              </li>
              <li>
                Default:{" "}
                <span className="mono" style={{ color: "var(--danger)" }}>
                  Deny
                </span>
                .
              </li>
            </ol>
          </div>
        </div>
      </div>

      <style jsx>{`
        .iam-head {
          max-width: 760px;
          margin-bottom: 56px;
        }
        .iam-grid {
          display: grid;
          grid-template-columns: 1fr 1.2fr 1fr;
          gap: 16px;
          align-items: stretch;
        }
        .iam-tree,
        .iam-policy,
        .iam-eval {
          padding: 24px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius-lg);
        }
        .iam-tree {
          display: flex;
          flex-direction: column;
          gap: 8px;
        }
        .iam-tree-item {
          display: flex;
          gap: 12px;
          align-items: center;
          padding: 10px 14px;
          background: var(--bg-deep);
          border: 1px solid var(--border);
          border-radius: var(--radius-sm);
          font-size: 13px;
        }
        .iam-tree-item--root {
          border-color: var(--accent-dim);
        }
        .iam-tree-item--group {
          margin-left: 24px;
        }
        .iam-tree-item--policy {
          margin-left: 48px;
          border-color: var(--warn);
          border-style: dashed;
        }
        .iam-tree-tag {
          font-size: 9px;
          padding: 2px 6px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: 3px;
          color: var(--fg-dim);
          letter-spacing: 0.08em;
          text-transform: uppercase;
        }
        .iam-tree-item--root .iam-tree-tag {
          color: var(--accent-bright);
        }
        .iam-tree-item--group .iam-tree-tag {
          color: var(--success);
        }
        .iam-tree-item--policy .iam-tree-tag {
          color: var(--warn);
        }

        .iam-policy {
          display: flex;
          flex-direction: column;
          gap: 12px;
        }
        .iam-policy-head {
          display: flex;
          justify-content: space-between;
          align-items: baseline;
        }
        .iam-policy-meta {
          font-size: 10px;
          color: var(--fg-dim);
        }
        .iam-policy-code {
          margin: 0;
          font-family: var(--font-mono);
          font-size: 11.5px;
          line-height: 1.7;
          color: var(--fg);
          padding: 16px;
          background: var(--bg-deep);
          border: 1px solid var(--border);
          border-radius: var(--radius-sm);
          overflow-x: auto;
          flex: 1;
        }

        .iam-eval-list {
          margin: 12px 0 0;
          padding-left: 18px;
          display: flex;
          flex-direction: column;
          gap: 12px;
          font-size: 13px;
          color: var(--fg-muted);
          line-height: 1.5;
        }
        .iam-eval-list :global(li::marker) {
          color: var(--accent);
          font-family: var(--font-mono);
          font-size: 11px;
        }

        @media (max-width: 980px) {
          .iam-grid {
            grid-template-columns: 1fr;
          }
        }
      `}</style>
    </section>
  );
}
