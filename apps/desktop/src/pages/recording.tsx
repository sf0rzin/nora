import { useRecording } from "@/hooks/use-recording";
import { useState } from "react";

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export function RecordingPage() {
  const {
    isRecording,
    transcriptLines,
    partialText,
    fullTranscript,
    devices,
    selectedDevice,
    setSelectedDevice,
    duration,
    startRecording,
    stopRecording,
  } = useRecording({
    azureSpeechKey: localStorage.getItem("nora_azure_speech_key") || undefined,
    azureRegion: localStorage.getItem("nora_azure_region") || undefined,
    language: "pt-BR",
  });

  const [meetingTitle, setMeetingTitle] = useState("");

  return (
    <div className="flex-1 overflow-auto p-6">
      <div className="max-w-4xl mx-auto">
        <h1 className="text-xl font-bold mb-4">Captura ao Vivo</h1>

        <div className="mb-4 flex items-center gap-4">
          <input
            type="text"
            placeholder="Título da reunião (opcional)"
            value={meetingTitle}
            onChange={(e) => setMeetingTitle(e.target.value)}
            className="flex-1 px-3 py-2 bg-zinc-800 border border-zinc-700 rounded text-sm focus:outline-none focus:border-blue-500"
          />

          <select
            value={selectedDevice || ""}
            onChange={(e) => setSelectedDevice(e.target.value || null)}
            disabled={isRecording}
            className="px-3 py-2 bg-zinc-800 border border-zinc-700 rounded text-sm focus:outline-none disabled:opacity-50"
          >
            <option value="">Dispositivo padrão</option>
            {devices.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </div>

        <div className="flex items-center gap-4 mb-6">
          {!isRecording ? (
            <button
              onClick={startRecording}
              className="flex items-center gap-2 px-6 py-2 bg-red-600 hover:bg-red-700 rounded text-sm font-medium transition-colors"
            >
              <span className="w-3 h-3 rounded-full bg-white" />
              Iniciar Gravação
            </button>
          ) : (
            <button
              onClick={stopRecording}
              className="flex items-center gap-2 px-6 py-2 bg-zinc-700 hover:bg-zinc-600 rounded text-sm font-medium transition-colors"
            >
              <span className="w-3 h-3 rounded-full bg-zinc-400" />
              Parar Gravação
            </button>
          )}

          {isRecording && (
            <div className="flex items-center gap-2 text-sm">
              <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
              <span className="font-mono text-lg">{formatDuration(duration)}</span>
              <span className="text-zinc-400">REC</span>
            </div>
          )}
        </div>

        <div className="border border-zinc-700 rounded-lg overflow-hidden">
          <div className="px-4 py-2 bg-zinc-800 border-b border-zinc-700 flex items-center justify-between">
            <span className="text-sm font-medium">Transcrição</span>
            {transcriptLines.length > 0 && (
              <span className="text-xs text-zinc-400">
                {transcriptLines.length} linhas
              </span>
            )}
          </div>

          <div className="p-4 min-h-[300px] max-h-[500px] overflow-y-auto space-y-2 font-mono text-sm">
            {transcriptLines.length === 0 && !partialText && !isRecording && (
              <p className="text-zinc-500 text-center py-12">
                Clique em "Iniciar Gravação" para começar a transcrição em tempo real.
              </p>
            )}

            {transcriptLines.length === 0 && !partialText && isRecording && (
              <p className="text-zinc-400 text-center py-12">
                Aguardando fala...
              </p>
            )}

            {transcriptLines.map((line) => (
              <div key={line.id} className="flex gap-2">
                {line.speaker && (
                  <span className="text-blue-400 shrink-0">{line.speaker}</span>
                )}
                <span className="text-zinc-200">{line.text}</span>
              </div>
            ))}

            {partialText && (
              <div className="flex gap-2 opacity-60">
                <span className="text-zinc-200">{partialText}...</span>
              </div>
            )}
          </div>
        </div>

        {fullTranscript && (
          <div className="mt-4 flex justify-end gap-2">
            <button
              onClick={() => navigator.clipboard.writeText(fullTranscript)}
              className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 rounded text-sm transition-colors"
            >
              Copiar Transcrição
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
