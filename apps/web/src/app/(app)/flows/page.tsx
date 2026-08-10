"use client";

/**
 * NORA Flows — flow list (/flows).
 *
 * Tenant automations: each card shows name, trigger, state (active/paused)
 * and the last update. Everything comes from the real backend (GET /workflows) — no mock.
 */
import Link from "next/link";
import type { Route } from "next";
import { useEffect, useState } from "react";

import { ApiRequestError, listWorkflows, type WorkflowResponse } from "@/lib/api/client";
import { strings } from "@/lib/strings";

import { blockMeta } from "./catalog";
import { relativeTime } from "./relative-time";

const copy = strings.flows.list;

/** Empty-state illustration — three connected nodes, in the app icons' stroke style. */
function FlowIllustration() {
  return (
    <svg
      width="150"
      height="84"
      viewBox="0 0 150 84"
      fill="none"
      aria-hidden
      style={{ display: "block" }}
    >
      {/* edges */}
      <path
        d="M44 42h18M96 42h18"
        stroke="var(--border-strong)"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeDasharray="3 4"
      />
      {/* trigger */}
      <rect x="8" y="26" width="36" height="32" rx="9" fill="var(--accent-soft)" stroke="var(--accent)" strokeWidth="1.5" />
      <path d="M27.5 33 22 41h4l-1.5 7L30 40h-4z" fill="none" stroke="var(--accent)" strokeWidth="1.5" strokeLinejoin="round" />
      {/* condition */}
      <rect x="62" y="26" width="34" height="32" rx="9" fill="var(--canvas)" stroke="var(--border-strong)" strokeWidth="1.5" />
      <path d="M72 36h12l-4.5 5v4l-3-1.5v-2.5z" fill="none" stroke="var(--warn)" strokeWidth="1.5" strokeLinejoin="round" />
      {/* action */}
      <rect x="114" y="26" width="28" height="32" rx="9" fill="var(--canvas)" stroke="var(--border-strong)" strokeWidth="1.5" />
      <path d="m134 36-9 9M134 36l-5.5 12-1.8-4.7-4.7-1.8z" fill="none" stroke="var(--success)" strokeWidth="1.4" strokeLinejoin="round" />
    </svg>
  );
}

function FlowCard({ flow }: { flow: WorkflowResponse }) {
  const trigger = blockMeta(flow.triggerType)?.name ?? flow.triggerType;
  const conditions = flow.definition.nodes.filter((n) => n.kind === "condition").length;
  const actions = flow.definition.nodes.filter((n) => n.kind === "action").length;
  return (
    <Link href={`/flows/${flow.id}` as Route} className="card flows-card">
      <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 10 }}>
        <span
          style={{
            fontSize: 14.5,
            fontWeight: 500,
            letterSpacing: "-0.012em",
            lineHeight: 1.35,
            overflow: "hidden",
            display: "-webkit-box",
            WebkitLineClamp: 2,
            WebkitBoxOrient: "vertical",
          }}
        >
          {flow.name}
        </span>
        {flow.active ? (
          <span className="chip" style={{ flexShrink: 0, color: "var(--success)" }}>
            <span className="status-dot" style={{ background: "var(--success)", width: 6, height: 6 }} />
            {copy.active}
          </span>
        ) : (
          <span className="chip" style={{ flexShrink: 0, color: "var(--muted)" }}>
            <span className="status-dot" style={{ background: "var(--border-strong)", width: 6, height: 6 }} />
            {copy.paused}
          </span>
        )}
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
        <span className="chip">{trigger}</span>
        {conditions > 0 && (
          <span style={{ fontSize: 11.5, color: "var(--muted)" }}>
            {conditions} {copy.conditionCount(conditions)}
          </span>
        )}
        <span style={{ fontSize: 11.5, color: "var(--muted)" }}>
          {actions} {copy.actionCount(actions)}
        </span>
      </div>

      <span style={{ fontSize: 11.5, color: "var(--muted)" }}>
        {copy.updatedPrefix} {relativeTime(flow.updatedAt)}
      </span>
    </Link>
  );
}

export default function FlowsPage() {
  const [flows, setFlows] = useState<WorkflowResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    listWorkflows()
      .then((r) => {
        if (alive) setFlows(r);
      })
      .catch((e) => {
        if (!alive) return;
        setError(e instanceof ApiRequestError ? e.message : copy.loadFailed);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [attempt]);

  return (
    <div className="page">
      <header
        style={{
          display: "flex",
          alignItems: "flex-end",
          justifyContent: "space-between",
          gap: 24,
          marginBottom: 28,
        }}
      >
        <div>
          <div className="eyebrow">{copy.eyebrow}</div>
          <h1 className="h1">{copy.title}</h1>
          <p className="lede" style={{ marginTop: 8 }}>
            {copy.lede}
          </p>
        </div>
        <Link href={"/flows/new" as Route} className="btn btn-primary" style={{ flexShrink: 0 }}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
          {copy.newFlow}
        </Link>
      </header>

      {error && (
        <div
          className="notice notice--danger"
          style={{ marginBottom: 16, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}
        >
          <span>
            {copy.loadErrorPrefix} ({error}).
          </span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setAttempt((t) => t + 1)}>
            {copy.retry}
          </button>
        </div>
      )}

      {loading ? (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 14 }}>
          {[0, 1, 2].map((i) => (
            <div key={i} className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <div className="skel" style={{ width: "65%", height: 15 }} />
              <div className="skel" style={{ width: "45%", height: 12 }} />
              <div className="skel" style={{ width: "35%", height: 11 }} />
            </div>
          ))}
        </div>
      ) : flows.length === 0 && !error ? (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            gap: 18,
            padding: "56px 24px",
            textAlign: "center",
            border: "1px dashed var(--border-strong)",
            borderRadius: 14,
          }}
        >
          <FlowIllustration />
          <div style={{ maxWidth: 380 }}>
            <h2
              style={{
                fontFamily: "var(--display)",
                fontSize: 18,
                fontWeight: 500,
                letterSpacing: "-0.018em",
                margin: "0 0 6px",
                color: "var(--ink)",
              }}
            >
              {copy.emptyTitle}
            </h2>
            <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
              {copy.emptyBody}
            </p>
          </div>
          <Link href={"/flows/new" as Route} className="btn btn-primary">
            {copy.emptyCta}
          </Link>
        </div>
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 14 }}>
          {flows.map((f) => (
            <FlowCard key={f.id} flow={f} />
          ))}
        </div>
      )}
    </div>
  );
}
