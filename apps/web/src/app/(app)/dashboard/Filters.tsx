"use client";

import { useRouter, useSearchParams } from "next/navigation";
import type { Route } from "next";
import { useState, useEffect } from "react";
import type { ListMeetingsParams } from "@/lib/api/client";

interface Props {
  defaults: ListMeetingsParams;
}

export default function DashboardFilters({ defaults }: Props) {
  const router = useRouter();
  const params = useSearchParams();
  const [search, setSearch] = useState(defaults.search ?? "");
  const [status, setStatus] = useState(defaults.status ?? "");
  const [from, setFrom] = useState(defaults.from?.slice(0, 10) ?? "");
  const [to, setTo] = useState(defaults.to?.slice(0, 10) ?? "");

  useEffect(() => {
    setSearch(params.get("search") ?? "");
    setStatus(params.get("status") ?? "");
    setFrom((params.get("from") ?? "").slice(0, 10));
    setTo((params.get("to") ?? "").slice(0, 10));
  }, [params]);

  function apply(e?: React.FormEvent) {
    e?.preventDefault();
    const qs = new URLSearchParams();
    if (search.trim()) qs.set("search", search.trim());
    if (status) qs.set("status", status);
    if (from) qs.set("from", `${from}T00:00:00Z`);
    if (to) qs.set("to", `${to}T23:59:59Z`);
    // Reaplicar filtros sempre volta pra página 1.
    const q = qs.toString();
    router.push((q ? `/dashboard?${q}` : "/dashboard") as Route);
  }

  function clear() {
    setSearch("");
    setStatus("");
    setFrom("");
    setTo("");
    router.push("/dashboard" as Route);
  }

  const hasAny = Boolean(search || status || from || to);

  return (
    <form onSubmit={apply} style={{ display: "flex", flexWrap: "wrap", gap: 8, alignItems: "center" }}>
      <div style={{ position: "relative", flex: "1 1 220px", minWidth: 200 }}>
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          style={{ position: "absolute", left: 11, top: 10, color: "var(--muted)" }}
        >
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" />
        </svg>
        <input
          className="input"
          type="search"
          placeholder="Buscar reuniões…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ width: "100%", paddingLeft: 32 }}
        />
      </div>
      <select className="select" value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
        <option value="">Todos status</option>
        <option value="PENDING">Na fila</option>
        <option value="PROCESSING">Analisando</option>
        <option value="COMPLETED">Analisada</option>
        <option value="FAILED">Falhou</option>
      </select>
      <input className="input" type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="De" />
      <input className="input" type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="Até" />
      <button className="btn btn-primary btn-sm" type="submit" style={{ borderRadius: 8 }}>
        Aplicar
      </button>
      {hasAny && (
        <button className="btn btn-ghost btn-sm" type="button" onClick={clear}>
          Limpar
        </button>
      )}
    </form>
  );
}

/**
 * Atualiza a lista enquanto houver reuniões em PROCESSING — espelha o polling
 * do upload (Subfase 1.3), mas no nível da lista. Sem libs novas: setInterval
 * + router.refresh() revalida o Server Component até nada mais estar analisando.
 */
const POLL_INTERVAL_MS = 4_000;

export function ProcessingPoller({ active }: { active: boolean }) {
  const router = useRouter();

  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => {
      router.refresh();
    }, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [active, router]);

  return null;
}
