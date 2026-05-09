"use client";

import { useRouter } from "next/navigation";
import type { Route } from "next";
import { useState } from "react";
import { uploadMeeting, ApiRequestError } from "@/lib/api/client";

type Format = "TXT" | "VTT" | "SRT";

const FORMAT_BY_EXT: Record<string, Format> = {
  txt: "TXT",
  vtt: "VTT",
  srt: "SRT",
};

export default function UploadMeetingPage() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState("pt-BR");
  const [format, setFormat] = useState<Format>("TXT");
  const [tags, setTags] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0] ?? null;
    setFile(f);
    if (f) {
      const ext = f.name.split(".").pop()?.toLowerCase();
      if (ext && FORMAT_BY_EXT[ext]) setFormat(FORMAT_BY_EXT[ext]);
      if (!title) setTitle(f.name.replace(/\.[^.]+$/, ""));
    }
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!file) {
      setError("Selecione um arquivo de transcricao.");
      return;
    }
    setLoading(true);
    try {
      const r = await uploadMeeting({
        title: title.trim(),
        language,
        transcriptFormat: format,
        tags: tags
          .split(",")
          .map((t) => t.trim())
          .filter(Boolean),
        file,
      });
      router.push(`/meetings/${r.id}` as Route);
      router.refresh();
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : "Falha no upload.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="max-w-2xl space-y-6">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Nova reunião</h1>
        <p className="text-sm text-slate-500">
          Suba uma transcrição (.txt, .vtt ou .srt) — a análise começa em segundo plano.
        </p>
      </header>

      <form
        onSubmit={onSubmit}
        className="space-y-4 rounded-lg border border-slate-200 bg-white p-6"
      >
        <div className="space-y-1.5">
          <label className="text-sm font-medium text-slate-700">Título</label>
          <input
            required
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
            placeholder="Discovery Acme – outubro/2025"
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-slate-700">Idioma</label>
            <select
              value={language}
              onChange={(e) => setLanguage(e.target.value)}
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm"
            >
              <option value="pt-BR">Português (BR)</option>
              <option value="en-US">Inglês (US)</option>
              <option value="es-ES">Espanhol</option>
            </select>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium text-slate-700">Formato</label>
            <select
              value={format}
              onChange={(e) => setFormat(e.target.value as Format)}
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm"
            >
              <option value="TXT">TXT</option>
              <option value="VTT">VTT</option>
              <option value="SRT">SRT</option>
            </select>
          </div>
        </div>

        <div className="space-y-1.5">
          <label className="text-sm font-medium text-slate-700">Tags (separadas por vírgula)</label>
          <input
            value={tags}
            onChange={(e) => setTags(e.target.value)}
            placeholder="discovery, renovacao"
            className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
          />
        </div>

        <div className="space-y-1.5">
          <label className="text-sm font-medium text-slate-700">Arquivo de transcrição</label>
          <input
            type="file"
            accept=".txt,.vtt,.srt,text/plain"
            onChange={onFileChange}
            className="block w-full text-sm text-slate-700 file:mr-3 file:rounded-md file:border-0 file:bg-slate-900 file:px-3 file:py-2 file:text-sm file:font-medium file:text-white hover:file:bg-slate-800"
          />
          {file && (
            <p className="text-xs text-slate-500">
              {file.name} · {(file.size / 1024).toFixed(1)} KB
            </p>
          )}
        </div>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <div className="flex gap-2">
          <button
            type="submit"
            disabled={loading}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-50"
          >
            {loading ? "Enviando…" : "Enviar e analisar"}
          </button>
          <button
            type="button"
            onClick={() => router.back()}
            className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50"
          >
            Cancelar
          </button>
        </div>
      </form>
    </div>
  );
}
