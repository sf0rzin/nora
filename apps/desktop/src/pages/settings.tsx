import { useState, useEffect } from "react";
import { useAuth } from "@/hooks/use-auth";
import { secrets } from "@/lib/secrets";
import { invoke } from "@tauri-apps/api/core";

export function SettingsPage() {
  const { user } = useAuth();
  const [cleanupDone, setCleanupDone] = useState(false);
  const [stealthMode, setStealthMode] = useState(false);
  const [stealthError, setStealthError] = useState<string | null>(null);
  const [isApplyingStealth, setIsApplyingStealth] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        // Migration: remove old azure-speech-key if exists
        const hasOldKey = await secrets.has("azure-speech-key");
        if (hasOldKey) {
          await secrets.delete("azure-speech-key");
          console.log("[settings] migrated: removed old azure-speech-key");
        }
        const hasOldRegion = await secrets.has("azure-region");
        if (hasOldRegion) {
          await secrets.delete("azure-region");
          console.log("[settings] migrated: removed old azure-region");
        }
        setCleanupDone(true);

        // Load stealth mode state
        console.log("[settings] loading stealth mode state...");
        const saved = await invoke<boolean>("get_stealth_mode");
        console.log("[settings] stealth mode loaded:", saved);
        setStealthMode(saved);
      } catch (e) {
        console.error("[settings] failed to initialize settings:", e);
      }
    })();
  }, []);

  const handleStealthToggle = async (enabled: boolean) => {
    console.log("[settings] handleStealthToggle called, enabled=", enabled);
    setStealthError(null);
    setIsApplyingStealth(true);

    // Optimistic update — checkbox muda imediatamente na UI
    setStealthMode(enabled);

    try {
      await invoke("set_stealth_mode", { enabled });
      console.log("[settings] set_stealth_mode succeeded");
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      console.error("[settings] failed to set stealth mode:", msg);
      setStealthError(msg);
      // Reverte o estado otimista em caso de erro
      setStealthMode(!enabled);
    } finally {
      setIsApplyingStealth(false);
    }
  };

  return (
    <div className="flex-1 overflow-auto p-6">
      <div className="max-w-2xl mx-auto">
        <h1 className="text-xl font-bold mb-6">Configurações</h1>

        <section className="mb-8">
          <h2 className="text-sm font-semibold text-zinc-400 uppercase mb-4">Conta</h2>
          <div className="bg-zinc-800 border border-zinc-700 rounded-lg p-4 space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-zinc-400">Nome</span>
              <span>{user?.displayName}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-zinc-400">E-mail</span>
              <span>{user?.email}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-zinc-400">Tenant</span>
              <span className="font-mono text-xs">{user?.tenantId}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-zinc-400">Roles</span>
              <span>{user?.roles?.join(", ")}</span>
            </div>
          </div>
        </section>

        <section className="mb-8">
          <h2 className="text-sm font-semibold text-zinc-400 uppercase mb-4">
            Privacidade
          </h2>
          <div className="bg-zinc-800 border border-zinc-700 rounded-lg p-4 space-y-4">
            <div className="flex items-start gap-3">
              <input
                id="stealth-mode-checkbox"
                type="checkbox"
                checked={stealthMode}
                disabled={isApplyingStealth}
                onChange={(e) => {
                  console.log("[settings] onChange fired, checked=", e.target.checked);
                  handleStealthToggle(e.target.checked);
                }}
                className="w-4 h-4 mt-0.5 rounded border-zinc-600 bg-zinc-700 text-blue-500 focus:ring-blue-500/20 disabled:opacity-50"
              />
              <label htmlFor="stealth-mode-checkbox" className="flex-1 cursor-pointer">
                <span className="text-sm text-zinc-200 font-medium block">
                  Modo Stealth
                  {isApplyingStealth && (
                    <span className="ml-2 inline-block w-3 h-3 border-2 border-zinc-500 border-t-zinc-300 rounded-full animate-spin align-middle" />
                  )}
                </span>
                <span className="text-xs text-zinc-400">
                  Oculta o NORA Desktop e a overlay de capturas de tela, OBS e compartilhamento de tela.
                  No Windows usa proteção nativa do sistema; no Linux aplica flags de janela para reduzir visibilidade.
                </span>
                {stealthError && (
                  <span className="text-xs text-red-400 mt-1 block">
                    Erro: {stealthError}
                  </span>
                )}
              </label>
            </div>
          </div>
        </section>

        <section className="mb-8">
          <h2 className="text-sm font-semibold text-zinc-400 uppercase mb-4">
            Azure Speech to Text
          </h2>
          <div className="bg-zinc-800 border border-zinc-700 rounded-lg p-4 space-y-4">
            <div className="flex items-center gap-3">
              <div className="w-2 h-2 rounded-full bg-green-500" />
              <span className="text-sm text-zinc-300">
                Transcrição gerenciada pelo NORA
              </span>
            </div>
            <p className="text-sm text-zinc-400">
              Nenhuma configuração necessária. O NORA gerencia automaticamente os tokens 
              de autorização para o Azure Speech Services.
            </p>
            {cleanupDone && (
              <p className="text-xs text-zinc-500">
                Chaves antigas removidas com sucesso.
              </p>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
