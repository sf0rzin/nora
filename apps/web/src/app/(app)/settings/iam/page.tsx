"use client";

import { useEffect, useState } from "react";
import {
  ApiRequestError,
  type AuditEventDto,
  type GroupDto,
  type PermissionBoundaryDto,
  type PolicyDto,
  type PolicyTemplateDto,
  type SimulationDto,
  addGroupMember,
  attachPolicyToGroup,
  attachPolicyToUser,
  createGroup,
  createPolicy,
  deleteGroup,
  deletePolicy,
  detachPolicyFromGroup,
  detachPolicyFromUser,
  getPermissionBoundary,
  listAuditEvents,
  listGroups,
  listPolicies,
  listPolicyTemplates,
  removeGroupMember,
  removePermissionBoundary,
  setPermissionBoundary,
  simulatePolicy,
  updatePolicyDocument,
} from "@/lib/api/client";
import PolicyEditor from "@/components/policy-editor";
import PolicyFormEditor from "@/components/policy-form-editor";
import CorporateDomainCard from "@/components/corporate-domain-card";
import InvitationCard from "@/components/invitation-card";

const POLICY_PLACEHOLDER = `{
  "version": "2026-05-07",
  "statements": [
    {
      "effect": "Allow",
      "action": ["meeting:read"],
      "resource": ["nora:tenant/*:meeting/*"]
    }
  ]
}`;

// One line per decision reason. The simulator answers a boolean plus one of these; showing only
// the boolean is what made policy debugging blind in the first place (US43).
const REASON_COPY: Record<SimulationDto["reason"], string> = {
  ROOT_BYPASS:
    "Este usuário é Root do tenant: permitido por bypass, sem consultar nenhum statement.",
  ALLOW: "Um statement Allow correspondeu e nenhum Deny correspondeu.",
  EXPLICIT_DENY: "Um statement Deny correspondeu — Deny vence qualquer Allow.",
  NO_MATCHING_STATEMENT:
    "Nenhum statement aplicável correspondeu à ação, ao recurso ou à condição. Negado por padrão.",
  NO_STATEMENTS: "O usuário não tem nenhuma policy anexada. Negado por padrão.",
  BOUNDARY_NOT_PERMITTED:
    "As policies do usuário permitiam, mas o permission boundary não cobre esta ação. O limite" +
    " nunca concede: ele só restringe.",
  BOUNDARY_EXPLICIT_DENY:
    "As policies do usuário permitiam, e um statement Deny do permission boundary correspondeu.",
};

type ContextPair = { key: string; value: string };

/** Which of the two editors is on screen. Both write the same JSON string (US42). */
type EditorMode = "form" | "json";

/**
 * pt-BR copy for the built-in templates (US41), keyed by the id the API returns. The API ships
 * English identifiers and an English one-line summary, as everything in `services/api` does; the
 * user-facing wording lives here with the rest of the UI. An id this map does not know still
 * renders — with the API's own description — so a template added on the server is never invisible.
 */
const TEMPLATE_COPY: Record<string, { title: string; hint: string }> = {
  "read-only-access": {
    title: "Somente leitura",
    hint: "Lê reuniões, tarefas, configurações, flows e integrações. Nenhuma ação de IAM.",
  },
  "meeting-analyst": {
    title: "Analista de reuniões",
    hint: "Envia, lê, atualiza e reprocessa reuniões, e escreve as tarefas delas.",
  },
  "iam-administrator": {
    title: "Administrador de IAM",
    hint: "Todas as operações de IAM: grupos, policies, anexos, convites e auditoria.",
  },
  "department-scoped-meeting-reader": {
    title: "Leitura por departamento",
    hint:
      "Lê apenas as reuniões cujo atributo department satisfaz a condição. Troque CHANGE-ME antes" +
      " de salvar: enquanto ele estiver lá, a policy não libera nada.",
  },
};

