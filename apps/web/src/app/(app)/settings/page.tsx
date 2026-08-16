import { redirect } from "next/navigation";

/**
 * NORA Core — `/settings` index.
 *
 * The prefix had no page, so the bare URL 404'd while `/settings/context` and
 * `/settings/iam` both worked. That is a broken link waiting to happen: `/settings` is
 * what a person types, what a stale bookmark holds, and what the gear button now points
 * at.
 *
 * A redirect rather than a hub screen: the settings hub already exists inside
 * `context/page.tsx`, which renders its own section switcher (Conta · Segurança ·
 * Workspace · Contexto da empresa). A second index in front of it would be one more
 * click to the same place. IAM is reached from the sidebar, being its own route with its
 * own state rather than a section of that switcher.
 */
export default function SettingsIndexPage() {
  redirect("/settings/context");
}
