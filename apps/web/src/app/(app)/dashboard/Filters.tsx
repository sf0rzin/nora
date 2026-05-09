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

  // Mantem o estado sincronizado quando o usuario navega para tras/frente
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

  return (
    <form
      onSubmit={apply}
      className="grid gap-3 rounded-md border border-slate-200 bg-white p-4 sm:grid-cols-5"
    >
      <input
        type="search"
        placeholder="Buscar por titulo…"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm sm:col-span-2"
      />
      <select
        value={status}
        onChange={(e) => setStatus(e.target.value)}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm"
      >
        <option value="">Todos status</option>
        <option value="PENDING">Pendente</option>
        <option value="PROCESSING">Processando</option>
        <option value="COMPLETED">Analisada</option>
        <option value="FAILED">Falhou</option>
      </select>
      <input
        type="date"
        value={from}
        onChange={(e) => setFrom(e.target.value)}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm"
      />
      <input
        type="date"
        value={to}
        onChange={(e) => setTo(e.target.value)}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm"
      />
      <div className="flex gap-2 sm:col-span-5">
        <button
          type="submit"
          className="rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-800"
        >
          Aplicar
        </button>
        <button
          type="button"
          onClick={clear}
          className="rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-700 hover:bg-slate-50"
        >
          Limpar
        </button>
      </div>
    </form>
  );
}
