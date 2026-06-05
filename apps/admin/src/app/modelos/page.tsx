import { getBindings, getModels } from "@/lib/data";

import { ModelosClient } from "./modelos-client";

// Dados vêm de fetch server-side (no-store) contra /admin/platform/* — sempre dinâmico.
export const dynamic = "force-dynamic";

export default async function ModelosPage() {
  const [models, bindings] = await Promise.all([getModels(), getBindings()]);
  return <ModelosClient initialModels={models} initialBindings={bindings} />;
}
