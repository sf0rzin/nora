import { useState, useEffect } from "react";
import { useAuth } from "@/hooks/use-auth";

export function SettingsPage() {
  const { user } = useAuth();
  const [speechKey, setSpeechKey] = useState("");
  const [endpoint, setEndpoint] = useState("https://eastus.api.cognitive.microsoft.com");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    const storedKey = localStorage.getItem("nora_azure_speech_key") || "";
    const storedEndpoint = localStorage.getItem("nora_azure_endpoint") || "https://eastus.api.cognitive.microsoft.com";
    setSpeechKey(storedKey);
    setEndpoint(storedEndpoint);
  }, []);

  const handleSave = () => {
    localStorage.setItem("nora_azure_speech_key", speechKey);
    localStorage.setItem("nora_azure_endpoint", endpoint);
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
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
            Azure Speech to Text
          </h2>
          <div className="bg-zinc-800 border border-zinc-700 rounded-lg p-4 space-y-4">
            <div>
              <label className="block text-sm text-zinc-400 mb-1">
                Endpoint
              </label>
              <input
                type="text"
                value={endpoint}
                onChange={(e) => setEndpoint(e.target.value)}
                placeholder="https://eastus.api.cognitive.microsoft.com"
                className="w-full px-3 py-2 bg-zinc-900 border border-zinc-600 rounded text-sm focus:outline-none focus:border-blue-500"
              />
            </div>
            <div>
              <label className="block text-sm text-zinc-400 mb-1">
                Subscription Key
              </label>
              <input
                type="password"
                value={speechKey}
                onChange={(e) => setSpeechKey(e.target.value)}
                placeholder="Cole sua Azure Speech Key aqui"
                className="w-full px-3 py-2 bg-zinc-900 border border-zinc-600 rounded text-sm focus:outline-none focus:border-blue-500"
              />
            </div>
            <button
              onClick={handleSave}
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded text-sm font-medium transition-colors"
            >
              {saved ? "Salvo!" : "Salvar"}
            </button>
            <p className="text-xs text-zinc-500">
              Armazenado localmente no dispositivo. Nunca enviado ao backend NORA.
            </p>
          </div>
        </section>
      </div>
    </div>
  );
}
