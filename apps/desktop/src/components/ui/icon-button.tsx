import { forwardRef } from "react";

interface IconButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  size?: "md" | "sm";
}

/**
 * Botão só-ícone quadrado e consistente (titlebar, toolbars, ações de linha).
 * 30x30 (md) ou 26x26 (sm) — acaba com os 28/30/26 ad-hoc espalhados.
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
