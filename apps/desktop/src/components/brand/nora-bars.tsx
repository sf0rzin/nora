import { useEffect, useState } from "react";

// Same heights as NoraLogo — single source of truth pro soundwave da marca.
const HEIGHTS = [0.42, 0.78, 1.0, 0.66, 0.52];
const BAR_W = 3;
const BAR_GAP = 2.5;

interface Props {
  size?: number;
  /** Pulse animation on the bars (e.g., recording state). */
  active?: boolean;
  /** Play the rise entry animation on mount. */
  animate?: boolean;
  /** Change to replay the animation without remount. */
  animKey?: number;
  /** Override bar color. Defaults to var(--danger) when active, var(--ink) otherwise. */
  color?: string;
  /** Override active color (only used when active=true). Defaults var(--danger). */
  activeColor?: string;
}

/**
 * Stand-alone NORA soundwave — 5 bars com altura assimétrica fixa.
 * Usado no header da overlay, no dock e em qualquer lugar que precise da
 * marca sem o texto "NORA". O componente NoraLogo continua sendo o pacote
 * completo (bars + texto), mas internamente compartilha as mesmas heights.
 */
export function NoraBars({
  size = 16,
  active = false,
  animate = false,
  animKey = 0,
  color,
  activeColor,
}: Props) {
  const [phase, setPhase] = useState<"init" | "animating" | "done">(
    animate ? "init" : "done",
  );

  useEffect(() => {
    if (!animate) {
      setPhase("done");
      return;
    }
    setPhase("init");
    const t1 = setTimeout(() => setPhase("animating"), 60);
    const t2 = setTimeout(() => setPhase("done"), 1100);
    return () => {
      clearTimeout(t1);
      clearTimeout(t2);
    };
  }, [animate, animKey]);

  const totalW = HEIGHTS.length * BAR_W + (HEIGHTS.length - 1) * BAR_GAP;
  const fill = color || (active ? activeColor || "var(--danger)" : "var(--ink)");

  return (
    <span
      className="inline-flex items-center justify-center select-none"
      style={{
        width: totalW,
        height: size,
        gap: BAR_GAP,
      }}
      aria-hidden="true"
    >
      {HEIGHTS.map((h, i) => {
        const finalH = h * size;
        const base: React.CSSProperties = {
          display: "block",
          width: BAR_W,
          background: fill,
          borderRadius: BAR_W,
          transformOrigin: "bottom center",
        };

        if (animate && phase === "init") {
          return (
            <span
              key={i}
              style={{ ...base, height: finalH, transform: "scaleY(0)", opacity: 0 }}
            />
          );
        }
        if (animate && phase === "animating") {
          return (
            <span
              key={i}
              style={{
                ...base,
                height: finalH,
                transform: "scaleY(1)",
                opacity: 1,
                transition: `transform 480ms cubic-bezier(.22,.8,.36,1) ${i * 70}ms, opacity 380ms ease ${i * 70}ms`,
              }}
            />
          );
        }
        // done
        return (
          <span
            key={i}
            style={{
              ...base,
              height: finalH,
              opacity: 1,
              animation: active
                ? `dotPulse 1.4s ease-in-out ${i * 0.12}s infinite`
                : undefined,
            }}
          />
        );
      })}
    </span>
  );
}
