import Link from "next/link";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen flex">
      <aside className="hidden md:flex w-60 flex-col border-r border-border bg-muted/30 p-4">
        <div className="mb-6">
          <Link href="/dashboard" className="text-lg font-semibold tracking-tight">
            NORA
          </Link>
          <p className="text-xs text-muted-foreground">MVP</p>
        </div>
        <nav className="flex flex-col gap-1 text-sm">
          <Link
            href="/dashboard"
            className="rounded-md px-3 py-2 hover:bg-background"
          >
            Reuniões
          </Link>
          <span className="rounded-md px-3 py-2 text-muted-foreground cursor-not-allowed">
            Tarefas (em breve)
          </span>
          <span className="rounded-md px-3 py-2 text-muted-foreground cursor-not-allowed">
            Contexto (em breve)
          </span>
        </nav>
        <div className="mt-auto text-xs text-muted-foreground">
          v0.1.0 · skeleton
        </div>
      </aside>

      <main className="flex-1 p-6 md:p-10 max-w-6xl mx-auto w-full">
        {children}
      </main>
    </div>
  );
}
