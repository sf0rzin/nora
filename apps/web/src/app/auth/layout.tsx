export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className="min-h-screen flex items-center justify-center bg-slate-50 px-4 py-10">
      <div className="w-full max-w-md space-y-6 rounded-xl border border-slate-200 bg-white p-8 shadow-sm">
        <header className="space-y-1 text-center">
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">NORA</h1>
          <p className="text-xs uppercase tracking-widest text-slate-500">
            Conversational intelligence
          </p>
        </header>
        {children}
      </div>
    </main>
  );
}
