import { forwardRef } from "react";

/** Text input primitive (focus with a --accent-glow ring). */
export const Input = forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", ...rest }, ref) {
    return <input ref={ref} className={["ui-input", className].filter(Boolean).join(" ")} {...rest} />;
  },
);

/** Textarea primitive (same look as Input; no resize by default). */
export const Textarea = forwardRef<
  HTMLTextAreaElement,
  React.TextareaHTMLAttributes<HTMLTextAreaElement>
>(function Textarea({ className = "", ...rest }, ref) {
  return (
    <textarea ref={ref} className={["ui-input", className].filter(Boolean).join(" ")} {...rest} />
  );
});

/** Grouper for label + control + help, with a consistent gap. */
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
