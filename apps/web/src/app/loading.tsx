/**
 * Root loading state. Tokens and the product's own `.wave` mark instead of a generic Tailwind
 * spinner — see the note in `error.tsx`. `role="status"` so the wait is announced rather than
 * being an unlabelled animation.
 */
export default function RootLoading() {
  return (
    <div
      role="status"
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        background: 'var(--canvas)',
        color: 'var(--muted)',
      }}
    >
      <span className="wave wave--breathe" aria-hidden>
        {[14, 22, 30, 22, 14].map((height, i) => (
          <span key={i} style={{ height }} />
        ))}
      </span>
      <p style={{ fontSize: 13, margin: 0 }}>Carregando…</p>
    </div>
  );
}
