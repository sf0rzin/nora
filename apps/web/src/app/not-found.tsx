import Link from 'next/link';

/** 404. Tokens and `.btn`, like the rest of the product — see the note in `error.tsx`. */
export default function NotFound() {
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
        <p
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 11.5,
            letterSpacing: '0.08em',
            color: 'var(--muted)',
            margin: '0 0 8px',
          }}
        >
          404
        </p>
        <h1 className="h1" style={{ fontSize: 22, marginBottom: 8 }}>
          Página não encontrada
        </h1>
        <p className="lede" style={{ marginBottom: 24 }}>
          A rota que você tentou acessar não existe ou foi removida.
        </p>
        <Link href="/dashboard" className="btn btn-primary">
          Voltar para o dashboard
        </Link>
      </div>
    </div>
  );
}
