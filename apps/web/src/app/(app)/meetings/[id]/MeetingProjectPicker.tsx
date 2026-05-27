"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  listProjects,
  assignMeetingToProject,
  type Project,
  ApiRequestError,
} from "@/lib/api/client";
import { Banner, Card, Select } from "@/components/core/ui";

interface Props {
  meetingId: string;
  currentProjectId: string | null;
  currentProjectName: string | null;
}

/**
 * Picker de projeto na tela de detalhe da reunião. Carrega os projetos do tenant
 * e permite vincular / desvincular a meeting via PUT /meetings/{id}/project.
 * Após a mudança, `router.refresh()` revalida o server component (projectName).
 */
export default function MeetingProjectPicker({
  meetingId,
  currentProjectId,
  currentProjectName,
}: Props) {
  const router = useRouter();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Estado otimista do select; sincroniza com a prop quando o server revalida.
  const [selected, setSelected] = useState<string>(currentProjectId ?? "");

  useEffect(() => {
    setSelected(currentProjectId ?? "");
  }, [currentProjectId]);

  useEffect(() => {
    let active = true;
    listProjects()
      .then((items) => {
        if (active) setProjects(items);
      })
      .catch((e) => {
        if (active) setError(e instanceof ApiRequestError ? e.message : "Falha ao carregar projetos.");
      })
      .finally(() => {
        if (active) setLoaded(true);
      });
    return () => {
      active = false;
    };
  }, []);

  async function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const value = e.target.value;
    const nextId = value === "" ? null : value;
    if (nextId === (currentProjectId ?? null)) {
      setSelected(value);
      return;
    }
    const previous = selected;
    setSelected(value);
    setSaving(true);
    setError(null);
    try {
      await assignMeetingToProject(meetingId, nextId);
      router.refresh();
    } catch (err) {
      setSelected(previous);
      setError(err instanceof ApiRequestError ? err.message : "Falha ao atualizar projeto.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={{ marginBottom: 28 }}>
      <Card>
        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 12,
          }}
        >
          <div style={{ minWidth: 0 }}>
            <div
              style={{
                fontSize: 10.5,
                letterSpacing: "0.08em",
                textTransform: "uppercase",
                color: "var(--muted)",
                fontWeight: 500,
                marginBottom: 4,
              }}
            >
              Projeto
            </div>
            <div style={{ fontSize: 14, fontWeight: 500, color: "var(--ink)" }}>
              {currentProjectName ?? "Sem projeto"}
            </div>
          </div>

          <div style={{ display: "flex", alignItems: "center", gap: 8, flexShrink: 0 }}>
            <Select
              value={selected}
              onChange={handleChange}
              disabled={!loaded || saving}
              aria-label="Atribuir reunião a um projeto"
              style={{ width: "auto", minWidth: 180, fontSize: 13 }}
            >
              <option value="">Sem projeto</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                  {p.status === "ARCHIVED" ? " (arquivado)" : ""}
                </option>
              ))}
            </Select>
            {saving && <span style={{ fontSize: 12, color: "var(--muted)" }}>Salvando…</span>}
          </div>
        </div>

        {error && (
          <div style={{ marginTop: 12 }}>
            <Banner tone="error">{error}</Banner>
          </div>
        )}
      </Card>
    </div>
  );
}
