import { forwardRef } from "react";

interface IconButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  size?: "md" | "sm";
}

/**
 * Square, consistent icon-only button (titlebar, toolbars, row actions).
 * 30x30 (md) or 26x26 (sm) — kills the ad-hoc 28/30/26 scattered around.
 */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { size = "md", className = "", type = "button", ...rest },
  ref,
) {
  const cls = ["ui-icon-btn", size === "sm" ? "ui-icon-btn--sm" : "", className]
    .filter(Boolean)
    .join(" ");
  return <button ref={ref} type={type} className={cls} {...rest} />;
});
