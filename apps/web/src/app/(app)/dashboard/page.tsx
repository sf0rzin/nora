import Link from "next/link";
import { listMeetings } from "@/lib/api/client";
import { formatDateTime } from "@/lib/utils";

export const dynamic = "force-dynamic";

const STATUS_LABEL: Record<string, string> = {
  PENDING: "Pendente",
  PROCESSING: "Processando",
  COMPLETED: "Analisada",
  FAILED: "Falhou",
};

export default async function DashboardPage() {
  const data = await listMeetings();

  return (
    <div className="space-y-6">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Reuniões</h1>
          <p className="text-sm text-muted-foreground">
            {data.totalItems} {data.totalItems === 1 ? "reunião" : "reuniões"} no total
          </p>
        </div>
      </header>

      <ul className="divide-y divide-border rounded-lg border border-border bg-background">
        {data.items.map((m) => (
          <li key={m.id}>
            <Link
              href={`/meetings/${m.id}`}
              className="flex flex-col gap-1 p-4 hover:bg-muted/40 transition"
            >
              <div className="flex items-baseline justify-between gap-4">
                <h2 className="font-medium">{m.title}</h2>
                <span className="text-xs uppercase tracking-wide text-muted-foreground">
                  {STATUS_LABEL[m.processingStatus] ?? m.processingStatus}
                </span>
              </div>
              {m.summarySnippet && (
                <p className="text-sm text-muted-foreground line-clamp-2">
                  {m.summarySnippet}
                </p>
              )}
              <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground pt-1">
                <span>{formatDateTime(m.startedAt)}</span>
                <span>·</span>
                <span>{m.ownerName}</span>
                <span>·</span>
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
    </div>
  );
}
