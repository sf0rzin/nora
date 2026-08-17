"use client";

import { useRouter, useSearchParams } from "next/navigation";
import type { Route } from "next";
import { useEffect, useState } from "react";
import type { TrendsGranularity } from "@/lib/api/client";

interface Props {
  granularity: TrendsGranularity;
  from?: string;
  to?: string;
}

/**
 * Same shape as the dashboard's filter bar (a form that rewrites the query string and lets the
 * Server Component refetch), plus the one control this panel adds: the bucket size. The date
 * inputs send a full ISO instant because the API parses `OffsetDateTime`, exactly like
 * `GET /meetings`.
 */
export default function TrendsFilters({ granularity, from, to }: Props) {
  const router = useRouter();
  const params = useSearchParams();
  const [unit, setUnit] = useState<TrendsGranularity>(granularity);
  const [start, setStart] = useState(from?.slice(0, 10) ?? "");
  const [end, setEnd] = useState(to?.slice(0, 10) ?? "");

  useEffect(() => {
    const raw = params.get("granularity");
    setUnit(raw === "MONTH" ? "MONTH" : "WEEK");
    setStart((params.get("from") ?? "").slice(0, 10));
    setEnd((params.get("to") ?? "").slice(0, 10));
  }, [params]);

  function apply(e?: React.FormEvent) {
    e?.preventDefault();
    const qs = new URLSearchParams();
    if (unit === "MONTH") qs.set("granularity", "MONTH");
    if (start) qs.set("from", `${start}T00:00:00Z`);
    if (end) qs.set("to", `${end}T23:59:59Z`);
    const q = qs.toString();
    router.push((q ? `/trends?${q}` : "/trends") as Route);
  }

  function clear() {
    setUnit("WEEK");
    setStart("");
    setEnd("");
    router.push("/trends" as Route);
  }

  const hasAny = Boolean(unit === "MONTH" || start || end);

  return (
    <form onSubmit={apply} style={{ display: "flex", flexWrap: "wrap", gap: 8, alignItems: "center" }}>
      <select
        className="select"
        value={unit}
        onChange={(e) => setUnit(e.target.value === "MONTH" ? "MONTH" : "WEEK")}
        aria-label="Agrupar por"
      >
        <option value="WEEK">Por semana</option>
        <option value="MONTH">Por mês</option>
      </select>
      <input
        className="input"
        type="date"
        value={start}
        onChange={(e) => setStart(e.target.value)}
        aria-label="De"
      />
      <input
        className="input"
        type="date"
        value={end}
        onChange={(e) => setEnd(e.target.value)}
        aria-label="Até"
      />
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
