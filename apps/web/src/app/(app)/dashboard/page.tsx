import { getTenantMetrics, listMeetings, type TenantMetrics } from "@/lib/api/client";
import type { MeetingListItem } from "@/lib/api/types";
import { buildInbox } from "@/components/core/format";
import HomeInbox from "@/components/core/home-inbox";
import MetricsStrip from "@/components/core/metrics-strip";

export const dynamic = "force-dynamic";

export default async function DashboardPage() {
  let items: MeetingListItem[];
  let totalItems = 0;
  let error: string | null = null;
  try {
    const data = await listMeetings({ size: 50 });
    items = data.items;
    totalItems = data.totalItems;
  } catch (err) {
    error = err instanceof Error ? err.message : "Falha ao carregar reuniões.";
    items = [];
  }

  // Métricas do tenant (US33) são complementares: se falharem, não quebram o
  // dashboard — apenas não renderizamos a faixa.
  let metrics: TenantMetrics | null = null;
  try {
    metrics = await getTenantMetrics();
  } catch {
    metrics = null;
  }

  // `nowMs` fixado no servidor → grouping idêntico no SSR e na hidratação.
  const { groups, todayCount } = buildInbox(items, Date.now());

  return (
    <>
      {metrics && (
        <div style={{ maxWidth: 920, margin: "0 auto" }}>
          <MetricsStrip metrics={metrics} />
        </div>
      )}
      <HomeInbox groups={groups} todayCount={todayCount} totalItems={totalItems} error={error} />
    </>
  );
}
