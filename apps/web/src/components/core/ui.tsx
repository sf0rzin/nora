/**
 * Kit de UI editorial do Core. Componentes finos sobre as classes `.nora-*`
 * declaradas em core.css (escopadas em .nora-core). Use estes nas páginas
 * internas no lugar do visual legado slate/shadcn, pra coerência com o shell.
 */

import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from "react";
import Link from "next/link";
import type { Route } from "next";

type BtnVariant = "primary" | "ghost" | "danger";

function btnClass(variant: BtnVariant, size?: "sm", extra?: string): string {
  return ["nora-btn", `nora-btn--${variant}`, size === "sm" ? "nora-btn--sm" : "", extra ?? ""]
    .filter(Boolean)
    .join(" ");
}

export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
}) {
  return (
    <header
      style={{
        display: "flex",
        flexWrap: "wrap",
        alignItems: "flex-end",
        justifyContent: "space-between",
        gap: 16,
        marginBottom: 28,
      }}
    >
      <div>
        <h1 className="nora-h1">{title}</h1>
        {subtitle && <p className="nora-sub">{subtitle}</p>}
      </div>
      {actions && (
        <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 8 }}>
          {actions}
        </div>
      )}
    </header>
  );
}

export function Card({
  children,
  style,
  className,
}: {
  children: ReactNode;
  style?: React.CSSProperties;
  className?: string;
}) {
  return (
    <div className={className ? `nora-card ${className}` : "nora-card"} style={style}>
      {children}
    </div>
  );
}

export function Section({
  title,
  action,
  children,
}: {
  title?: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section style={{ marginBottom: 28 }}>
      {(title || action) && (
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 10,
          }}
        >
          {title && <h2 className="nora-section-title">{title}</h2>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

export function Button({
  variant = "ghost",
  size,
  type = "button",
  className,
  ...rest
}: { variant?: BtnVariant; size?: "sm" } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button type={type} className={btnClass(variant, size, className)} {...rest} />;
}

export function ButtonLink({
  href,
  variant = "ghost",
  size,
  title,
  children,
}: {
  href: Route;
  variant?: BtnVariant;
  size?: "sm";
  title?: string;
  children: ReactNode;
}) {
  return (
    <Link href={href} className={btnClass(variant, size)} title={title}>
      {children}
    </Link>
  );
}

export function Field({
  label,
  hint,
  required,
  htmlFor,
  children,
}: {
  label: string;
  hint?: string;
  required?: boolean;
  htmlFor?: string;
  children: ReactNode;
}) {
  return (
    <div style={{ marginBottom: 16 }}>
      <label className="nora-label" htmlFor={htmlFor}>
        {label}
        {required && <span style={{ color: "var(--danger)" }}> *</span>}
      </label>
      {children}
      {hint && (
        <p style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 6, whiteSpace: "pre-line" }}>
          {hint}
        </p>
      )}
    </div>
  );
}

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input className="nora-input" {...props} />;
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className="nora-textarea" {...props} />;
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className="nora-select" {...props} />;
}

export function Badge({
  children,
  tone = "default",
}: {
  children: ReactNode;
  tone?: "default" | "success" | "warn" | "danger" | "accent";
}) {
  const bg =
    tone === "success"
      ? "oklch(0.94 0.05 155)"
      : tone === "warn"
        ? "oklch(0.95 0.06 70)"
        : tone === "danger"
          ? "oklch(0.95 0.04 25)"
          : tone === "accent"
            ? "var(--accent-soft)"
            : "var(--chip)";
  const color =
    tone === "success"
      ? "var(--success)"
      : tone === "warn"
        ? "var(--warn)"
        : tone === "danger"
          ? "var(--danger)"
          : tone === "accent"
            ? "var(--accent-ink)"
            : "var(--ink)";
  return (
    <span className="nora-badge" style={{ background: bg, color }}>
      {children}
    </span>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <div className="nora-empty">{children}</div>;
}

export function Banner({ tone, children }: { tone: "error" | "ok"; children: ReactNode }) {
  return (
    <div className={`nora-banner nora-banner--${tone}`} role={tone === "error" ? "alert" : "status"}>
      {children}
    </div>
  );
}
