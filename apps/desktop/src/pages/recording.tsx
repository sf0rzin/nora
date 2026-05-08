import { useRecording } from "@/hooks/use-recording";
import { useState } from "react";

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export function RecordingPage() {
  const [meetingTitle, setMeetingTitle] = useState("");
  const [captureSystemAudio, setCaptureSystemAudio] = useState(true);
  const [systemAudioDevice, setSystemAudioDevice] = useState<string | null>(null);

  const azureSpeechKey = localStorage.getItem("nora_azure_speech_key") || undefined;
  const azureRegion = localStorage.getItem("nora_azure_region") || undefined;

  const {
    isRecording,
    transcriptLines,
    partialText,
    fullTranscript,
    devices,
    selectedDevice,
    setSelectedDevice,
    duration,
    error,
    deviceName,
    sampleRate,
    speakerMap,
    renameSpeaker,
    getSpeakerName,
    isSaving,
    savedMeetingId,
    startRecording,
    stopRecording,
    saveMeeting,
  } = useRecording({
    azureSpeechKey,
    azureRegion,
    captureSystemAudio,
    systemAudioDevice,
    language: "pt-BR",
  });

  return (
    <div className="flex-1 overflow-auto p-6">
      <div className="max-w-4xl mx-auto">
        <h1 className="text-xl font-bold mb-4">Captura ao Vivo</h1>

        {error && (
          <div className="mb-4 p-3 bg-red-900/30 border border-red-800 rounded text-sm text-red-300">
            {error}
          </div>
        )}

        {isRecording && (
          <div className="mb-4 p-3 bg-green-900/30 border border-green-800 rounded text-sm text-green-300">
            Gravando... Dispositivo: {deviceName} | Sample rate: {sampleRate}Hz
          </div>
        )}

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

        <div className="mb-6 flex items-center gap-6">
          <label className="flex items-center gap-2 text-sm cursor-pointer">
            <input
              type="checkbox"
              checked={captureSystemAudio}
              onChange={(e) => setCaptureSystemAudio(e.target.checked)}
              disabled={isRecording}
              className="w-4 h-4 rounded"
            />
            <span className="text-zinc-300">Capturar áudio do sistema</span>
          </label>

          {captureSystemAudio && (
            <select
              value={systemAudioDevice || ""}
              onChange={(e) => setSystemAudioDevice(e.target.value || null)}
              disabled={isRecording}
              className="px-3 py-2 bg-zinc-800 border border-zinc-700 rounded text-sm focus:outline-none disabled:opacity-50"
            >
              <option value="">Auto-detectar monitor</option>
              {devices.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          )}
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

            {transcriptLines.map((line) => {
              const speakerName = getSpeakerName(line.speakerId, line.speaker, line.track);
              return (
                <div key={line.id} className="flex gap-2 items-start group">
                  {speakerName && (
                    <span className={`text-blue-400 shrink-0 font-medium min-w-[80px] ${line.track === 'mic' ? 'text-green-400' : ''}`}>
                      {speakerName}
                    </span>
                  )}
                  <span className="text-zinc-200">{line.text}</span>
                </div>
              );
            })}

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
            {!isRecording && transcriptLines.length > 0 && !savedMeetingId && (
              <button
                onClick={() => saveMeeting(meetingTitle)}
                disabled={isSaving}
                className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 rounded text-sm font-medium transition-colors"
              >
                {isSaving ? "Salvando..." : "Salvar Reunião"}
              </button>
            )}
            {savedMeetingId && (
              <span className="px-4 py-2 bg-green-900/30 border border-green-800 rounded text-sm text-green-300">
                Reunião salva!
              </span>
            )}
          </div>
        )}

        {/* Speaker Management Panel */}
        {Object.keys(speakerMap).length > 0 && (
          <div className="mt-6 border border-zinc-700 rounded-lg overflow-hidden">
            <div className="px-4 py-2 bg-zinc-800 border-b border-zinc-700">
              <span className="text-sm font-medium">Gerenciar Participantes</span>
            </div>
            <div className="p-4 space-y-2">
              {Object.entries(speakerMap).map(([speakerId, name]) => (
                <div key={speakerId} className="flex items-center gap-2">
                  <span className="text-sm text-zinc-400 w-20">{speakerId}:</span>
                  <input
                    type="text"
                    value={name}
                    onChange={(e) => renameSpeaker(speakerId, e.target.value)}
                    className="flex-1 px-2 py-1 bg-zinc-800 border border-zinc-700 rounded text-sm focus:outline-none focus:border-blue-500"
                  />
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
