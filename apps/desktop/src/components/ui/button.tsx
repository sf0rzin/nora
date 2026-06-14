import { forwardRef } from "react";

type Variant = "primary" | "secondary" | "ghost" | "danger" | "accent";
type Size = "md" | "sm";

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  block?: boolean;
}

/**
 * Botão primitivo do NORA Desktop. Tamanhos e estados (hover/focus/disabled)
 * vivem em `.ui-btn*` no styles.css — toda tela usa este componente em vez de
 * estilo inline, garantindo padding/raio/peso/altura idênticos em todo lugar.
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
