'use client';

import { useEffect } from 'react';

interface ErrorBoundaryProps {
  error: Error & { digest?: string };
  reset: () => void;
}

/**
 * Root error boundary of the App Router. Catches errors escaping Server
 * Components and Client Components. Without this file, Next served the default
 * "Internal Server Error" screen with no branding in prod.
 */
export default function RootError({ error, reset }: ErrorBoundaryProps) {
  useEffect(() => {
    if (process.env.NODE_ENV !== 'production') {
      // eslint-disable-next-line no-console
      console.error('[error.tsx]', error);
    }
  }, [error]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-50 p-6">
      <div className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-8 text-center shadow-sm">
        <h1 className="mb-2 text-xl font-semibold text-slate-900">Algo deu errado</h1>
        <p className="mb-6 text-sm text-slate-600">
          Tivemos um problema ao carregar essa página. Tente novamente em instantes.
        </p>
        {error.digest && (
          <p className="mb-6 font-mono text-xs text-slate-400">
            ID do erro: {error.digest}
          </p>
        )}
        <button
          type="button"
          onClick={reset}
          className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800"
        >
          Tentar novamente
        </button>
      </div>
    </div>
  );
}
