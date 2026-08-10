import { requireAccess } from "@/lib/access";
import { getBindings, getModels } from "@/lib/data";

import { ModelsClient } from "./models-client";

// Data comes from a server-side fetch (no-store) against /admin/platform/* — always dynamic.
export const dynamic = "force-dynamic";

export default async function ModelosPage() {
  // See the note in app/page.tsx: the layout does not re-run on RSC navigation, so each read
  // gates itself.
  await requireAccess();

  const [models, bindings] = await Promise.all([getModels(), getBindings()]);
  return <ModelsClient initialModels={models} initialBindings={bindings} />;
}
