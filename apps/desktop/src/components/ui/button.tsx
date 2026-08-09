import { forwardRef } from "react";

type Variant = "primary" | "secondary" | "ghost" | "danger" | "accent";
type Size = "md" | "sm";

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  block?: boolean;
}

/**
 * NORA Desktop button primitive. Sizes and states (hover/focus/disabled)
 * live in `.ui-btn*` in styles.css — every screen uses this component instead of
 * inline styling, guaranteeing identical padding/radius/weight/height everywhere.
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "secondary", size = "md", block, className = "", type = "button", ...rest },
  ref,
) {
  const cls = [
    "ui-btn",
    `ui-btn--${variant}`,
    size === "sm" ? "ui-btn--sm" : "",
    block ? "ui-btn--block" : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");
  return <button ref={ref} type={type} className={cls} {...rest} />;
});
