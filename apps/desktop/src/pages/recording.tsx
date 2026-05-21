import { useRecording } from "@/hooks/use-recording";
import { useState, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

interface AudioPrerequisites {
  platform: string;
  available: boolean;
  missingDriver: string | null;
  supportsScreenCaptureKit: boolean;
  message: string;
}

export function RecordingPage() {
  const [meetingTitle, setMeetingTitle] = useState("");
  const [captureSystemAudio, setCaptureSystemAudio] = useState(true);
  const [systemAudioDevice, setSystemAudioDevice] = useState<string | null>(null);
  const [showBlackHoleWizard, setShowBlackHoleWizard] = useState(false);

  useEffect(() => {
    let mounted = true;
    // Antes salvávamos o objeto inteiro em `_, setAudioPrereqs` sem nunca ler — dead state.
    // Só precisamos do `platform === "macos" && !available` pra decidir o wizard.
    invoke<AudioPrerequisites>("check_system_audio_prerequisites")
      .then((result) => {
        if (!mounted) return;
        if (result.platform === "macos" && !result.available) {
          setShowBlackHoleWizard(true);
        }
      })
      .catch((e) => console.error("[recording] failed to check audio prerequisites:", e));
    return () => {
      mounted = false;
    };
  }, []);

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
    saveError,
    startRecording,
    stopRecording,
    saveMeeting,
  } = useRecording({
    captureSystemAudio,
    systemAudioDevice,
    language: "pt-BR",
  });

  return (
    <div className="flex-1 overflow-auto p-6">
      <div className="max-w-4xl mx-auto">
        <h1 className="text-xl font-bold mb-4">Captura ao Vivo</h1>

        {showBlackHoleWizard && (
          <div className="mb-4 p-4 bg-yellow-900/30 border border-yellow-800 rounded-lg">
            <div className="flex items-start gap-3">
              <svg className="w-5 h-5 text-yellow-400 mt-0.5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
              <div className="flex-1">
                <h3 className="text-sm font-medium text-yellow-200 mb-1">
                  Driver de áudio necessário
                </h3>
                <p className="text-sm text-yellow-300/80 mb-3">
                  Para capturar áudio do sistema no macOS, você precisa instalar o BlackHole (driver virtual gratuito).
                </p>
                <div className="flex gap-2">
                  <a
                    href="https://existential.audio/blackhole/"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="px-3 py-1.5 bg-yellow-700 hover:bg-yellow-600 rounded text-xs font-medium text-white transition-colors"
                  >
                    Baixar BlackHole
                  </a>
                  <button
                    onClick={() => setShowBlackHoleWizard(false)}
                    className="px-3 py-1.5 border border-yellow-700 hover:bg-yellow-900/50 rounded text-xs text-yellow-300 transition-colors"
                  >
                    Ignorar
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

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

        {saveError && (
          <div className="mt-4 p-3 bg-red-900/30 border border-red-800 rounded text-sm text-red-300 flex items-center justify-between">
            <span>Falha ao salvar: {saveError}</span>
            <button
              onClick={() => saveMeeting(meetingTitle)}
              disabled={isSaving}
              className="px-3 py-1 bg-red-700 hover:bg-red-600 disabled:opacity-50 rounded text-xs font-medium transition-colors"
            >
              Tentar novamente
            </button>
          </div>
        )}

        {fullTranscript && (
          <div className="mt-4 flex justify-end gap-2">
            <button
              onClick={() => navigator.clipboard.writeText(fullTranscript)}
              className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 rounded text-sm transition-colors"
            >
              Copiar Transcrição
            </button>
            {!isRecording && transcriptLines.length > 0 && !savedMeetingId && !saveError && (
              <button
                onClick={() => saveMeeting(meetingTitle)}
                disabled={isSaving}
                className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 rounded text-sm font-medium transition-colors flex items-center gap-2"
              >
                {isSaving && (
                  <span className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                )}
                {isSaving ? "Salvando..." : "Salvar Reunião"}
              </button>
            )}
            {savedMeetingId && (
              <span className="px-4 py-2 bg-green-900/30 border border-green-800 rounded text-sm text-green-300 flex items-center gap-2">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                </svg>
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
