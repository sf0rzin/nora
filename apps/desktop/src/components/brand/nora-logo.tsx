import { useEffect, useState } from "react";

const HEIGHTS = [0.42, 0.78, 1.0, 0.66, 0.52];
const BAR_W = 3;
const BAR_GAP = 2.5;

interface Props {
  size?: number;
  showText?: boolean;
  animate?: boolean;
  animKey?: number;
}

export function NoraLogo({
  size = 20,
  showText = true,
  animate = false,
  animKey = 0,
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

  return (
    <span className="inline-flex items-center gap-[7px] select-none">
      <span
        className="inline-flex items-center justify-center"
        style={{ width: totalW, height: size, gap: BAR_GAP }}
      >
        {HEIGHTS.map((h, i) => {
          const finalH = h * size;
          const baseStyle: React.CSSProperties = {
            display: "block",
            width: BAR_W,
            background: "var(--ink)",
            borderRadius: BAR_W,
            transformOrigin: "bottom center",
          };
          if (!animate) {
            return <span key={i} style={{ ...baseStyle, height: finalH }} />;
          }
          if (phase === "init") {
            return (
              <span
                key={i}
                style={{
                  ...baseStyle,
                  height: finalH,
                  transform: "scaleY(0)",
                  opacity: 0,
                }}
              />
            );
          }
          if (phase === "animating") {
            return (
              <span
                key={i}
                style={{
                  ...baseStyle,
                  height: finalH,
                  transform: "scaleY(1)",
                  opacity: 1,
                  transition: `transform 480ms cubic-bezier(.22,.8,.36,1) ${i * 70}ms, opacity 380ms ease ${i * 70}ms`,
                }}
              />
            );
          }
          return (
            <span
              key={i}
              style={{ ...baseStyle, height: finalH, opacity: 1 }}
            />
          );
        })}
      </span>
      {showText && (
        <span
          style={{
            fontFamily: "var(--display)",
            fontSize: 15.5,
            fontWeight: 500,
            letterSpacing: "-0.01em",
            color: "var(--ink)",
            opacity: !animate || phase === "done" ? 1 : 0,
            transform:
              !animate || phase === "done" ? "translateX(0)" : "translateX(-3px)",
            transition: "opacity 320ms ease 380ms, transform 320ms ease 380ms",
          }}
        >
          NORA
        </span>
      )}
    </span>
  );
}
