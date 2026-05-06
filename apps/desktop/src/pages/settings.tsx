import { useState, useEffect } from "react";
import { useAuth } from "@/hooks/use-auth";

export function SettingsPage() {
  const { user } = useAuth();
  const [speechKey, setSpeechKey] = useState("");
  const [region, setRegion] = useState("eastus");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    const storedKey = localStorage.getItem("nora_azure_speech_key") || "";
    const storedRegion = localStorage.getItem("nora_azure_region") || "eastus";
    setSpeechKey(storedKey);
    setRegion(storedRegion);
  }, []);

  const handleSave = () => {
    localStorage.setItem("nora_azure_speech_key", speechKey);
    localStorage.setItem("nora_azure_region", region);
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
            <div>
              <label className="block text-sm text-zinc-400 mb-1">Região</label>
              <select
                value={region}
                onChange={(e) => setRegion(e.target.value)}
                className="w-full px-3 py-2 bg-zinc-900 border border-zinc-600 rounded text-sm focus:outline-none focus:border-blue-500"
              >
                <option value="eastus">East US</option>
                <option value="eastus2">East US 2</option>
                <option value="brazilsouth">Brazil South</option>
                <option value="westus2">West US 2</option>
                <option value="westeurope">West Europe</option>
              </select>
            </div>
            <button
              onClick={handleSave}
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded text-sm font-medium transition-colors"
            >
              {saved ? "Salvo!" : "Salvar"}
            </button>
            <p className="text-xs text-zinc-500">
              A chave é armazenada localmente no seu dispositivo. Nunca é enviada para o backend NORA.
            </p>
          </div>
        </section>
      </div>
    </div>
  );
}
