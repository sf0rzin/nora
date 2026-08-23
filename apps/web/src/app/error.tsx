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
 *
 * Design-system note: this screen, `not-found.tsx` and `loading.tsx` used raw Tailwind `slate`
 * utilities while the rest of the product runs on the v3 tokens in `src/styles/tokens.css`. They
 * are the three screens a user only ever sees when something has gone wrong, so looking like a
 * different application is exactly the wrong moment for it. Same tokens and the same `.btn` /
 * `.h1` primitives as every other surface now, which also means they follow the dark theme.
 */
export default function RootError({ error, reset }: ErrorBoundaryProps) {
  useEffect(() => {
    if (process.env.NODE_ENV !== 'production') {
      // eslint-disable-next-line no-console
      console.error('[error.tsx]', error);
    }
  }, [error]);

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        background: 'var(--canvas)',
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: 440,
          padding: 32,
          textAlign: 'center',
          background: 'var(--canvas)',
          border: '1px solid var(--border)',
          borderRadius: 16,
        }}
      >
        <h1 className="h1" style={{ fontSize: 22, marginBottom: 8 }}>
          Algo deu errado
        </h1>
        <p className="lede" style={{ marginBottom: 24 }}>
          Tivemos um problema ao carregar essa página. Tente novamente em instantes.
        </p>
        {error.digest && (
          <p
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 11.5,
              color: 'var(--muted)',
              marginBottom: 24,
            }}
          >
            ID do erro: {error.digest}
          </p>
        )}
        <button type="button" onClick={reset} className="btn btn-primary">
          Tentar novamente
        </button>
      </div>
    </div>
  );
}