export default function IamPage() {
  const [groups, setGroups] = useState<GroupDto[]>([]);
  const [policies, setPolicies] = useState<PolicyDto[]>([]);
  const [templates, setTemplates] = useState<PolicyTemplateDto[]>([]);
  const [audit, setAudit] = useState<AuditEventDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // forms
  const [groupName, setGroupName] = useState("");
  const [groupDesc, setGroupDesc] = useState("");
  const [policyName, setPolicyName] = useState("");
  const [policyDoc, setPolicyDoc] = useState(POLICY_PLACEHOLDER);
  const [policyDocValid, setPolicyDocValid] = useState(true);
  const [policyMode, setPolicyMode] = useState<EditorMode>("form");
  const [attachPolicyId, setAttachPolicyId] = useState("");
  const [attachGroupId, setAttachGroupId] = useState("");
  const [attachUserId, setAttachUserId] = useState("");
  const [memberGroupId, setMemberGroupId] = useState("");
  const [memberUserId, setMemberUserId] = useState("");
  // editing an existing policy (PUT /iam/policies/{id} → new version)
  const [editPolicyId, setEditPolicyId] = useState<string | null>(null);
  const [editPolicyDoc, setEditPolicyDoc] = useState("");
  const [editPolicyValid, setEditPolicyValid] = useState(true);
  const [editPolicyMode, setEditPolicyMode] = useState<EditorMode>("form");
  // simulator (US43) — kept out of `handle` on purpose: it is a read, and refreshing the whole
  // page after it would wipe the very answer the user asked for.
  const [simUserId, setSimUserId] = useState("");
  const [simAction, setSimAction] = useState("");
  const [simResource, setSimResource] = useState("");
  const [simContext, setSimContext] = useState<ContextPair[]>([{ key: "", value: "" }]);
  const [simResult, setSimResult] = useState<SimulationDto | null>(null);
  const [simError, setSimError] = useState<string | null>(null);
  const [simRunning, setSimRunning] = useState(false);
  // permission boundary (US44) — same reasoning as the simulator: it is its own piece of state,
  // because a full refresh after a read would throw away the answer that was just asked for.
  const [boundaryUserId, setBoundaryUserId] = useState("");
  const [boundaryPolicyId, setBoundaryPolicyId] = useState("");
  const [boundary, setBoundary] = useState<PermissionBoundaryDto | null>(null);
  const [boundaryError, setBoundaryError] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setError(null);
    try {
      const [g, p, t, a] = await Promise.all([
        listGroups(),
        listPolicies(),
        listPolicyTemplates(),
        listAuditEvents(50),
      ]);
      setGroups(g);
      setPolicies(p);
      setTemplates(t);
      setAudit(a);
    } catch (err) {
      setError(toMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh().catch(() => undefined);
  }, []);

  async function handle(action: () => Promise<unknown>) {
    setError(null);
    try {
      await action();
      await refresh();
    } catch (err) {
      setError(toMessage(err));
    }
  }

  async function runSimulation() {
    setSimError(null);
    setSimResult(null);
    setSimRunning(true);
    try {
      const context: Record<string, string> = {};
      for (const pair of simContext) {
        if (pair.key.trim()) context[pair.key.trim()] = pair.value;
      }
      setSimResult(
        await simulatePolicy({
          userId: simUserId.trim(),
          action: simAction.trim(),
          resource: simResource.trim(),
          context,
        }),
      );
    } catch (err) {
      setSimError(toMessage(err));
    } finally {
      setSimRunning(false);
    }
  }

  /** US44 — reads the cap of the user in the field. A user with none answers, explicitly, none. */
  async function loadBoundary() {
    setBoundaryError(null);
    setBoundary(null);
    try {
      setBoundary(await getPermissionBoundary(boundaryUserId.trim()));
    } catch (err) {
      setBoundaryError(toMessage(err));
    }
  }

  /**
   * US44 — a write followed by the read that shows what it did. The re-read is not cosmetic: the
   * API refuses a boundary on the Root and on the caller itself, so the only honest confirmation
   * that a write landed is asking the server what the boundary is now.
   */
  async function runBoundary(action: () => Promise<unknown>) {
    setBoundaryError(null);
    try {
      await action();
      await loadBoundary();
    } catch (err) {
      setBoundaryError(toMessage(err));
    }
  }

  /**
   * US41 — loads a template into the create form. Nothing is created here: it is a starting
   * document, and "Criar policy" sends it through the same `POST /iam/policies` a hand-written one
   * goes through. The name is only pre-filled when the field is still empty, so a name already
   * typed is never overwritten.
   */
  function applyTemplate(template: PolicyTemplateDto) {
    setPolicyDoc(JSON.stringify(template.document, null, 2));
    setPolicyDocValid(true);
    setPolicyMode("form");
    setPolicyName((current) => (current.trim() ? current : template.id));
  }

  function updateContextPair(index: number, patch: Partial<ContextPair>) {
    setSimContext((pairs) => pairs.map((p, i) => (i === index ? { ...p, ...patch } : p)));
  }

  const simReady = Boolean(simUserId.trim() && simAction.trim() && simResource.trim());

  if (loading) {
    return <p className="text-sm text-slate-500">Carregando IAM…</p>;
  }

  return (
    <div className="space-y-10">
      <header>
        <h1 className="text-2xl font-semibold">IAM</h1>
        <p className="text-sm text-slate-500">
          Identity & Access Management estilo AWS. Você é Root deste tenant; novas Users, Groups e
          Policies controlam o que cada pessoa pode fazer.
        </p>
      </header>

      {error && (
        <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      {/* ===== Corporate Domain (US32) ===== */}
      <CorporateDomainCard />

      {/* ===== Invitations (US06) ===== */}
      <InvitationCard />

      {/* ===== Groups ===== */}
      <section className="space-y-3">
        <h2 className="text-lg font-medium">Groups</h2>
        <form
          className="flex flex-wrap gap-2 text-sm"
          onSubmit={(e) => {
            e.preventDefault();
            if (!groupName.trim()) return;
            void handle(async () => {
              await createGroup(groupName.trim(), groupDesc.trim() || undefined);
              setGroupName("");
              setGroupDesc("");
            });
          }}
        >
          <input
            value={groupName}
            onChange={(e) => setGroupName(e.target.value)}
            placeholder="nome (ex: sales-team)"
            className="rounded-md border border-slate-300 px-3 py-1.5"
          />
          <input
            value={groupDesc}
            onChange={(e) => setGroupDesc(e.target.value)}
            placeholder="descrição"
            className="rounded-md border border-slate-300 px-3 py-1.5 flex-1 min-w-[200px]"
          />
          <button
            type="submit"
            className="rounded-md bg-slate-900 px-3 py-1.5 text-white hover:bg-slate-800"
          >
            Criar grupo
          </button>
        </form>

        {groups.length === 0 ? (
          <p className="text-sm text-slate-500">Nenhum grupo criado ainda.</p>
        ) : (
          <ul className="divide-y divide-slate-200 text-sm">
            {groups.map((g) => (
              <li key={g.id} className="flex items-start justify-between py-2">
                <div>
                  <div className="font-medium">{g.name}</div>
                  {g.description && <div className="text-xs text-slate-500">{g.description}</div>}
                  <div className="text-xs text-slate-400">{g.id}</div>
                </div>
                <button
                  type="button"
                  className="text-xs text-red-600 hover:underline"
                  onClick={() => {
                    void handle(() => deleteGroup(g.id));
                  }}
                >
                  excluir
                </button>
              </li>
            ))}
          </ul>
        )}

        <details className="text-sm">
          <summary className="cursor-pointer text-slate-600">Adicionar membro a um grupo</summary>
          <form
            className="mt-2 flex flex-wrap gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (!memberGroupId || !memberUserId) return;
              void handle(async () => {
                await addGroupMember(memberGroupId.trim(), memberUserId.trim());
                setMemberUserId("");
              });
            }}
          >
            <input
              value={memberGroupId}
              onChange={(e) => setMemberGroupId(e.target.value)}
              placeholder="group id"
              className="rounded-md border border-slate-300 px-3 py-1.5"
            />
            <input
              value={memberUserId}
              onChange={(e) => setMemberUserId(e.target.value)}
              placeholder="user id"
              className="rounded-md border border-slate-300 px-3 py-1.5"
            />
            <button
              type="submit"
              className="rounded-md border border-slate-300 px-3 py-1.5 hover:bg-slate-50"
            >
              Adicionar
            </button>
            <button
              type="button"
              className="rounded-md border border-slate-300 px-3 py-1.5 hover:bg-slate-50"
              onClick={() => {
                if (!memberGroupId || !memberUserId) return;
                void handle(() => removeGroupMember(memberGroupId.trim(), memberUserId.trim()));
              }}
            >
              Remover
            </button>
          </form>
        </details>
      </section>

      {/* ===== Policy templates (US41) ===== */}
      <section className="space-y-3">
        <h2 className="text-lg font-medium">Modelos de policy</h2>
        <p className="text-sm text-slate-500">
          Pontos de partida embutidos, já com os ARNs deste tenant. Carregar um modelo apenas
          preenche o editor abaixo — a policy só passa a existir quando você a cria, pelo mesmo
          caminho de uma escrita à mão.
        </p>
        {templates.length === 0 ? (
          <p className="text-sm text-slate-500">Nenhum modelo disponível.</p>
        ) : (
          <ul className="grid gap-2 text-sm md:grid-cols-2">
            {templates.map((t) => {
              const copy = TEMPLATE_COPY[t.id] ?? { title: t.id, hint: t.description };
              return (
                <li
                  key={t.id}
                  className="flex items-start justify-between gap-3 rounded-md border border-slate-200 p-3"
                >
                  <div>
                    <div className="font-medium">{copy.title}</div>
                    <div className="text-xs text-slate-500">{copy.hint}</div>
                    <div className="font-mono text-xs text-slate-400">{t.id}</div>
                  </div>
                  <button
                    type="button"
                    className="shrink-0 rounded-md border border-slate-300 px-3 py-1.5 text-xs hover:bg-slate-50"
                    onClick={() => applyTemplate(t)}
                  >
                    Usar modelo
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {/* ===== Policies ===== */}
      <section className="space-y-3">
        <h2 className="text-lg font-medium">Policies</h2>
        <form
          className="space-y-2 text-sm"
          onSubmit={(e) => {
            e.preventDefault();
            if (!policyName.trim()) return;
            // Defense in depth: the button is already disabled when invalid,
            // but we still re-parse before sending — UX > backend round-trip.
            let parsed: unknown;
            try {
              parsed = JSON.parse(policyDoc);
            } catch {
              setError("Policy document deve ser um JSON válido.");
              return;
            }
            void handle(async () => {
              await createPolicy(policyName.trim(), parsed);
              setPolicyName("");
              setPolicyDoc(POLICY_PLACEHOLDER);
              setPolicyDocValid(true);
            });
          }}
        >
          <input
            value={policyName}
            onChange={(e) => setPolicyName(e.target.value)}
            placeholder="nome da policy (ex: meeting-readonly)"
            className="w-full rounded-md border border-slate-300 px-3 py-1.5"
          />
          <EditorModeToggle mode={policyMode} onChange={setPolicyMode} />
          <PolicyDocumentEditor
            mode={policyMode}
            value={policyDoc}
            onChange={(next, isValid) => {
              setPolicyDoc(next);
              setPolicyDocValid(isValid);
            }}
            height={400}
          />
          <button
            type="submit"
            disabled={!policyName.trim() || !policyDocValid}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-400"
          >
            Criar policy
          </button>
        </form>

        {policies.length === 0 ? (
          <p className="text-sm text-slate-500">Nenhuma policy criada.</p>
        ) : (
          <ul className="divide-y divide-slate-200 text-sm">
            {policies.map((p) => (
              <li key={p.id} className="py-2">
                <div className="flex items-start justify-between">
                  <div>
                    <div className="font-medium">
                      {p.name}{" "}
                      <span className="text-xs text-slate-400">v{p.currentVersion}</span>
                    </div>
                    {p.description && (
                      <div className="text-xs text-slate-500">{p.description}</div>
                    )}
                    <div className="text-xs text-slate-400">{p.id}</div>
                  </div>
                  <div className="flex gap-3">
                    <button
                      type="button"
                      className="text-xs text-slate-600 hover:underline"
                      onClick={() => {
                        if (editPolicyId === p.id) {
                          setEditPolicyId(null);
                        } else {
                          setEditPolicyId(p.id);
                          setEditPolicyDoc(JSON.stringify(p.document, null, 2));
                          setEditPolicyValid(true);
                          setEditPolicyMode("form");
                        }
                      }}
                    >
                      {editPolicyId === p.id ? "cancelar" : "editar"}
                    </button>
                    <button
                      type="button"
                      className="text-xs text-red-600 hover:underline"
                      onClick={() => {
                        void handle(() => deletePolicy(p.id));
                      }}
                    >
                      excluir
                    </button>
                  </div>
                </div>
                {editPolicyId === p.id ? (
                  <div className="mt-2 space-y-2">
                    <EditorModeToggle mode={editPolicyMode} onChange={setEditPolicyMode} />
                    <PolicyDocumentEditor
                      mode={editPolicyMode}
                      value={editPolicyDoc}
                      onChange={(next, isValid) => {
                        setEditPolicyDoc(next);
                        setEditPolicyValid(isValid);
                      }}
                      height={320}
                    />
                    <button
                      type="button"
                      disabled={!editPolicyValid}
                      className="rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-400"
                      onClick={() => {
                        let parsed: unknown;
                        try {
                          parsed = JSON.parse(editPolicyDoc);
                        } catch {
                          setError("Policy document deve ser um JSON válido.");
                          return;
                        }
                        void handle(async () => {
                          await updatePolicyDocument(p.id, parsed);
                          setEditPolicyId(null);
                        });
                      }}
                    >
                      Salvar alterações (nova versão)
                    </button>
                  </div>
                ) : (
                  <pre className="mt-2 overflow-x-auto rounded-md bg-slate-50 p-2 text-xs">
                    {JSON.stringify(p.document, null, 2)}
                  </pre>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ===== Attachments ===== */}
      <section className="space-y-3">
        <h2 className="text-lg font-medium">Anexar policies</h2>
        <div className="grid gap-4 text-sm md:grid-cols-2">
          <div className="space-y-2 rounded-md border border-slate-200 p-3">
            <h3 className="font-medium">A um grupo</h3>
            <input
              value={attachPolicyId}
              onChange={(e) => setAttachPolicyId(e.target.value)}
              placeholder="policy id"
              className="w-full rounded-md border border-slate-300 px-3 py-1.5"
            />
            <input
              value={attachGroupId}
              onChange={(e) => setAttachGroupId(e.target.value)}
              placeholder="group id"
              className="w-full rounded-md border border-slate-300 px-3 py-1.5"
            />
            <div className="flex gap-2">
              <button
                type="button"
                className="rounded-md border border-slate-300 px-3 py-1.5 hover:bg-slate-50"
                onClick={() => {
                  if (!attachPolicyId || !attachGroupId) return;
                  void handle(() =>
                    attachPolicyToGroup(attachPolicyId.trim(), attachGroupId.trim()),
                  );
                }}
              >
                Anexar
              </button>
              <button
                type="button"
                className="rounded-md border border-slate-300 px-3 py-1.5 hover:bg-slate-50"
                onClick={() => {
                  if (!attachPolicyId || !attachGroupId) return;
                  void handle(() =>
                    detachPolicyFromGroup(attachPolicyId.trim(), attachGroupId.trim()),
                  );
                }}
              >
                Remover
              </button>
            </div>
          </div>

          <div className="space-y-2 rounded-md border border-slate-200 p-3">
            <h3 className="font-medium">A um usuário</h3>
            <input
              value={attachPolicyId}
              onChange={(e) => setAttachPolicyId(e.target.value)}
              placeholder="policy id (mesmo campo acima)"
              className="w-full rounded-md border border-slate-300 px-3 py-1.5"
            />
            <input
              value={attachUserId}
              onChange={(e) => setAttachUserId(e.target.value)}
              placeholder="user id"
              className="w-full rounded-md border border-slate-300 px-3 py-1.5"
            />
            <div className="flex gap-2">
              <button
                type="button"
                className="rounded-md border border-slate-300 px-3 py-1.5 hover:bg-slate-50"
                onClick={() => {
                  if (!attachPolicyId || !attachUserId) return;
                  void handle(() =>
                    attachPolicyToUser(attachPolicyId.trim(), attachUserId.trim()),
                  );
                }}
              >
                Anexar
              </button>
              <button
                type="button"
                className="rounded-md border border-slate-300 px-3 py-1.5 hover:bg-slate-50"
                onClick={() => {
                  if (!attachPolicyId || !attachUserId) return;
                  void handle(() =>
                    detachPolicyFromUser(attachPolicyId.trim(), attachUserId.trim()),
                  );
                }}
              >
                Remover
              </button>
            </div>
          </div>
        </div>
      </section>

      {/* ===== Permission boundary (US44) ===== */}
      <section className="space-y-3">
        <h2 className="text-lg font-medium">Permission boundary</h2>
        <p className="text-sm text-slate-500">
          Uma policy que <strong>limita</strong> o que um usuário pode fazer. A ação só passa se as
          policies do usuário permitirem <em>e</em> o boundary permitir — ele nunca concede. Sem
          boundary o usuário fica sem limite, que é o estado normal. O Root do tenant não pode ser
          limitado, e ninguém define o próprio boundary.
        </p>

        <div className="space-y-2 rounded-md border border-slate-200 p-3 text-sm">
          <div className="grid gap-2 md:grid-cols-2">
            <input
              value={boundaryUserId}
              onChange={(e) => setBoundaryUserId(e.target.value)}
              placeholder="user id"
              className="rounded-md border border-slate-300 px-3 py-1.5"
            />
            <input
              value={boundaryPolicyId}
              onChange={(e) => setBoundaryPolicyId(e.target.value)}
              placeholder="policy id do limite"
              className="rounded-md border border-slate-300 px-3 py-1.5"
            />
          </div>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="rounded-md border border-slate-300 px-3 py-1.5 hover:bg-slate-50"
              onClick={() => {
                if (!boundaryUserId.trim()) return;
                void loadBoundary();
              }}
            >
              Consultar
            </button>
            <button
              type="button"
              className="rounded-md bg-slate-900 px-3 py-1.5 text-white hover:bg-slate-800"
              onClick={() => {
                if (!boundaryUserId.trim() || !boundaryPolicyId.trim()) return;
                void runBoundary(() =>
                  setPermissionBoundary(boundaryUserId.trim(), boundaryPolicyId.trim()),
                );
              }}
            >
              Definir limite
            </button>
            <button
              type="button"
              className="rounded-md border border-slate-300 px-3 py-1.5 hover:bg-slate-50"
              onClick={() => {
                if (!boundaryUserId.trim()) return;
                void runBoundary(() => removePermissionBoundary(boundaryUserId.trim()));
              }}
            >
              Remover limite
            </button>
          </div>

          {boundaryError && (
            <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-red-700">
              {boundaryError}
            </p>
          )}

          {boundary &&
            (boundary.policyId ? (
              <p className="text-slate-600">
                Limitado pela policy{" "}
                <span className="font-medium text-slate-800">{boundary.policyName}</span>{" "}
                <span className="text-slate-400">({boundary.policyId})</span>
              </p>
            ) : (
              <p className="text-slate-600">
                Este usuário não tem permission boundary: sem limite, decidem só as policies dele.
              </p>
            ))}
        </div>
      </section>

      {/* ===== Policy simulator (US43) ===== */}
      <section className="space-y-3">
        <h2 className="text-lg font-medium">Simulador de policy</h2>
        <p className="text-sm text-slate-500">
          Pergunta ao avaliador o que ele decidiria — sem executar a operação — e mostra qual
          statement decidiu. O usuário precisa pertencer a este tenant.
        </p>

        <form
          className="space-y-3 text-sm"
          onSubmit={(e) => {
            e.preventDefault();
            if (!simReady) return;
            void runSimulation();
          }}
        >
          <div className="grid gap-2 md:grid-cols-3">
            <input
              value={simUserId}
              onChange={(e) => setSimUserId(e.target.value)}
              placeholder="user id"
              className="rounded-md border border-slate-300 px-3 py-1.5"
            />
            <input
              value={simAction}
              onChange={(e) => setSimAction(e.target.value)}
              placeholder="ação (ex: meeting:read)"
              className="rounded-md border border-slate-300 px-3 py-1.5"
            />
            <input
              value={simResource}
              onChange={(e) => setSimResource(e.target.value)}
              placeholder="recurso (ex: nora:tenant/…:meeting/…)"
              className="rounded-md border border-slate-300 px-3 py-1.5"
            />
          </div>

          <div className="space-y-2">
            <p className="text-xs text-slate-500">
              Contexto — os atributos que as condições da policy leem (StringEquals, StringIn,
              StringLike, DateGreaterThan, DateLessThan). Chave vazia é ignorada.
            </p>
            {simContext.map((pair, index) => (
              <div key={index} className="flex flex-wrap gap-2">
                <input
                  value={pair.key}
                  onChange={(e) => updateContextPair(index, { key: e.target.value })}
                  placeholder="chave (ex: department)"
                  className="rounded-md border border-slate-300 px-3 py-1.5"
                />
                <input
                  value={pair.value}
                  onChange={(e) => updateContextPair(index, { value: e.target.value })}
                  placeholder="valor (ex: Vendas)"
                  className="rounded-md border border-slate-300 px-3 py-1.5"
                />
              </div>
            ))}
            <button
              type="button"
              className="text-xs text-slate-600 hover:underline"
              onClick={() => setSimContext((pairs) => [...pairs, { key: "", value: "" }])}
            >
              adicionar atributo
            </button>
          </div>

          <button
            type="submit"
            disabled={!simReady || simRunning}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-400"
          >
            {simRunning ? "Simulando…" : "Simular"}
          </button>
        </form>

        {simError && (
          <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {simError}
          </p>
        )}

        {simResult && (
          <div className="space-y-2 rounded-md border border-slate-200 p-3 text-sm">
            <div className="flex items-center gap-2">
              <span
                className={`rounded-md px-2 py-0.5 text-xs font-medium ${
                  simResult.allowed ? "bg-emerald-100 text-emerald-800" : "bg-red-100 text-red-800"
                }`}
              >
                {simResult.allowed ? "Allow" : "Deny"}
              </span>
              <span className="font-mono text-xs text-slate-400">{simResult.reason}</span>
            </div>

            <p className="text-slate-600">{REASON_COPY[simResult.reason]}</p>

            {simResult.policyName ? (
              <p className="text-xs text-slate-500">
                Decidido pelo statement {(simResult.statementIndex ?? 0) + 1} da policy{" "}
                <span className="font-medium text-slate-700">{simResult.policyName}</span>{" "}
                <span className="text-slate-400">({simResult.policyId})</span>
              </p>
            ) : (
              <p className="text-xs text-slate-500">
                Nenhum statement decidiu. Statements avaliados: {simResult.statementsEvaluated}.
              </p>
            )}

            {simResult.boundaryPolicyName && (
              <p className="text-xs text-slate-500">
                Este usuário tem permission boundary:{" "}
                <span className="font-medium text-slate-700">{simResult.boundaryPolicyName}</span>.
                Nada além do que essa policy cobre é permitido, quaisquer que sejam as outras.
              </p>
            )}

            {simResult.statement && (
              <pre className="overflow-x-auto rounded-md bg-slate-50 p-2 text-xs">
                {JSON.stringify(simResult.statement, null, 2)}
              </pre>
            )}
          </div>
        )}
      </section>

      {/* ===== Audit ===== */}
      <section className="space-y-3">
        <h2 className="text-lg font-medium">Auditoria</h2>
        {audit.length === 0 ? (
          <p className="text-sm text-slate-500">Sem eventos.</p>
        ) : (
          <ul className="divide-y divide-slate-200 text-sm">
            {audit.map((e) => (
              <li key={e.id} className="py-2">
                <div className="flex items-baseline justify-between gap-3">
                  <span className="font-mono text-xs text-slate-500">
                    {new Date(e.createdAt).toLocaleString()}
                  </span>
                  <span className="text-xs text-slate-400">{e.actorUserId}</span>
                </div>
                <div className="text-sm">
                  <span className="font-medium">{e.action}</span>{" "}
                  <span className="text-slate-500">
                    {e.targetType} {e.targetId}
                  </span>
                </div>
                {Object.keys(e.payload ?? {}).length > 0 && (
                  <pre className="mt-1 overflow-x-auto rounded-md bg-slate-50 p-2 text-xs">
                    {JSON.stringify(e.payload, null, 2)}
                  </pre>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

/**
 * US42 — the two editors, over one piece of state.
 *
 * The JSON editor is not replaced by the form and never will be: the form refuses to open a
 * document it cannot represent exactly, and the way out of that refusal is this toggle. Both write
 * the same JSON string, so switching tabs mid-edit carries the work across.
 */
function PolicyDocumentEditor({
  mode,
  value,
  onChange,
  height,
}: {
  mode: EditorMode;
  value: string;
  onChange: (value: string, isValid: boolean) => void;
  height: number;
}) {
  if (mode === "form") {
    return <PolicyFormEditor value={value} onChange={onChange} />;
  }
  return <PolicyEditor value={value} onChange={onChange} height={height} />;
}

function EditorModeToggle({
  mode,
  onChange,
}: {
  mode: EditorMode;
  onChange: (mode: EditorMode) => void;
}) {
  const options: { mode: EditorMode; label: string }[] = [
    { mode: "form", label: "Formulário" },
    { mode: "json", label: "JSON" },
  ];
  return (
    <div className="flex gap-1" role="group" aria-label="Modo do editor de policy">
      {options.map((option) => (
        <button
          key={option.mode}
          type="button"
          aria-pressed={mode === option.mode}
          onClick={() => onChange(option.mode)}
          className={
            mode === option.mode
              ? "rounded-md bg-slate-900 px-2 py-1 text-xs text-white"
              : "rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
          }
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

function toMessage(err: unknown): string {
  if (err instanceof ApiRequestError) {
    return `${err.payload?.code ?? err.status}: ${err.message}`;
  }
  if (err instanceof Error) return err.message;
  return "Erro inesperado.";
}
