"use server";

import { revalidatePath } from "next/cache";

import { requireAccess } from "@/lib/access";
import type { ServiceKey } from "@/lib/contracts";
import { bindService, createModel, removeModel, type NewModelInput } from "@/lib/data";
import { getOperator } from "@/lib/operator";

/**
 * Server actions do console de modelos. Rodam server-side (token de bridge nunca chega ao
 * browser) e carimbam o e-mail do operador (Cloudflare Access) pra auditoria no backend.
 * Retornam `{ ok }` em vez de lançar — a UI mostra o erro sem derrubar a página.
 *
 * CADA UMA CHAMA `requireAccess()` ANTES DE MUTAR. O gate do RootLayout não vale aqui:
 * numa server action o Next executa a action primeiro e re-renderiza a árvore depois, ou
 * seja, o layout roda quando o efeito colateral já aconteceu. E o id da action está no
 * bundle do cliente, então o endpoint é alcançável por POST direto por qualquer um que
 * chegue na origem. `addModelAction` aceita `baseUrl` arbitrária: sem este gate, quem
 * alcançasse a rede interna repontaria o provedor de LLM da plataforma inteira — com
 * trilha de auditoria carimbada em nome de outra pessoa, porque o e-mail vinha de um
 * header não assinado.
 */

type ActionResult = { ok: true } | { ok: false; error: string };

function fail(e: unknown): ActionResult {
  return { ok: false, error: e instanceof Error ? e.message : "Falha inesperada" };
}

/**
 * Identidade para a trilha de auditoria. Sob enforce, é o claim `email` do JWT
 * verificado — não o header homônimo, que é forjável por quem alcança a origem.
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
