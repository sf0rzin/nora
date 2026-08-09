"use server";

import { revalidatePath } from "next/cache";

import { requireAccess } from "@/lib/access";
import type { ServiceKey } from "@/lib/contracts";
import { bindService, createModel, removeModel, type NewModelInput } from "@/lib/data";
import { getOperator } from "@/lib/operator";

/**
 * Server actions for the models console. They run server-side (the bridge token never reaches
 * the browser) and stamp the operator's e-mail (Cloudflare Access) for auditing in the backend.
 * They return `{ ok }` instead of throwing — the UI shows the error without taking the page down.
 *
 * EACH ONE CALLS `requireAccess()` BEFORE MUTATING. The RootLayout gate does not apply here:
 * in a server action Next runs the action first and re-renders the tree afterwards, that is,
 * the layout runs when the side effect already happened. And the action id is in the
 * client bundle, so the endpoint is reachable by a direct POST from anyone who
 * reaches the origin. `addModelAction` accepts an arbitrary `baseUrl`: without this gate, whoever
 * reached the internal network would repoint the LLM provider of the entire platform — with
 * an audit trail stamped in someone else's name, because the e-mail came from an
 * unsigned header.
 */

type ActionResult = { ok: true } | { ok: false; error: string };

function fail(e: unknown): ActionResult {
  return { ok: false, error: e instanceof Error ? e.message : "Falha inesperada" };
}

/**
 * Identity for the audit trail. Under enforce, it is the `email` claim of the verified
 * JWT — not the header of the same name, which is forgeable by whoever reaches the origin.
 */
async function operadorAutorizado(): Promise<string> {
  const verificado = await requireAccess();
  return verificado ?? (await getOperator()).email;
}

export async function bindServiceAction(
  service: ServiceKey,
  modelId: string,
  enabled: boolean,
): Promise<ActionResult> {
  try {
    const operador = await operadorAutorizado();
    await bindService(service, modelId, enabled, operador);
    revalidatePath("/modelos");
    return { ok: true };
  } catch (e) {
    return fail(e);
  }
}

export async function removeModelAction(id: string): Promise<ActionResult> {
  try {
    const operador = await operadorAutorizado();
    await removeModel(id, operador);
    revalidatePath("/modelos");
    return { ok: true };
  } catch (e) {
    return fail(e);
  }
}

export async function addModelAction(input: NewModelInput): Promise<ActionResult> {
  try {
    const operador = await operadorAutorizado();
    await createModel(input, operador);
    revalidatePath("/modelos");
    return { ok: true };
  } catch (e) {
    return fail(e);
  }
}
