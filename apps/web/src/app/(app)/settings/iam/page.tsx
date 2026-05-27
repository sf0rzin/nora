"use client";

import { useEffect, useState } from "react";
import {
  ApiRequestError,
  type AuditEventDto,
  type GroupDto,
  type PolicyDto,
  addGroupMember,
  attachPolicyToGroup,
  attachPolicyToUser,
  createGroup,
  createPolicy,
  deleteGroup,
  deletePolicy,
  detachPolicyFromGroup,
  detachPolicyFromUser,
  listAuditEvents,
  listGroups,
  listPolicies,
  removeGroupMember,
} from "@/lib/api/client";
import PolicyEditor from "@/components/policy-editor";
import CorporateDomainCard from "@/components/corporate-domain-card";
import InvitationCard from "@/components/invitation-card";
import {
  Badge,
  Banner,
  Button,
  Card,
  EmptyState,
  Field,
  Input,
  PageHeader,
  Section,
} from "@/components/core/ui";

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

export default function IamPage() {
  const [groups, setGroups] = useState<GroupDto[]>([]);
  const [policies, setPolicies] = useState<PolicyDto[]>([]);
  const [audit, setAudit] = useState<AuditEventDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // formularios
  const [groupName, setGroupName] = useState("");
  const [groupDesc, setGroupDesc] = useState("");
  const [policyName, setPolicyName] = useState("");
  const [policyDoc, setPolicyDoc] = useState(POLICY_PLACEHOLDER);
  const [policyDocValid, setPolicyDocValid] = useState(true);
  const [attachPolicyId, setAttachPolicyId] = useState("");
  const [attachGroupId, setAttachGroupId] = useState("");
  const [attachUserId, setAttachUserId] = useState("");
  const [memberGroupId, setMemberGroupId] = useState("");
  const [memberUserId, setMemberUserId] = useState("");

  async function refresh() {
    setLoading(true);
    setError(null);
    try {
      const [g, p, a] = await Promise.all([listGroups(), listPolicies(), listAuditEvents(50)]);
      setGroups(g);
      setPolicies(p);
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

  if (loading) {
    return (
      <div>
        <PageHeader
          title="IAM"
          subtitle="Identity & Access Management estilo AWS. Você é Root deste tenant; novas Users, Groups e Policies controlam o que cada pessoa pode fazer."
        />
        <EmptyState>Carregando IAM…</EmptyState>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="IAM"
        subtitle="Identity & Access Management estilo AWS. Você é Root deste tenant; novas Users, Groups e Policies controlam o que cada pessoa pode fazer."
      />

      {error && (
        <div style={{ marginBottom: 20 }}>
          <Banner tone="error">{error}</Banner>
        </div>
      )}

      {/* ===== Corporate Domain (US32) ===== */}
      <CorporateDomainCard />

      {/* ===== Invitations (US06) ===== */}
      <InvitationCard />

      {/* ===== Groups ===== */}
      <Section title="Groups">
        <Card style={{ marginBottom: 16 }}>
          <form
            style={{ display: "flex", flexWrap: "wrap", alignItems: "flex-end", gap: 10 }}
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
            <div style={{ minWidth: 200 }}>
              <Field label="Nome" htmlFor="group-name">
                <Input
                  id="group-name"
                  value={groupName}
                  onChange={(e) => setGroupName(e.target.value)}
                  placeholder="nome (ex: sales-team)"
                />
              </Field>
            </div>
            <div style={{ flex: 1, minWidth: 200 }}>
              <Field label="Descrição" htmlFor="group-desc">
                <Input
                  id="group-desc"
                  value={groupDesc}
                  onChange={(e) => setGroupDesc(e.target.value)}
                  placeholder="descrição"
                />
              </Field>
            </div>
            <div style={{ marginBottom: 16 }}>
              <Button type="submit" variant="primary">
                Criar grupo
              </Button>
            </div>
          </form>
        </Card>

        {groups.length === 0 ? (
          <EmptyState>Nenhum grupo criado ainda.</EmptyState>
        ) : (
          <div
            style={{
              border: "1px solid var(--border)",
              borderRadius: "var(--radius)",
              overflow: "hidden",
              background: "var(--surface)",
            }}
          >
            {groups.map((g, i) => (
              <div
                key={g.id}
                style={{
                  display: "flex",
                  alignItems: "flex-start",
                  justifyContent: "space-between",
                  gap: 12,
                  padding: "12px 16px",
                  borderTop: i === 0 ? "none" : "1px solid var(--border)",
                }}
              >
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 14, fontWeight: 500, color: "var(--ink)" }}>{g.name}</div>
                  {g.description && (
                    <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 2 }}>
                      {g.description}
                    </div>
                  )}
                  <div
                    style={{
                      fontFamily: "var(--mono)",
                      fontSize: 11,
                      color: "var(--muted)",
                      background: "var(--chip)",
                      borderRadius: "var(--radius-sm)",
                      padding: "1px 6px",
                      marginTop: 6,
                      display: "inline-block",
                    }}
                  >
                    {g.id}
                  </div>
                </div>
                <Button
                  variant="danger"
                  size="sm"
                  onClick={() => {
                    void handle(() => deleteGroup(g.id));
                  }}
                >
                  excluir
                </Button>
              </div>
            ))}
          </div>
        )}

        <details style={{ marginTop: 16 }}>
          <summary style={{ cursor: "pointer", fontSize: 13, color: "var(--muted)" }}>
            Adicionar membro a um grupo
          </summary>
          <Card style={{ marginTop: 12 }}>
            <form
              style={{ display: "flex", flexWrap: "wrap", alignItems: "flex-end", gap: 10 }}
              onSubmit={(e) => {
                e.preventDefault();
                if (!memberGroupId || !memberUserId) return;
                void handle(async () => {
                  await addGroupMember(memberGroupId.trim(), memberUserId.trim());
                  setMemberUserId("");
                });
              }}
            >
              <div style={{ minWidth: 200 }}>
                <Field label="Group ID" htmlFor="member-group-id">
                  <Input
                    id="member-group-id"
                    value={memberGroupId}
                    onChange={(e) => setMemberGroupId(e.target.value)}
                    placeholder="group id"
                    style={{ fontFamily: "var(--mono)" }}
                  />
                </Field>
              </div>
              <div style={{ minWidth: 200 }}>
                <Field label="User ID" htmlFor="member-user-id">
                  <Input
                    id="member-user-id"
                    value={memberUserId}
                    onChange={(e) => setMemberUserId(e.target.value)}
                    placeholder="user id"
                    style={{ fontFamily: "var(--mono)" }}
                  />
                </Field>
              </div>
              <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
                <Button type="submit" variant="primary">
                  Adicionar
                </Button>
                <Button
                  onClick={() => {
                    if (!memberGroupId || !memberUserId) return;
                    void handle(() => removeGroupMember(memberGroupId.trim(), memberUserId.trim()));
                  }}
                >
                  Remover
                </Button>
              </div>
            </form>
          </Card>
        </details>
      </Section>

      {/* ===== Policies ===== */}
      <Section title="Policies">
        <Card style={{ marginBottom: 16 }}>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (!policyName.trim()) return;
              // Defesa em profundidade: o botao ja é desabilitado quando invalido,
              // mas ainda assim re-parseamos antes de enviar — UX > backend round-trip.
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
            <Field label="Nome da policy" htmlFor="policy-name">
              <Input
                id="policy-name"
                value={policyName}
                onChange={(e) => setPolicyName(e.target.value)}
                placeholder="nome da policy (ex: meeting-readonly)"
              />
            </Field>
            <Field label="Documento (JSON)" htmlFor="policy-doc">
              <PolicyEditor
                value={policyDoc}
                onChange={(next, isValid) => {
                  setPolicyDoc(next);
                  setPolicyDocValid(isValid);
                }}
                height={400}
              />
            </Field>
            <Button type="submit" variant="primary" disabled={!policyName.trim() || !policyDocValid}>
              Criar policy
            </Button>
          </form>
        </Card>

        {policies.length === 0 ? (
          <EmptyState>Nenhuma policy criada.</EmptyState>
        ) : (
          <div
            style={{
              border: "1px solid var(--border)",
              borderRadius: "var(--radius)",
              overflow: "hidden",
              background: "var(--surface)",
            }}
          >
            {policies.map((p, i) => (
              <div
                key={p.id}
                style={{
                  padding: "12px 16px",
                  borderTop: i === 0 ? "none" : "1px solid var(--border)",
                }}
              >
                <div
                  style={{
                    display: "flex",
                    alignItems: "flex-start",
                    justifyContent: "space-between",
                    gap: 12,
                  }}
                >
                  <div style={{ minWidth: 0 }}>
                    <div
                      style={{
                        fontSize: 14,
                        fontWeight: 500,
                        color: "var(--ink)",
                        display: "flex",
                        alignItems: "center",
                        gap: 8,
                      }}
                    >
                      {p.name}
                      <Badge>v{p.currentVersion}</Badge>
                    </div>
                    {p.description && (
                      <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 2 }}>
                        {p.description}
                      </div>
                    )}
                    <div
                      style={{
                        fontFamily: "var(--mono)",
                        fontSize: 11,
                        color: "var(--muted)",
                        background: "var(--chip)",
                        borderRadius: "var(--radius-sm)",
                        padding: "1px 6px",
                        marginTop: 6,
                        display: "inline-block",
                      }}
                    >
                      {p.id}
                    </div>
                  </div>
                  <Button
                    variant="danger"
                    size="sm"
                    onClick={() => {
                      void handle(() => deletePolicy(p.id));
                    }}
                  >
                    excluir
                  </Button>
                </div>
                <pre
                  style={{
                    marginTop: 10,
                    overflowX: "auto",
                    borderRadius: "var(--radius-sm)",
                    background: "var(--chip)",
                    padding: 10,
                    fontFamily: "var(--mono)",
                    fontSize: 12,
                    color: "var(--ink)",
                  }}
                >
                  {JSON.stringify(p.document, null, 2)}
                </pre>
              </div>
            ))}
          </div>
        )}
      </Section>

      {/* ===== Attachments ===== */}
      <Section title="Anexar policies">
        <div
          style={{
            display: "grid",
            gap: 16,
            gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))",
          }}
        >
          <Card>
            <h3 style={{ fontSize: 14, fontWeight: 600, color: "var(--ink)", margin: "0 0 14px" }}>
              A um grupo
            </h3>
            <Field label="Policy ID" htmlFor="attach-group-policy-id">
              <Input
                id="attach-group-policy-id"
                value={attachPolicyId}
                onChange={(e) => setAttachPolicyId(e.target.value)}
                placeholder="policy id"
                style={{ fontFamily: "var(--mono)" }}
              />
            </Field>
            <Field label="Group ID" htmlFor="attach-group-id">
              <Input
                id="attach-group-id"
                value={attachGroupId}
                onChange={(e) => setAttachGroupId(e.target.value)}
                placeholder="group id"
                style={{ fontFamily: "var(--mono)" }}
              />
            </Field>
            <div style={{ display: "flex", gap: 8 }}>
              <Button
                variant="primary"
                onClick={() => {
                  if (!attachPolicyId || !attachGroupId) return;
                  void handle(() =>
                    attachPolicyToGroup(attachPolicyId.trim(), attachGroupId.trim()),
                  );
                }}
              >
                Anexar
              </Button>
              <Button
                onClick={() => {
                  if (!attachPolicyId || !attachGroupId) return;
                  void handle(() =>
                    detachPolicyFromGroup(attachPolicyId.trim(), attachGroupId.trim()),
                  );
                }}
              >
                Remover
              </Button>
            </div>
          </Card>

          <Card>
            <h3 style={{ fontSize: 14, fontWeight: 600, color: "var(--ink)", margin: "0 0 14px" }}>
              A um usuário
            </h3>
            <Field label="Policy ID" htmlFor="attach-user-policy-id">
              <Input
                id="attach-user-policy-id"
                value={attachPolicyId}
                onChange={(e) => setAttachPolicyId(e.target.value)}
                placeholder="policy id (mesmo campo acima)"
                style={{ fontFamily: "var(--mono)" }}
              />
            </Field>
            <Field label="User ID" htmlFor="attach-user-id">
              <Input
                id="attach-user-id"
                value={attachUserId}
                onChange={(e) => setAttachUserId(e.target.value)}
                placeholder="user id"
                style={{ fontFamily: "var(--mono)" }}
              />
            </Field>
            <div style={{ display: "flex", gap: 8 }}>
              <Button
                variant="primary"
                onClick={() => {
                  if (!attachPolicyId || !attachUserId) return;
                  void handle(() => attachPolicyToUser(attachPolicyId.trim(), attachUserId.trim()));
                }}
              >
                Anexar
              </Button>
              <Button
                onClick={() => {
                  if (!attachPolicyId || !attachUserId) return;
                  void handle(() =>
                    detachPolicyFromUser(attachPolicyId.trim(), attachUserId.trim()),
                  );
                }}
              >
                Remover
              </Button>
            </div>
          </Card>
        </div>
      </Section>

      {/* ===== Audit ===== */}
      <Section title="Auditoria">
        {audit.length === 0 ? (
          <EmptyState>Sem eventos.</EmptyState>
        ) : (
          <div
            style={{
              border: "1px solid var(--border)",
              borderRadius: "var(--radius)",
              overflow: "hidden",
              background: "var(--surface)",
            }}
          >
            {audit.map((e, i) => (
              <div
                key={e.id}
                style={{
                  padding: "12px 16px",
                  borderTop: i === 0 ? "none" : "1px solid var(--border)",
                }}
              >
                <div
                  style={{
                    display: "flex",
                    alignItems: "baseline",
                    justifyContent: "space-between",
                    gap: 12,
                  }}
                >
                  <span style={{ fontFamily: "var(--mono)", fontSize: 11.5, color: "var(--muted)" }}>
                    {new Date(e.createdAt).toLocaleString()}
                  </span>
                  <span
                    style={{ fontFamily: "var(--mono)", fontSize: 11.5, color: "var(--muted)" }}
                  >
                    {e.actorUserId}
                  </span>
                </div>
                <div style={{ fontSize: 13.5, marginTop: 3 }}>
                  <span style={{ fontWeight: 500, color: "var(--ink)" }}>{e.action}</span>{" "}
                  <span style={{ color: "var(--muted)" }}>
                    {e.targetType} {e.targetId}
                  </span>
                </div>
                {Object.keys(e.payload ?? {}).length > 0 && (
                  <pre
                    style={{
                      marginTop: 8,
                      overflowX: "auto",
                      borderRadius: "var(--radius-sm)",
                      background: "var(--chip)",
                      padding: 10,
                      fontFamily: "var(--mono)",
                      fontSize: 12,
                      color: "var(--ink)",
                    }}
                  >
                    {JSON.stringify(e.payload, null, 2)}
                  </pre>
                )}
              </div>
            ))}
          </div>
        )}
      </Section>
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
