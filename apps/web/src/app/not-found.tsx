import Link from 'next/link';

export default function NotFound() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-50 p-6">
      <div className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-8 text-center shadow-sm">
        <p className="mb-2 font-mono text-xs uppercase tracking-wider text-slate-400">404</p>
        <h1 className="mb-2 text-xl font-semibold text-slate-900">Página não encontrada</h1>
        <p className="mb-6 text-sm text-slate-600">
          A rota que você tentou acessar não existe ou foi removida.
        </p>
        <Link
          href="/dashboard"
          className="inline-block rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800"
        >
          Voltar para o dashboard
        </Link>
      </div>
    </div>
  );
}
