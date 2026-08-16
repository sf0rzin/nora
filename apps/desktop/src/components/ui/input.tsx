import { forwardRef } from "react";

/** Text input primitive (focus with a --accent-glow ring). */
export const Input = forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", ...rest }, ref) {
    return <input ref={ref} className={["ui-input", className].filter(Boolean).join(" ")} {...rest} />;
  },
);
