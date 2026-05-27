"use client";

import { useEffect, useState } from "react";
import {
  listProjects,
  createProject,
  updateProject,
  deleteProject,
  type Project,
  type ProjectStatus,
  ApiRequestError,
} from "@/lib/api/client";
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
  Textarea,
} from "@/components/core/ui";

const STATUS_LABEL: Record<ProjectStatus, string> = {
  ACTIVE: "Ativo",
  ARCHIVED: "Arquivado",
};

function errMessage(e: unknown, fallback: string): string {
  return e instanceof ApiRequestError ? e.message : fallback;
}

export default function ProjectsPage() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Create form
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [creating, setCreating] = useState(false);

  // Inline rename
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState("");

  // Per-row busy state (toggle/delete)
  const [busyId, setBusyId] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    listProjects()
      .then((items) => {
        if (active) setProjects(items);
      })
      .catch((e) => {
        if (active) setError(errMessage(e, "Falha ao carregar projetos."));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || creating) return;
    setCreating(true);
    setError(null);
    try {
      const created = await createProject(trimmed, description.trim() || undefined);
      setProjects((curr) => [created, ...curr]);
      setName("");
      setDescription("");
    } catch (err) {
      setError(errMessage(err, "Falha ao criar projeto."));
    } finally {
      setCreating(false);
    }
  }

  function beginEdit(p: Project) {
    setEditingId(p.id);
    setEditName(p.name);
  }

  function cancelEdit() {
    setEditingId(null);
    setEditName("");
  }

  async function saveEdit(p: Project) {
    const trimmed = editName.trim();
    if (!trimmed || trimmed === p.name) {
      cancelEdit();
      return;
    }
    const previous = projects;
    setProjects((curr) => curr.map((x) => (x.id === p.id ? { ...x, name: trimmed } : x)));
    cancelEdit();
    try {
      const updated = await updateProject(p.id, { name: trimmed });
      setProjects((curr) => curr.map((x) => (x.id === p.id ? updated : x)));
    } catch (err) {
      setProjects(previous);
      setError(errMessage(err, "Falha ao renomear projeto."));
    }
  }

  async function toggleStatus(p: Project) {
    if (busyId) return;
    const next: ProjectStatus = p.status === "ACTIVE" ? "ARCHIVED" : "ACTIVE";
    const previous = projects;
    setBusyId(p.id);
    setProjects((curr) => curr.map((x) => (x.id === p.id ? { ...x, status: next } : x)));
    try {
      const updated = await updateProject(p.id, { status: next });
      setProjects((curr) => curr.map((x) => (x.id === p.id ? updated : x)));
    } catch (err) {
      setProjects(previous);
      setError(errMessage(err, "Falha ao atualizar status."));
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(p: Project) {
    if (busyId) return;
    if (!window.confirm(`Excluir o projeto "${p.name}"? As reuniões vinculadas serão desvinculadas.`)) {
      return;
    }
    const previous = projects;
    setBusyId(p.id);
    setProjects((curr) => curr.filter((x) => x.id !== p.id));
    try {
      await deleteProject(p.id);
    } catch (err) {
      setProjects(previous);
      setError(errMessage(err, "Falha ao excluir projeto."));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div>
      <PageHeader
        title="Projetos"
        subtitle="Agrupe suas reuniões em projetos para acompanhar o contexto de cada iniciativa."
      />

      {error && (
        <div style={{ marginBottom: 16 }}>
          <Banner tone="error">{error}</Banner>
        </div>
      )}

      <Section title="Novo projeto">
        <Card>
          <form onSubmit={handleCreate}>
            <Field label="Nome" required htmlFor="project-name">
              <Input
                id="project-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Ex.: Implantação Cliente ACME"
                maxLength={200}
                required
              />
            </Field>
            <Field label="Descrição" htmlFor="project-description" hint="Opcional.">
              <Textarea
                id="project-description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Contexto do projeto, objetivos, escopo…"
                rows={3}
                maxLength={5000}
              />
            </Field>
            <Button type="submit" variant="primary" disabled={creating || !name.trim()}>
              {creating ? "Criando…" : "Criar projeto"}
            </Button>
          </form>
        </Card>
      </Section>

      <Section title="Seus projetos">
        {loading ? (
          <EmptyState>Carregando…</EmptyState>
        ) : projects.length === 0 ? (
          <EmptyState>
            <div style={{ fontWeight: 500, color: "var(--ink)", marginBottom: 4 }}>
              Nenhum projeto ainda.
            </div>
            Crie seu primeiro projeto acima para começar a organizar suas reuniões.
          </EmptyState>
        ) : (
          <div
            style={{
              border: "1px solid var(--border)",
              borderRadius: "var(--radius)",
              overflow: "hidden",
              background: "var(--surface)",
            }}
          >
            {projects.map((p, i) => (
              <div
                key={p.id}
                style={{
                  display: "flex",
                  flexWrap: "wrap",
                  alignItems: "center",
                  gap: 12,
                  padding: "12px 16px",
                  borderTop: i === 0 ? "none" : "1px solid var(--border)",
                }}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  {editingId === p.id ? (
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                      <input
                        autoFocus
                        className="nora-input"
                        style={{ flex: 1, minWidth: 180 }}
                        value={editName}
                        maxLength={200}
                        onChange={(e) => setEditName(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") saveEdit(p);
                          if (e.key === "Escape") cancelEdit();
                        }}
                      />
                      <Button variant="primary" size="sm" onClick={() => saveEdit(p)}>
                        Salvar
                      </Button>
                      <Button size="sm" onClick={cancelEdit}>
                        Cancelar
                      </Button>
                    </div>
                  ) : (
                    <>
                      <div
                        style={{
                          display: "flex",
                          alignItems: "center",
                          gap: 8,
                          fontSize: 14,
                          fontWeight: 500,
                          color: "var(--ink)",
                        }}
                      >
                        {p.name}
                        <Badge tone={p.status === "ACTIVE" ? "success" : "default"}>
                          {STATUS_LABEL[p.status]}
                        </Badge>
                      </div>
                      {p.description && (
                        <div style={{ marginTop: 4, fontSize: 12.5, color: "var(--muted)" }}>
                          {p.description}
                        </div>
                      )}
                    </>
                  )}
                </div>

                {editingId !== p.id && (
                  <div style={{ display: "flex", alignItems: "center", gap: 8, flexShrink: 0 }}>
                    <Button size="sm" onClick={() => beginEdit(p)}>
                      Renomear
                    </Button>
                    <Button size="sm" onClick={() => toggleStatus(p)} disabled={busyId === p.id}>
                      {p.status === "ACTIVE" ? "Arquivar" : "Reativar"}
                    </Button>
                    <Button
                      variant="danger"
                      size="sm"
                      onClick={() => handleDelete(p)}
                      disabled={busyId === p.id}
                    >
                      Excluir
                    </Button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </Section>
    </div>
  );
}
