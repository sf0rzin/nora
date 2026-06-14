import { forwardRef } from "react";

/** Input de texto primitivo (foco com ring de --accent-glow). */
export const Input = forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", ...rest }, ref) {
    return <input ref={ref} className={["ui-input", className].filter(Boolean).join(" ")} {...rest} />;
  },
);

/** Textarea primitiva (mesmo visual do Input; sem resize por padrão). */
export const Textarea = forwardRef<
  HTMLTextAreaElement,
  React.TextareaHTMLAttributes<HTMLTextAreaElement>
>(function Textarea({ className = "", ...rest }, ref) {
  return (
    <textarea ref={ref} className={["ui-input", className].filter(Boolean).join(" ")} {...rest} />
  );
});

/** Agrupador label + controle + ajuda, com gap consistente. */
export function Field({
  label,
  help,
  error,
  htmlFor,
  children,
  className = "",
}: {
  label?: string;
  help?: string;
  error?: string;
  htmlFor?: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={["ui-field", className].filter(Boolean).join(" ")}>
      {label && (
        <label className="ui-field-label" htmlFor={htmlFor}>
          {label}
        </label>
      )}
      {children}
      {(error || help) && (
        <span className={error ? "ui-field-help is-err" : "ui-field-help"}>{error || help}</span>
      )}
    </div>
  );
}
