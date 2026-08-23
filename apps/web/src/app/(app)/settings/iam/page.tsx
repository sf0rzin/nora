"use client";

import { useEffect, useMemo, useState } from "react";
import {
  ApiRequestError,
  type AuditEventDto,
  type DirectoryUserDto,
  type GroupDto,
  type Invite,
  type PermissionBoundaryDto,
  type PolicyDto,
  type PolicyTemplateDto,
  type PolicyVersionDto,
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
  listGroupMembers,
  listGroups,
  listIamUsers,
  listInvites,
  listPolicies,
  listPolicyTemplates,
  listPolicyVersions,
  removeGroupMember,
  removePermissionBoundary,
  setPermissionBoundary,
  simulatePolicy,
  updatePolicyDocument,
} from "@/lib/api/client";
import { buildUserDirectory, type DirectoryUser } from "@/lib/iam/user-directory";
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

  /**
   * The tenant directory (`GET /iam/users`). `null` means the read did not answer — a separate
   * permission from the rest of this screen — and it is what decides whether the four user fields
   * are real pickers or free text with suggestions. A `select` over an incomplete list would lock
   * out whoever is missing from it, so the distinction is kept rather than assumed.
   */
  const [directoryUsers, setDirectoryUsers] = useState<DirectoryUserDto[] | null>(null);
  /**
   * Invites are read here as well as inside `InvitationCard`, and the duplicate request is the
   * point: an accepted invite pairs a user id with the e-mail of the person behind it, which is
   * the label to fall back on when the directory above could not be read.
   */
  const [invites, setInvites] = useState<Invite[]>([]);
  /** Members of the groups that have been expanded, keyed by group id. */
  const [members, setMembers] = useState<Record<string, string[]>>({});
  const [membersError, setMembersError] = useState<Record<string, string>>({});
  const [loadingMembers, setLoadingMembers] = useState<string | null>(null);
  const [openGroupId, setOpenGroupId] = useState<string | null>(null);
  // Two-step deletes. Both buttons fired straight from `onClick` on a screen whose targets are
  // identified by a UUID: one misplaced click removed a group or a policy with no way back.
  const [confirmDeleteGroup, setConfirmDeleteGroup] = useState<string | null>(null);
  const [confirmDeletePolicy, setConfirmDeletePolicy] = useState<string | null>(null);

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
  /**
   * Revision history per policy (US36), loaded only for the one that was opened.
   *
   * `iam_policy_versions` had been written on every create and every edit and read by nothing at
   * all, so the "immutable history" the story promised was a backup: the audit feed records THAT
   * a policy changed and never what it said before. `GET /iam/policies/{id}/versions` is the read
   * that closes it, and this is its consumer.
   */
  const [versions, setVersions] = useState<Record<string, PolicyVersionDto[]>>({});
  const [versionsError, setVersionsError] = useState<Record<string, string>>({});
  const [loadingVersions, setLoadingVersions] = useState<string | null>(null);
  const [openVersionsId, setOpenVersionsId] = useState<string | null>(null);
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
      // Best-effort and deliberately outside the Promise.all: each of these is a separate
      // permission, and an admin who does not hold one must still get the rest of the screen.
      // What is lost is how well the user fields can name somebody, not the page.
      try {
        setDirectoryUsers(await listIamUsers());
      } catch {
        setDirectoryUsers(null);
      }
      try {
        setInvites((await listInvites()).items);
      } catch {
        setInvites([]);
      }
      // Membership of whatever was already expanded: a refresh after "Adicionar" has to show the
      // member that was just added, which is the confirmation the operator never had.
      if (openGroupId) await loadMembers(openGroupId);
    } catch (err) {
      setError(toMessage(err));
    } finally {
      setLoading(false);
    }
  }

  async function loadMembers(groupId: string) {
    setLoadingMembers(groupId);
    setMembersError((curr) => {
      const next = { ...curr };
      delete next[groupId];
      return next;
    });
    try {
      const ids = await listGroupMembers(groupId);
      setMembers((curr) => ({ ...curr, [groupId]: ids }));
    } catch (err) {
      setMembersError((curr) => ({ ...curr, [groupId]: toMessage(err) }));
    } finally {
      setLoadingMembers(null);
    }
  }

  async function loadVersions(policyId: string) {
    setLoadingVersions(policyId);
    setVersionsError((curr) => {
      const next = { ...curr };
      delete next[policyId];
      return next;
    });
    try {
      const list = await listPolicyVersions(policyId);
      setVersions((curr) => ({ ...curr, [policyId]: list }));
    } catch (err) {
      setVersionsError((curr) => ({ ...curr, [policyId]: toMessage(err) }));
    } finally {
      setLoadingVersions(null);
    }
  }

  function toggleVersions(policyId: string) {
    if (openVersionsId === policyId) {
      setOpenVersionsId(null);
      return;
    }
    setOpenVersionsId(policyId);
    // Re-read rather than trust the cache: an edit made since it was opened added a revision, and
    // a history that silently omits the newest one is worse than no history.
    void loadVersions(policyId);
  }

  function toggleGroup(groupId: string) {
    if (openGroupId === groupId) {
      setOpenGroupId(null);
      return;
    }
    setOpenGroupId(groupId);
    setMemberGroupId(groupId);
    if (!(groupId in members)) void loadMembers(groupId);
  }

  useEffect(() => {
    refresh().catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * Everyone this screen can name. Rebuilt whenever any source changes, so expanding a group
   * immediately makes its members nameable in the boundary and simulator fields even on a tenant
   * whose directory read was refused.
   */
  const userDirectory = useMemo(
    () =>
      buildUserDirectory({
        users: directoryUsers ?? undefined,
        invites,
        groupMembers: Object.entries(members).map(([groupId, userIds]) => ({
          groupName: groups.find((g) => g.id === groupId)?.name ?? groupId,
          userIds,
        })),
        auditActors: audit.map((e) => e.actorUserId),
      }),
    [directoryUsers, invites, members, groups, audit],
  );

  /**
   * True when the list above is the whole tenant. Only then may a field REFUSE anything outside
   * it — which is the difference between a picker and a trap.
   */
  const directoryComplete = directoryUsers !== null;

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
    return (
      <p role="status" className="text-sm text-slate-500">
        Carregando IAM…
      </p>
    );
  }

  // A load that failed left the screen showing the error and four empty lists, with no way to try
  // again short of a full reload — on the one screen whose data is a snapshot of who can do what.
  if (error !== null && groups.length === 0 && policies.length === 0) {
    return (
      <div className="space-y-3">
        <h1 className="text-2xl font-semibold">IAM</h1>
        <p
          role="alert"
          className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          Não consegui carregar o IAM: {error}
        </p>
        <button
          type="button"
          className="rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-800"
          data-testid="iam-load-retry"
          onClick={() => {
            void refresh();
          }}
        >
          Tentar de novo
        </button>
      </div>
    );
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
        <p
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          <span>{error}</span>
          <button
            type="button"
            className="rounded-md border border-red-300 px-2 py-1 text-xs hover:bg-red-100"
            data-testid="iam-error-retry"
            onClick={() => {
              void refresh();
            }}
          >
            Tentar de novo
          </button>
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
          <Field id="iam-group-name" label="Nome do grupo">
            <input
              id="iam-group-name"
              value={groupName}
              onChange={(e) => setGroupName(e.target.value)}
              placeholder="ex: sales-team"
              className="rounded-md border border-slate-300 px-3 py-1.5"
            />
          </Field>
          <Field id="iam-group-desc" label="Descrição" className="flex-1 min-w-[200px]">
            <input
              id="iam-group-desc"
              value={groupDesc}
              onChange={(e) => setGroupDesc(e.target.value)}
              placeholder="para que serve este grupo"
              className="w-full rounded-md border border-slate-300 px-3 py-1.5"
            />
          </Field>
          <button
            type="submit"
            className="self-end rounded-md bg-slate-900 px-3 py-1.5 text-white hover:bg-slate-800"
          >
            Criar grupo
          </button>
        </form>

        {groups.length === 0 ? (
          <p className="text-sm text-slate-500">Nenhum grupo criado ainda.</p>
        ) : (
          <ul className="divide-y divide-slate-200 text-sm">
            {groups.map((g) => (
              <li key={g.id} className="py-2">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <div className="font-medium">{g.name}</div>
                    {g.description && <div className="text-xs text-slate-500">{g.description}</div>}
                    <div className="font-mono text-xs text-slate-400">{g.id}</div>
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    <button
                      type="button"
                      className="text-xs text-slate-600 hover:underline"
                      aria-expanded={openGroupId === g.id}
                      data-testid={`group-members-toggle-${g.id}`}
                      onClick={() => toggleGroup(g.id)}
                    >
                      {openGroupId === g.id ? "ocultar membros" : "membros"}
                    </button>
                    {confirmDeleteGroup === g.id ? (
                      <span className="flex items-center gap-2 text-xs">
                        <span className="text-red-700">Excluir o grupo “{g.name}”?</span>
                        <button
                          type="button"
                          className="rounded-md bg-red-600 px-2 py-1 text-white hover:bg-red-700"
                          data-testid={`group-delete-confirm-${g.id}`}
                          onClick={() => {
                            setConfirmDeleteGroup(null);
                            void handle(() => deleteGroup(g.id));
                          }}
                        >
                          Excluir
                        </button>
                        <button
                          type="button"
                          className="text-slate-600 hover:underline"
                          onClick={() => setConfirmDeleteGroup(null)}
                        >
                          cancelar
                        </button>
                      </span>
                    ) : (
                      <button
                        type="button"
                        className="text-xs text-red-600 hover:underline"
                        data-testid={`group-delete-${g.id}`}
                        onClick={() => setConfirmDeleteGroup(g.id)}
                      >
                        excluir
                      </button>
                    )}
                  </div>
                </div>

                {/* Membership. `listGroupMembers` and `GET /iam/groups/{id}/members` had both
                    existed for a while with no screen calling either, so groups were administered
                    blind: nothing confirmed that an addition worked, and removing somebody meant
                    knowing a user id the product never showed. */}
                {openGroupId === g.id && (
                  <div className="mt-2 rounded-md border border-slate-200 p-3">
                    {loadingMembers === g.id ? (
                      <p className="text-xs text-slate-500">Carregando membros…</p>
                    ) : membersError[g.id] ? (
                      <p className="flex items-center gap-2 text-xs text-red-700">
                        <span>{membersError[g.id]}</span>
                        <button
                          type="button"
                          className="underline"
                          onClick={() => void loadMembers(g.id)}
                        >
                          tentar de novo
                        </button>
                      </p>
                    ) : (members[g.id] ?? []).length === 0 ? (
                      <p className="text-xs text-slate-500">Este grupo não tem membros.</p>
                    ) : (
                      <ul className="space-y-1 text-xs">
                        {(members[g.id] ?? []).map((userId) => (
                          <li key={userId} className="flex items-center justify-between gap-3">
                            <span className="min-w-0 truncate">
                              <span className="font-medium">{labelForUser(userDirectory, userId)}</span>{" "}
                              <span className="font-mono text-slate-400">{userId}</span>
                            </span>
                            <button
                              type="button"
                              className="shrink-0 text-red-600 hover:underline"
                              data-testid={`group-member-remove-${userId}`}
                              onClick={() => {
                                void handle(() => removeGroupMember(g.id, userId));
                              }}
                            >
                              remover
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}

        <details className="text-sm">
          <summary className="cursor-pointer text-slate-600">Adicionar membro a um grupo</summary>
          <form
            className="mt-2 flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (!memberGroupId || !memberUserId) return;
              void handle(async () => {
                await addGroupMember(memberGroupId.trim(), memberUserId.trim());
                setMemberUserId("");
              });
            }}
          >
            <GroupSelect
              id="iam-member-group"
              label="Grupo"
              groups={groups}
              value={memberGroupId}
              onChange={setMemberGroupId}
            />
            <UserIdField
              id="iam-member-user"
              label="Usuário"
              directory={userDirectory}
              complete={directoryComplete}
              value={memberUserId}
              onChange={setMemberUserId}
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
          <Field id="iam-policy-name" label="Nome da policy">
            <input
              id="iam-policy-name"
              value={policyName}
              onChange={(e) => setPolicyName(e.target.value)}
              placeholder="ex: meeting-readonly"
              className="w-full rounded-md border border-slate-300 px-3 py-1.5"
            />
          </Field>
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
                      data-testid={`policy-versions-toggle-${p.id}`}
                      onClick={() => toggleVersions(p.id)}
                    >
                      {openVersionsId === p.id ? "fechar histórico" : "histórico"}
                    </button>
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
                    {confirmDeletePolicy === p.id ? (
                      <span className="flex items-center gap-2 text-xs">
                        <span className="text-red-700">Excluir “{p.name}”?</span>
                        <button
                          type="button"
                          className="rounded-md bg-red-600 px-2 py-1 text-white hover:bg-red-700"
                          onClick={() => {
                            setConfirmDeletePolicy(null);
                            void handle(() => deletePolicy(p.id));
                          }}
                        >
                          Excluir
                        </button>
                        <button
                          type="button"
                          className="text-slate-600 hover:underline"
                          onClick={() => setConfirmDeletePolicy(null)}
                        >
                          cancelar
                        </button>
                      </span>
                    ) : (
                      <button
                        type="button"
                        className="text-xs text-red-600 hover:underline"
                        onClick={() => setConfirmDeletePolicy(p.id)}
                      >
                        excluir
                      </button>
                    )}
                  </div>
                </div>
                {openVersionsId === p.id && (
                  <div className="mt-2 rounded-md border border-slate-200 p-3">
                    <h4 className="text-xs font-medium text-slate-600">
                      Histórico — mais recente primeiro
                    </h4>
                    {loadingVersions === p.id ? (
                      <p className="mt-2 text-xs text-slate-500">Carregando histórico…</p>
                    ) : versionsError[p.id] ? (
                      <p className="mt-2 flex items-center gap-2 text-xs text-red-700">
                        <span>{versionsError[p.id]}</span>
                        <button
                          type="button"
                          className="underline"
                          onClick={() => void loadVersions(p.id)}
                        >
                          tentar de novo
                        </button>
                      </p>
                    ) : (versions[p.id] ?? []).length === 0 ? (
                      <p className="mt-2 text-xs text-slate-500">
                        Nenhuma versão registrada para esta policy.
                      </p>
                    ) : (
                      <ul className="mt-2 space-y-2 text-xs">
                        {(versions[p.id] ?? []).map((v) => (
                          <li key={v.version} className="rounded-md bg-slate-50 p-2">
                            <div className="flex flex-wrap items-center justify-between gap-2">
                              <span>
                                <span className="font-medium">v{v.version}</span>{" "}
                                <span className="text-slate-500">
                                  {formatTimestamp(v.createdAt)}
                                </span>{" "}
                                {/* `createdBy` is null when the author has since been deleted;
                                    saying so beats printing a generic "usuário" that reads like
                                    the screen simply did not look. */}
                                <span className="text-slate-400">
                                  {v.createdBy === null
                                    ? "autor removido"
                                    : labelForUser(userDirectory, v.createdBy)}
                                </span>
                              </span>
                              {/* Loads the old document into the editor and stops there. Saving
                                  writes a NEW revision through the ordinary PUT, which is what
                                  keeps the history append-only: a rollback is an edit, not an
                                  erasure of what happened in between. */}
                              <button
                                type="button"
                                className="text-slate-600 hover:underline"
                                data-testid={`policy-version-restore-${p.id}-${v.version}`}
                                onClick={() => {
                                  setEditPolicyId(p.id);
                                  setEditPolicyDoc(JSON.stringify(v.document, null, 2));
                                  setEditPolicyValid(true);
                                  setEditPolicyMode("form");
                                }}
                              >
                                carregar no editor
                              </button>
                            </div>
                            <pre className="mt-1 overflow-x-auto">
                              {JSON.stringify(v.document, null, 2)}
                            </pre>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
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
            <PolicySelect
              id="iam-attach-group-policy"
              label="Policy"
              policies={policies}
              value={attachPolicyId}
              onChange={setAttachPolicyId}
            />
            <GroupSelect
              id="iam-attach-group"
              label="Grupo"
              groups={groups}
              value={attachGroupId}
              onChange={setAttachGroupId}
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
            {/* Same piece of state as the card on the left, on purpose: picking a policy once
                covers both attachments. The label says so — the old placeholder ("mesmo campo
                acima") was the only thing explaining it, and placeholders vanish on typing. */}
            <PolicySelect
              id="iam-attach-user-policy"
              label="Policy (a mesma escolhida ao lado)"
              policies={policies}
              value={attachPolicyId}
              onChange={setAttachPolicyId}
            />
            <UserIdField
              id="iam-attach-user"
              label="Usuário"
              directory={userDirectory}
              complete={directoryComplete}
              value={attachUserId}
              onChange={setAttachUserId}
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
            <UserIdField
              id="iam-boundary-user"
              label="Usuário a limitar"
              directory={userDirectory}
              complete={directoryComplete}
              value={boundaryUserId}
              onChange={setBoundaryUserId}
            />
            <PolicySelect
              id="iam-boundary-policy"
              label="Policy do limite"
              policies={policies}
              value={boundaryPolicyId}
              onChange={setBoundaryPolicyId}
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
            <UserIdField
              id="iam-sim-user"
              label="Usuário"
              directory={userDirectory}
              complete={directoryComplete}
              value={simUserId}
              onChange={setSimUserId}
            />
            <Field id="iam-sim-action" label="Ação">
              <input
                id="iam-sim-action"
                value={simAction}
                onChange={(e) => setSimAction(e.target.value)}
                placeholder="ex: meeting:read"
                className="w-full rounded-md border border-slate-300 px-3 py-1.5"
              />
            </Field>
            <Field id="iam-sim-resource" label="Recurso">
              <input
                id="iam-sim-resource"
                value={simResource}
                onChange={(e) => setSimResource(e.target.value)}
                placeholder="ex: nora:tenant/…:meeting/…"
                className="w-full rounded-md border border-slate-300 px-3 py-1.5"
              />
            </Field>
          </div>

          <div className="space-y-2">
            <p className="text-xs text-slate-500">
              Contexto — os atributos que as condições da policy leem (StringEquals, StringIn,
              StringLike, DateGreaterThan, DateLessThan). Chave vazia é ignorada.
            </p>
            {simContext.map((pair, index) => (
              <div key={index} className="flex flex-wrap items-end gap-2">
                <Field id={`iam-sim-ctx-key-${index}`} label={`Chave ${index + 1}`}>
                  <input
                    id={`iam-sim-ctx-key-${index}`}
                    value={pair.key}
                    onChange={(e) => updateContextPair(index, { key: e.target.value })}
                    placeholder="ex: department"
                    className="w-full rounded-md border border-slate-300 px-3 py-1.5"
                  />
                </Field>
                <Field id={`iam-sim-ctx-value-${index}`} label={`Valor ${index + 1}`}>
                  <input
                    id={`iam-sim-ctx-value-${index}`}
                    value={pair.value}
                    onChange={(e) => updateContextPair(index, { value: e.target.value })}
                    placeholder="ex: Vendas"
                    className="w-full rounded-md border border-slate-300 px-3 py-1.5"
                  />
                </Field>
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

/**
 * Label plus control, and the reason it is a component is that there are sixteen of them.
 *
 * This screen identified every one of its fields by placeholder alone — the only screen in the
 * product that did, while upload, MCP, the Flows side panel, Contexto and the invite card all use
 * `label`/`htmlFor`. A placeholder is not an accessible name: it disappears at the first keystroke
 * and a screen reader announces an unnamed edit box. On the most complex administrative surface
 * there is, where a value pasted into the wrong field attaches a policy to the wrong person, the
 * field's name is not decoration.
 */
function Field({
  id,
  label,
  hint,
  className,
  children,
}: {
  id: string;
  label: string;
  hint?: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={`flex min-w-0 flex-col gap-1 ${className ?? ""}`}>
      <label htmlFor={id} className="text-xs font-medium text-slate-600">
        {label}
      </label>
      {children}
      {hint && <span className="text-xs text-slate-400">{hint}</span>}
    </div>
  );
}

/** Empty option shared by both pickers, so "nothing chosen" is a state and not an empty string. */
const NOTHING_CHOSEN = "— escolha —";

function PolicySelect({
  id,
  label,
  policies,
  value,
  onChange,
}: {
  id: string;
  label: string;
  policies: PolicyDto[];
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <Field id={id} label={label}>
      <select
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-md border border-slate-300 bg-white px-3 py-1.5"
      >
        <option value="">{NOTHING_CHOSEN}</option>
        {policies.map((p) => (
          <option key={p.id} value={p.id}>
            {p.name} (v{p.currentVersion})
          </option>
        ))}
      </select>
    </Field>
  );
}

function GroupSelect({
  id,
  label,
  groups,
  value,
  onChange,
}: {
  id: string;
  label: string;
  groups: GroupDto[];
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <Field id={id} label={label}>
      <select
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-md border border-slate-300 bg-white px-3 py-1.5"
      >
        <option value="">{NOTHING_CHOSEN}</option>
        {groups.map((g) => (
          <option key={g.id} value={g.id}>
            {g.name}
          </option>
        ))}
      </select>
    </Field>
  );
}

/**
 * The person an IAM operation acts on. A real picker when the tenant directory answered, a
 * suggestion list otherwise.
 *
 * The shape follows what is KNOWN, not what is convenient. `GET /iam/users` returns every user in
 * the tenant, so when it answers, a `select` is the honest control: there is no valid id outside
 * the options, and asking somebody to paste a UUID the product only ever showed inside a table is
 * how half of this screen went unused. When that read is refused — it is its own permission — the
 * list degrades to whatever accepted invites, expanded groups and the audit feed know, and that
 * list is provably incomplete, so the field goes back to free text with a `datalist`. A `select`
 * over the incomplete list would lock out exactly the people who are missing from it.
 */
function UserIdField({
  id,
  label,
  directory,
  complete,
  value,
  onChange,
}: {
  id: string;
  label: string;
  directory: DirectoryUser[];
  /** The directory is the whole tenant, so nothing valid is missing from it. */
  complete: boolean;
  value: string;
  onChange: (value: string) => void;
}) {
  const known = directory.find((u) => u.userId === value.trim());

  if (complete) {
    return (
      <Field id={id} label={label} hint={known?.root ? "Root do tenant." : undefined}>
        <select
          id={id}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full rounded-md border border-slate-300 bg-white px-3 py-1.5"
        >
          <option value="">{NOTHING_CHOSEN}</option>
          {directory.map((u) => (
            <option key={u.userId} value={u.userId}>
              {u.root ? `${u.label} (Root)` : u.label}
            </option>
          ))}
        </select>
      </Field>
    );
  }

  return (
    <Field
      id={id}
      label={label}
      hint={
        known
          ? known.label
          : directory.length === 0
            ? "Nenhum usuário conhecido ainda — expanda um grupo ou convide alguém."
            : "Escolha da lista ou cole um id."
      }
    >
      <input
        id={id}
        list={`${id}-options`}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="e-mail ou id"
        className="w-full rounded-md border border-slate-300 px-3 py-1.5"
      />
      <datalist id={`${id}-options`}>
        {directory.map((u) => (
          <option key={u.userId} value={u.userId} label={u.label} />
        ))}
      </datalist>
    </Field>
  );
}

/** An ISO timestamp in the reader's locale, left as it came when it is not a date at all. */
function formatTimestamp(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

/** The friendliest name this screen knows for a user id — the id itself when it knows none. */
function labelForUser(directory: DirectoryUser[], userId: string): string {
  return directory.find((u) => u.userId === userId)?.label ?? "usuário";
}

function toMessage(err: unknown): string {
  if (err instanceof ApiRequestError) {
    return `${err.payload?.code ?? err.status}: ${err.message}`;
  }
  if (err instanceof Error) return err.message;
  return "Erro inesperado.";
}
