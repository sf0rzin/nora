import Link from "next/link";

export default function HomePage() {
  return (
    <main className="min-h-screen flex items-center justify-center px-4">
      <div className="w-full max-w-md space-y-6 rounded-xl border border-border bg-background p-8 shadow-sm">
        <header className="space-y-1 text-center">
          <h1 className="text-3xl font-semibold tracking-tight">NORA</h1>
          <p className="text-sm text-muted-foreground">
            Conversational intelligence para reuniões comerciais
          </p>
        </header>

        <form className="space-y-4" action="/dashboard">
          <div className="space-y-1.5">
            <label htmlFor="email" className="text-sm font-medium">
              E-mail corporativo
            </label>
            <input
              id="email"
              name="email"
              type="email"
              required
              placeholder="voce@empresa.com"
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            />
          </div>
          <div className="space-y-1.5">
            <label htmlFor="password" className="text-sm font-medium">
              Senha
            </label>
            <input
              id="password"
              name="password"
              type="password"
              required
              placeholder="••••••••"
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            />
          </div>
          <button
            type="submit"
            className="w-full rounded-md bg-primary px-3 py-2 text-sm font-medium text-primary-foreground transition hover:opacity-90"
          >
            Entrar
          </button>
        </form>

        <p className="text-center text-xs text-muted-foreground">
          MVP em construcao. {""}
          <Link href="/dashboard" className="underline">
            Pular para dashboard (mock)
          </Link>
        </p>
      </div>
    </main>
  );
}
