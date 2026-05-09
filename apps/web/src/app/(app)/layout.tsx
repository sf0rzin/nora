import Link from "next/link";
import type { Route } from "next";
import LogoutButton from "@/components/LogoutButton";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen flex">
      <aside className="hidden md:flex w-60 flex-col border-r border-slate-200 bg-slate-50 p-4">
        <div className="mb-6">
          <Link href={"/dashboard" as Route} className="text-lg font-semibold tracking-tight">
            NORA
          </Link>
          <p className="text-xs text-slate-500">MVP</p>
        </div>
        <nav className="flex flex-col gap-1 text-sm">
          <Link href={"/dashboard" as Route} className="rounded-md px-3 py-2 hover:bg-white">
            Reuniões
          </Link>
          <Link href={"/meetings/upload" as Route} className="rounded-md px-3 py-2 hover:bg-white">
            Nova reunião
          </Link>
          <Link href={"/tasks" as Route} className="rounded-md px-3 py-2 hover:bg-white">
            Tarefas
          </Link>
          <Link href={"/settings/context" as Route} className="rounded-md px-3 py-2 hover:bg-white">
            Contexto do tenant
          </Link>
          <Link href={"/settings/iam" as Route} className="rounded-md px-3 py-2 hover:bg-white">
            IAM
          </Link>
        </nav>
        <div className="mt-auto space-y-2 text-xs text-slate-500">
          <LogoutButton />
          <div>v0.2.0</div>
        </div>
      </aside>

      <main className="flex-1 p-6 md:p-10 max-w-6xl mx-auto w-full">{children}</main>
    </div>
  );
}
