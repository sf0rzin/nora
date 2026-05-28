import React, { type ReactNode, type ErrorInfo } from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";
import "./styles.css";

class ErrorBoundary extends React.Component<
  { children: ReactNode },
  { hasError: boolean; error: Error | null }
> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error("[ErrorBoundary]", error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div
          className="flex items-center justify-center h-screen p-6"
          style={{ background: "var(--canvas)", color: "var(--ink)" }}
        >
          <div className="max-w-lg w-full">
            <h1
              style={{
                fontFamily: "var(--display)",
                fontSize: 22,
                fontWeight: 500,
                letterSpacing: "-0.022em",
                color: "var(--danger-ink)",
                margin: "0 0 12px",
              }}
            >
              Algo travou no NORA Desktop.
            </h1>
            <p style={{ fontSize: 13.5, color: "var(--muted)", lineHeight: 1.55, marginBottom: 14 }}>
              O frontend não conseguiu inicializar. Detalhes técnicos abaixo —
              copie e mande pro time se persistir.
            </p>
            <pre
              style={{
                fontSize: 12,
                fontFamily: "ui-monospace, SF Mono, Menlo, monospace",
                background: "var(--sidebar)",
                border: "1px solid var(--border)",
                padding: 14,
                borderRadius: 8,
                color: "var(--danger-ink)",
                overflow: "auto",
                maxHeight: 240,
              }}
            >
              {this.state.error?.message}
            </pre>
            <button
              onClick={() => window.location.reload()}
              style={{
                marginTop: 16,
                padding: "9px 14px",
                background: "var(--ink)",
                color: "var(--canvas)",
                borderRadius: 8,
                border: "none",
                cursor: "pointer",
                fontSize: 13,
                fontWeight: 500,
                letterSpacing: "-0.005em",
              }}
            >
              Recarregar
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </React.StrictMode>,
);
