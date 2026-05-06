export function RecordingPage() {
  return (
    <div className="flex-1 flex items-center justify-center">
      <div className="text-center">
        <div className="w-20 h-20 mx-auto mb-4 rounded-full bg-zinc-800 border border-zinc-700 flex items-center justify-center">
          <div className="w-6 h-6 rounded-full bg-red-500 animate-pulse" />
        </div>
        <h2 className="text-lg font-bold mb-1">Captura ao Vivo</h2>
        <p className="text-sm text-zinc-400 mb-6">
          Gravação de áudio em tempo real (em breve)
        </p>
        <button
          disabled
          className="px-6 py-2 bg-zinc-700 text-zinc-400 rounded text-sm cursor-not-allowed"
        >
          Iniciar Gravação
        </button>
      </div>
    </div>
  );
}
