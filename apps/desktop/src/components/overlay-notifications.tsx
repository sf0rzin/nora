import { useCallback, useEffect, useRef, useState } from "react";
import { DecisionIcon, NextStepIcon, ObservationIcon, TaskIcon } from "@/components/brand/feed-icons";
import { Card, IconButton } from "./ui";

export type NotificationVariant =
  | "info"
  | "decision"
  | "action"
  | "observation"
  | "task"
  | "warn";

export interface OverlayNotification {
  id: string;
  variant: NotificationVariant;
  title: string;
  body?: string;
  duration?: number; // ms — default 6000
}

interface VariantStyle {
  accent: string;
  bg: string;
  icon: React.ReactElement;
  eyebrow: string;
}

const ICONS: Record<NotificationVariant, VariantStyle> = {
  info: {
    accent: "var(--ink)",
    bg: "var(--chip)",
    eyebrow: "Nora",
    icon: (
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10" />
        <line x1="12" y1="16" x2="12" y2="12" />
        <line x1="12" y1="8" x2="12.01" y2="8" />
      </svg>
    ),
  },
  decision: {
    accent: "var(--accent-ink)",
    bg: "var(--accent-soft)",
    eyebrow: "Decisão",
    icon: <DecisionIcon />,
  },
  action: {
    accent: "var(--success-ink)",
    bg: "color-mix(in srgb, var(--success) 16%, transparent)",
    eyebrow: "Próximo passo",
    icon: <NextStepIcon />,
  },
  observation: {
    accent: "var(--muted)",
    bg: "var(--chip)",
    eyebrow: "Observação",
    icon: <ObservationIcon />,
  },
  task: {
    accent: "var(--warn-ink)",
    bg: "color-mix(in srgb, var(--warn) 16%, transparent)",
    eyebrow: "Tarefa",
    icon: <TaskIcon />,
  },
  warn: {
    accent: "var(--danger-ink)",
    bg: "var(--danger-soft-bg)",
    eyebrow: "Aviso",
    icon: (
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
        <line x1="12" y1="9" x2="12" y2="13" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
      </svg>
    ),
  },
};

export function useNotifications(max = 5) {
  const [items, setItems] = useState<OverlayNotification[]>([]);
  const timersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  const dismiss = useCallback((id: string) => {
    setItems((prev) => prev.filter((n) => n.id !== id));
    const t = timersRef.current.get(id);
    if (t) {
      clearTimeout(t);
      timersRef.current.delete(id);
    }
  }, []);

  const push = useCallback(
    (notif: Omit<OverlayNotification, "id">) => {
      const id =
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `n-${Date.now()}-${Math.random()}`;
      const full: OverlayNotification = { duration: 6000, ...notif, id };
      setItems((prev) => {
        const next = [...prev, full];
        // Cap at max — drop the oldest
        if (next.length > max) {
          const dropped = next.slice(0, next.length - max);
          dropped.forEach((d) => {
            const t = timersRef.current.get(d.id);
            if (t) {
              clearTimeout(t);
              timersRef.current.delete(d.id);
            }
          });
          return next.slice(-max);
        }
        return next;
      });
      const timer = setTimeout(() => {
        dismiss(id);
      }, full.duration);
      timersRef.current.set(id, timer);
    },
    [dismiss, max],
  );

  useEffect(() => {
    const timers = timersRef.current;
    return () => {
      timers.forEach((t) => clearTimeout(t));
      timers.clear();
    };
  }, []);

  return { items, push, dismiss };
}

function NotificationCard({
  notification,
  onDismiss,
}: {
  notification: OverlayNotification;
  onDismiss: () => void;
}) {
  const s = ICONS[notification.variant];
  return (
    <Card
      className="flex items-start gap-2.5"
      style={{
        width: 280,
        padding: "9px 11px 9px 10px",
        boxShadow: "var(--shadow-lg)",
        animation: "notifIn 240ms var(--ease)",
        pointerEvents: "auto",
      }}
    >
      <span
        className="grid place-items-center shrink-0"
        style={{
          width: 22,
          height: 22,
          borderRadius: "var(--radius-sm)",
          background: s.bg,
          color: s.accent,
          marginTop: 1,
        }}
      >
        {s.icon}
      </span>
      <div className="flex-1 min-w-0">
        <div
          className="flex items-baseline gap-2"
          style={{ marginBottom: 1 }}
        >
          <span
            style={{
              fontSize: 9.5,
              fontWeight: 500,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              color: s.accent,
            }}
          >
            {s.eyebrow}
          </span>
        </div>
        <div
          style={{
            fontSize: 12,
            color: "var(--ink)",
            lineHeight: 1.4,
            letterSpacing: "-0.005em",
          }}
        >
          <span style={{ fontWeight: 500 }}>{notification.title}</span>
        </div>
        {notification.body && (
          <div
            style={{
              fontSize: 11.5,
              color: "var(--muted)",
              lineHeight: 1.45,
              marginTop: 2,
            }}
          >
            {notification.body}
          </div>
        )}
      </div>
      <IconButton
        size="sm"
        onClick={onDismiss}
        aria-label="Dispensar"
        className="shrink-0"
        style={{ marginTop: 1 }}
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <path d="M18 6L6 18M6 6l12 12" />
        </svg>
      </IconButton>
    </Card>
  );
}

export function NotificationStack({
  items,
  onDismiss,
}: {
  items: OverlayNotification[];
  onDismiss: (id: string) => void;
}) {
  return (
    <div
      className="absolute flex flex-col gap-2"
      style={{
        bottom: 12,
        right: 12,
        zIndex: 30,
        pointerEvents: "none",
        maxWidth: 296,
      }}
    >
      {items.map((n) => (
        <NotificationCard key={n.id} notification={n} onDismiss={() => onDismiss(n.id)} />
      ))}
      <style>{`
        @keyframes notifIn {
          from { opacity: 0; transform: translateX(16px); }
          to   { opacity: 1; transform: translateX(0); }
        }
      `}</style>
    </div>
  );
}
