import { useState, useEffect, type FormEvent } from "react";
import { useAuth } from "@/hooks/use-auth";
import { login } from "@/lib/auth";

export function LoginPage() {
  const { authenticated, login: setAuthUser } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (authenticated) {
      window.location.hash = "#/meetings";
    }
  }, [authenticated]);

  if (authenticated) {
    return null;
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      const user = await login({ email, password });
      setAuthUser(user);
    } catch (err: unknown) {
      console.error("Login error:", err);
      const msg =
        err instanceof Error
          ? err.message
          : (err as Record<string, unknown>)?.message?.toString() ||
            JSON.stringify(err);
      setError(msg || "Erro ao fazer login.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="h-full grid grid-cols-1 lg:grid-cols-2 bg-[oklch(0.16_0.012_250)]">
      {/* LEFT: animated visual */}
      <aside className="hidden lg:flex relative bg-[oklch(0.13_0.012_250)] border-r border-[oklch(0.30_0.012_250)] overflow-hidden flex-col">
        {/* Dot grid */}
        <div 
          className="absolute inset-0 opacity-35"
          style={{
            backgroundImage: 'radial-gradient(oklch(0.30 0.012 250) 1px, transparent 1px)',
            backgroundSize: '28px 28px',
            maskImage: 'radial-gradient(ellipse 70% 60% at 50% 50%, black, transparent 80%)'
          }}
        />
        
        {/* Logo */}
        <div className="absolute top-8 left-10 z-10 font-semibold text-[22px] tracking-[-0.04em] text-[oklch(0.97_0.005_250)]">
          NORA
        </div>

        {/* Orb stage */}
        <div className="absolute inset-0 flex items-center justify-center">
          {/* Glow */}
          <div 
            className="absolute inset-0 opacity-70"
            style={{
              background: 'radial-gradient(closest-side, oklch(0.70 0.17 248 / 0.18), transparent 70%)'
            }}
          />
          
          {/* Orbs */}
          <div 
            className="absolute rounded-full border border-[oklch(0.70_0.17_248_/_0.4)]"
            style={{
              width: '380px', height: '380px',
              background: 'radial-gradient(circle at 30% 30%, oklch(0.70 0.17 248 / 0.12), transparent 65%)',
              animation: 'orbFloat1 14s ease-in-out infinite'
            }}
          />
          <div 
            className="absolute rounded-full border border-[oklch(0.70_0.17_248_/_0.25)]"
            style={{
              width: '260px', height: '260px',
              background: 'radial-gradient(circle at 30% 30%, oklch(0.70 0.17 248 / 0.08), transparent 65%)',
              animation: 'orbFloat2 18s ease-in-out infinite'
            }}
          />
          <div 
            className="absolute rounded-full border border-[oklch(0.70_0.17_248_/_0.15)]"
            style={{
              width: '520px', height: '520px',
              background: 'radial-gradient(circle at 30% 30%, oklch(0.70 0.17 248 / 0.05), transparent 65%)',
              animation: 'orbFloat3 22s ease-in-out infinite'
            }}
          />

          {/* Soundwave */}
          <div className="relative z-10 flex items-center justify-center gap-[6px] h-20">
            {[...Array(13)].map((_, i) => (
              <span
                key={i}
                className="block w-1 rounded-sm bg-[oklch(0.78_0.16_248)]"
                style={{
                  boxShadow: '0 0 12px oklch(0.70 0.17 248)',
                  animation: `waveBeat 1.4s ease-in-out infinite`,
                  animationDelay: `${Math.abs(i - 6) * 0.08}s`
                }}
              />
            ))}
          </div>
        </div>

        {/* Footer */}
        <div className="absolute bottom-8 left-10 right-10 flex justify-between text-xs text-[oklch(0.55_0.012_250)] z-10">
          <span>Inteligência conversacional</span>
          <span>v.1</span>
        </div>

        <style>{`
          @keyframes orbFloat1 {
            0%, 100% { transform: translate(0, 0); }
            50% { transform: translate(40px, -30px); }
          }
          @keyframes orbFloat2 {
            0%, 100% { transform: translate(0, 0); }
            50% { transform: translate(-50px, 40px); }
          }
          @keyframes orbFloat3 {
            0%, 100% { transform: translate(0, 0) scale(1); }
            50% { transform: translate(20px, 20px) scale(1.04); }
          }
          @keyframes waveBeat {
            0%, 100% { height: 14px; opacity: 0.5; }
            50% { height: 70px; opacity: 1; }
          }
        `}</style>
      </aside>

      {/* RIGHT: form */}
      <main className="flex items-center justify-center p-8 overflow-y-auto">
        <form onSubmit={handleSubmit} className="w-full max-w-[360px] flex flex-col gap-8">
          {/* Header */}
          <div>
            <h1 className="text-[28px] font-medium tracking-[-0.025em] text-[oklch(0.97_0.005_250)] mb-1.5">
              Bem-vindo de volta
            </h1>
            <p className="text-sm text-[oklch(0.72_0.012_250)] leading-relaxed">
              Entre na sua conta para continuar.
            </p>
          </div>

          {/* SSO — em breve (sem handler ainda; desabilitado pra evitar click morto) */}
          <div className="flex flex-col gap-2">
            <button
              type="button"
              disabled
              aria-disabled
              title="SSO Microsoft em breve"
              className="w-full flex items-center justify-center gap-2.5 px-4 py-2.5 bg-[oklch(0.20_0.014_250)] border border-[oklch(0.30_0.012_250)] rounded-[10px] text-[oklch(0.97_0.005_250)] text-[13.5px] font-medium transition-all duration-180 opacity-50 cursor-not-allowed"
            >
              <svg className="w-4 h-4 shrink-0" viewBox="0 0 23 23" xmlns="http://www.w3.org/2000/svg">
                <rect x="1" y="1" width="10" height="10" fill="#F25022"/>
                <rect x="12" y="1" width="10" height="10" fill="#7FBA00"/>
                <rect x="1" y="12" width="10" height="10" fill="#00A4EF"/>
                <rect x="12" y="12" width="10" height="10" fill="#FFB900"/>
              </svg>
              Continuar com Microsoft
              <span className="ml-1 text-[10px] text-[oklch(0.55_0.012_250)]">(em breve)</span>
            </button>
          </div>

          {/* Divider */}
          <div className="flex items-center gap-3 text-[11px] text-[oklch(0.55_0.012_250)]">
            <div className="flex-1 h-px bg-[oklch(0.30_0.012_250)]" />
            <span>ou</span>
            <div className="flex-1 h-px bg-[oklch(0.30_0.012_250)]" />
          </div>

          {/* Fields */}
          <div className="flex flex-col gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-[12.5px] font-medium text-[oklch(0.72_0.012_250)]" htmlFor="email">
                E-mail
              </label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="você@empresa.com"
                required
                className="w-full px-3.5 py-2.5 bg-[oklch(0.20_0.014_250)] border border-[oklch(0.30_0.012_250)] rounded-[10px] text-[oklch(0.97_0.005_250)] text-sm transition-all duration-180 outline-none placeholder:text-[oklch(0.55_0.012_250)] focus:border-[oklch(0.70_0.17_248)] focus:bg-[oklch(0.24_0.014_250)] focus:shadow-[0_0_0_4px_oklch(0.70_0.17_248_/_0.18)]"
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <div className="flex justify-between items-center">
                <label className="text-[12.5px] font-medium text-[oklch(0.72_0.012_250)]" htmlFor="password">
                  Senha
                </label>
                <button
                  type="button"
                  disabled
                  aria-disabled
                  title="Recuperação de senha em breve"
                  className="text-xs text-[oklch(0.78_0.16_248)] opacity-50 cursor-not-allowed"
                >
                  Esqueci minha senha
                </button>
              </div>
              <div className="relative flex items-center">
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••"
                  required
                  minLength={8}
                  className="w-full px-3.5 py-2.5 pr-10 bg-[oklch(0.20_0.014_250)] border border-[oklch(0.30_0.012_250)] rounded-[10px] text-[oklch(0.97_0.005_250)] text-sm transition-all duration-180 outline-none placeholder:text-[oklch(0.55_0.012_250)] focus:border-[oklch(0.70_0.17_248)] focus:bg-[oklch(0.24_0.014_250)] focus:shadow-[0_0_0_4px_oklch(0.70_0.17_248_/_0.18)]"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-2 p-1.5 bg-transparent border-0 text-[oklch(0.55_0.012_250)] hover:text-[oklch(0.97_0.005_250)] rounded transition-colors"
                  aria-label={showPassword ? "Ocultar senha" : "Mostrar senha"}
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7">
                    <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/>
                    <circle cx="12" cy="12" r="3"/>
                  </svg>
                </button>
              </div>
            </div>
          </div>

          {/* Error */}
          {error && (
            <p className="text-sm text-red-400 bg-red-950/30 px-3 py-2 rounded-lg border border-red-900/40">
              {error}
            </p>
          )}

          {/* Submit */}
          <button
            type="submit"
            disabled={loading}
            className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-[oklch(0.97_0.005_250)] text-[oklch(0.16_0.012_250)] border-0 rounded-[10px] text-sm font-medium transition-all duration-180 hover:-translate-y-0.5 disabled:opacity-50 disabled:hover:translate-y-0"
          >
            {loading ? "Entrando..." : "Entrar"}
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M3 7h8M7 3l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>

          {/* Footer */}
          <div className="flex justify-between items-center text-[12.5px] text-[oklch(0.55_0.012_250)]">
            <span>
              Não tem conta?{" "}
              <button
                type="button"
                disabled
                aria-disabled
                title="Em breve"
                className="text-[oklch(0.72_0.012_250)] opacity-50 cursor-not-allowed transition-colors"
              >
                Falar com vendas
              </button>
            </span>
            <span>SSO · SAML</span>
          </div>
        </form>
      </main>
    </div>
  );
}
