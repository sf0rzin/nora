import Link from "next/link";
import type { Route } from "next";
import { listMeetings, type ListMeetingsParams } from "@/lib/api/client";
import { formatDateTime } from "@/lib/utils";
import DashboardFilters from "./Filters";

export const dynamic = "force-dynamic";

const STATUS_LABEL: Record<string, string> = {
  PENDING: "Pendente",
  PROCESSING: "Processando",
  COMPLETED: "Analisada",
  FAILED: "Falhou",
};

const STATUS_VALUES = ["PENDING", "PROCESSING", "COMPLETED", "FAILED"] as const;

function isStatus(s: string | undefined): s is ListMeetingsParams["status"] {
  return !!s && (STATUS_VALUES as readonly string[]).includes(s);
}

export default async function DashboardPage({
  searchParams,
}: {
  searchParams: { search?: string; status?: string; from?: string; to?: string };
}) {
  const filters: ListMeetingsParams = {
    search: searchParams.search?.trim() || undefined,
    status: isStatus(searchParams.status) ? searchParams.status : undefined,
    from: searchParams.from || undefined,
    to: searchParams.to || undefined,
  };

  let data;
  let errorMessage: string | null = null;
  try {
    data = await listMeetings(filters);
  } catch (err) {
    errorMessage = err instanceof Error ? err.message : "Falha ao carregar reunioes.";
    data = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };
  }

  return (
    <div className="space-y-6">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Reuniões</h1>
          <p className="text-sm text-slate-500">
            {data.totalItems} {data.totalItems === 1 ? "reunião" : "reuniões"} no total
          </p>
        </div>
        <Link
          href={"/meetings/upload" as Route}
          className="rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-800"
        >
          Nova reunião
        </Link>
      </header>

      <DashboardFilters defaults={filters} />

      {errorMessage && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {errorMessage}
        </div>
      )}

      {data.items.length === 0 ? (
        <p className="rounded-md border border-slate-200 bg-white p-6 text-sm text-slate-600">
          Nenhuma reunião encontrada com esses filtros.
        </p>
      ) : (
        <ul className="divide-y divide-slate-200 rounded-lg border border-slate-200 bg-white">
          {data.items.map((m) => (
            <li key={m.id}>
              <Link
                href={`/meetings/${m.id}` as Route}
                className="flex flex-col gap-1 p-4 transition hover:bg-slate-50"
              >
                <div className="flex items-baseline justify-between gap-4">
                  <h2 className="font-medium text-slate-900">{m.title}</h2>
                  <span className="text-xs uppercase tracking-wide text-slate-500">
                    {STATUS_LABEL[m.processingStatus] ?? m.processingStatus}
                  </span>
                </div>
                {m.summarySnippet && (
                  <p className="line-clamp-2 text-sm text-slate-600">{m.summarySnippet}</p>
                )}
                <div className="flex flex-wrap items-center gap-3 pt-1 text-xs text-slate-500">
                  {m.startedAt && <span>{formatDateTime(m.startedAt)}</span>}
                  {m.startedAt && <span>·</span>}
                  <span>{m.actionItemCount} tarefas</span>
                  <span>·</span>
                  <span>{m.riskCount} riscos</span>
                  <span>·</span>
                  <span>{m.opportunityCount} oportunidades</span>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
