"use server";

import { revalidatePath } from "next/cache";

import type { ServiceKey } from "@/lib/contracts";
import { bindService, createModel, removeModel, type NewModelInput } from "@/lib/data";
import { getOperator } from "@/lib/operator";

/**
 * Server actions do console de modelos. Rodam server-side (token de bridge nunca chega ao
 * browser) e carimbam o e-mail do operador (Cloudflare Access) pra auditoria no backend.
 * Retornam `{ ok }` em vez de lançar — a UI mostra o erro sem derrubar a página.
 */

type ActionResult = { ok: true } | { ok: false; error: string };

function fail(e: unknown): ActionResult {
  return { ok: false, error: e instanceof Error ? e.message : "Falha inesperada" };
}

export async function bindServiceAction(
  service: ServiceKey,
  modelId: string,
  enabled: boolean,
): Promise<ActionResult> {
  try {
    await bindService(service, modelId, enabled, getOperator().email);
    revalidatePath("/modelos");
    return { ok: true };
  } catch (e) {
    return fail(e);
  }
}

export async function removeModelAction(id: string): Promise<ActionResult> {
  try {
    await removeModel(id, getOperator().email);
    revalidatePath("/modelos");
    return { ok: true };
  } catch (e) {
    return fail(e);
  }
}

export async function addModelAction(input: NewModelInput): Promise<ActionResult> {
  try {
    await createModel(input, getOperator().email);
    revalidatePath("/modelos");
    return { ok: true };
  } catch (e) {
    return fail(e);
  }
}
