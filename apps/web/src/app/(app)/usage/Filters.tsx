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
 * Same filter bar as the trends panel — a form that rewrites the query string and lets the Server
 * Component refetch — with the default flipped: consumption is read per month, so `MONTH` is the
 * state with no query parameter and `WEEK` is the one that writes it.
 */
export default function UsageFilters({ granularity, from, to }: Props) {
  const router = useRouter();
  const params = useSearchParams();
  const [unit, setUnit] = useState<TrendsGranularity>(granularity);
  const [start, setStart] = useState(from?.slice(0, 10) ?? "");
  const [end, setEnd] = useState(to?.slice(0, 10) ?? "");

  useEffect(() => {
    const raw = params.get("granularity");
    setUnit(raw === "WEEK" ? "WEEK" : "MONTH");
    setStart((params.get("from") ?? "").slice(0, 10));
    setEnd((params.get("to") ?? "").slice(0, 10));
  }, [params]);

  function apply(e?: React.FormEvent) {
    e?.preventDefault();
    const qs = new URLSearchParams();
    if (unit === "WEEK") qs.set("granularity", "WEEK");
    if (start) qs.set("from", `${start}T00:00:00Z`);
    if (end) qs.set("to", `${end}T23:59:59Z`);
    const q = qs.toString();
    router.push((q ? `/usage?${q}` : "/usage") as Route);
  }

  function clear() {
    setUnit("MONTH");
    setStart("");
    setEnd("");
    router.push("/usage" as Route);
  }

  const hasAny = Boolean(unit === "WEEK" || start || end);

  return (
    <form onSubmit={apply} style={{ display: "flex", flexWrap: "wrap", gap: 8, alignItems: "center" }}>
      <select
        className="select"
        value={unit}
        onChange={(e) => setUnit(e.target.value === "WEEK" ? "WEEK" : "MONTH")}
        aria-label="Agrupar por"
      >
        <option value="MONTH">Por mês</option>
        <option value="WEEK">Por semana</option>
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
