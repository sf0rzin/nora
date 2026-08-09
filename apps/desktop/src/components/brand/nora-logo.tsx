import { useEffect, useState } from "react";

// Mirrors exactly the canonical web logo (apps/web/src/components/brand/nora-logo.tsx):
// 5 bars [50,75,100,75,50]% of the height, width/gap relative to size, DM Sans 600 wordmark.
const HEIGHTS = [0.5, 0.75, 1.0, 0.75, 0.5];

interface Props {
  size?: number;
  showText?: boolean;
  /** "brand" = bars/text in --ink (on a light background); "paper" = in --canvas (on a dark background). */
  variant?: "brand" | "paper";
  animate?: boolean;
  animKey?: number;
}

export function NoraLogo({
  size = 20,
  showText = true,
  variant = "brand",
  animate = false,
  animKey = 0,
}: Props) {
  const [phase, setPhase] = useState<"init" | "animating" | "done">(animate ? "init" : "done");

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

  const color = variant === "paper" ? "var(--canvas)" : "var(--ink)";
  const barW = Math.max(2, Math.round(size * 0.12));
  const barGap = Math.max(2, Math.round(size * 0.1));
  const totalW = HEIGHTS.length * barW + (HEIGHTS.length - 1) * barGap;

  return (
    <span className="inline-flex items-center select-none" style={{ gap: Math.round(size * 0.34) }}>
      <span
        className="inline-flex items-center justify-center"
        style={{ width: totalW, height: size, gap: barGap }}
      >
        {HEIGHTS.map((h, i) => {
          const finalH = h * size;
          const baseStyle: React.CSSProperties = {
            display: "block",
            width: barW,
            height: finalH,
            background: color,
            borderRadius: barW / 2,
            transformOrigin: "bottom center",
          };
          if (!animate || phase === "done") {
            return <span key={i} style={{ ...baseStyle, opacity: 1 }} />;
          }
          if (phase === "init") {
            return <span key={i} style={{ ...baseStyle, transform: "scaleY(0)", opacity: 0 }} />;
          }
          return (
            <span
              key={i}
              style={{
                ...baseStyle,
                transform: "scaleY(1)",
                opacity: 1,
                transition: `transform 420ms var(--ease-out-expo) ${i * 60}ms, opacity 360ms ease ${i * 60}ms`,
              }}
            />
          );
        })}
      </span>
      {showText && (
        <span
          style={{
            fontFamily: "var(--display)",
            fontSize: Math.round(size * 0.95),
            fontWeight: 600,
            letterSpacing: "-0.01em",
            color,
            opacity: !animate || phase === "done" ? 1 : 0,
            transform: !animate || phase === "done" ? "translateX(0)" : "translateX(-3px)",
            transition: "opacity 320ms ease 380ms, transform 320ms ease 380ms",
          }}
        >
          Nora
        </span>
      )}
    </span>
  );
}
