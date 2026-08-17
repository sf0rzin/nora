/**
 * NORA Flows — builder block catalog (Phase 1 + Phase 2 integration actions).
 *
 * Single source of truth on the front end for the blocks the engine accepts (ADR 0030).
 * The backend rejects unknown types, so NEVER add a block here without the
 * corresponding executor in the engine (services/api).
 */
import type { ReactNode } from "react";

import type { WorkflowNodeKind } from "@/lib/api/types";
import { strings } from "@/lib/strings";

/** Visual metadata for each node role (trigger/condition/action). */
export const KIND_META: Record<
  WorkflowNodeKind,
  { label: string; labelPlural: string; color: string }
> = {
  trigger: { ...strings.flows.kinds.trigger, color: "var(--accent)" },
  condition: { ...strings.flows.kinds.condition, color: "var(--warn)" },
  action: { ...strings.flows.kinds.action, color: "var(--success)" },
};

/** Node role icon — inline 14px SVG in the app's standard (stroke 1.7). */
export function KindIcon({ kind, size = 14 }: { kind: WorkflowNodeKind; size?: number }): ReactNode {
  const common = {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.7,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
  };
  switch (kind) {
    case "trigger":
      // Bolt — event that fires the flow.
      return (
        <svg {...common}>
          <path d="M13 2 4.5 13.5h6L11 22l8.5-11.5h-6z" />
        </svg>
      );
    case "condition":
      // Funnel — filters the execution path.
      return (
        <svg {...common}>
          <path d="M3 5h18l-7 8v6l-4-2v-4z" />
        </svg>
      );
    case "action":
      // Paper plane — does something in the real world.
      return (
        <svg {...common}>
          <path d="m22 2-11 11" />
          <path d="M22 2 15 22l-4-9-9-4z" />
        </svg>
      );
  }
}

/** Common props for the per-block icons — same stroke as KindIcon (stroke 1.7). */
function svgProps(size: number) {
  return {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.7,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
  };
}

/** Envelope with a send arrow — "send via Gmail" action. */
export function GmailIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <path d="M21 12V7a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h8" />
      <path d="m3 7 9 6 9-6" />
      <path d="M15.5 19H22" />
      <path d="m19.5 16.5 2.5 2.5-2.5 2.5" />
    </svg>
  );
}

/** Calendar with a "+" — "create Calendar event" action. */
export function CalendarPlusIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <rect x="3" y="5" width="18" height="16" rx="2.5" />
      <path d="M8 3v4M16 3v4M3 10h18" />
      <path d="M12 13.2v4.6M9.7 15.5h4.6" />
    </svg>
  );
}

/** Arrow leaving the box — "call webhook" action (POST out of NORA). */
export function WebhookIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <path d="M14 4h6v6" />
      <path d="M20 4 11 13" />
      <path d="M20 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h5" />
    </svg>
  );
}

/** Chat bubble with two dots — "notify on Discord" action. */
export function DiscordIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8z" />
      <path d="M9 11.5h.01M15 11.5h.01" />
    </svg>
  );
}

/** Circle with a "+" — "create GitHub issue" action (open-issue style). */
export function GitHubIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 8.8v6.4M8.8 12h6.4" />
    </svg>
  );
}

/** Document with lines — the "create page in Notion" action. */
export function NotionIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
      <path d="M14 3v5h5" />
      <path d="M9 13h6M9 17h6" />
    </svg>
  );
}

/** Square with a check — "create Todoist task" action. */
export function TodoistIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <rect x="4" y="4" width="16" height="16" rx="3.5" />
      <path d="m8.5 12.3 2.4 2.4 4.8-5" />
    </svg>
  );
}

/** Kanban board with three columns — "create Linear issue" action. */
export function LinearIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
      <path d="M9.2 4.5v15M14.8 4.5v15" />
    </svg>
  );
}

/** Hash sign (#) — "post to Slack" action (channel). */
export function SlackIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <path d="M10 4 8 20M16 4l-2 16" />
      <path d="M5 9.5h15M4 14.5h15" />
    </svg>
  );
}

/** Closed envelope — "send via Outlook" action. */
export function OutlookIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="m3 7.5 9 6 9-6" />
    </svg>
  );
}

/** Calendar with a check — "Outlook Calendar event" action. */
export function CalendarCheckIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <rect x="3" y="5" width="18" height="16" rx="2.5" />
      <path d="M8 3v4M16 3v4M3 10h18" />
      <path d="m9.3 15.2 1.9 1.9 3.5-3.7" />
    </svg>
  );
}

/** Paper plane in a circle — "notify on Telegram" action. */
export function TelegramIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="m16.2 8.2-8.4 3.2 3.2 1.5 1.4 3.3z" />
    </svg>
  );
}

/** Board with two lists (one shorter) — "create Trello card" action. */
export function TrelloIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <rect x="4" y="4" width="16" height="16" rx="3" />
      <path d="M8.5 8v8M15.5 8v4.5" />
    </svg>
  );
}

/** Clock — the `schedule.cron` trigger (fires on a timer, not on an event). */
export function ClockIcon({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...svgProps(size)}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 1.8" />
    </svg>
  );
}

/** Valid Discord channel webhook prefixes — same rule as the backend. */
export const DISCORD_WEBHOOK_PREFIXES = [
  "https://discord.com/api/webhooks/",
  "https://discordapp.com/api/webhooks/",
];

/** true when the value is a Discord webhook URL. */
export function isDiscordWebhook(value: unknown): boolean {
  return (
    typeof value === "string" && DISCORD_WEBHOOK_PREFIXES.some((p) => value.trim().startsWith(p))
  );
}

/** true when the value follows the owner/name format — same rule as the backend. */
export function isGitHubRepo(value: unknown): boolean {
  return typeof value === "string" && value.trim().length > 0 && value.trim().includes("/");
}

/**
 * The schedule vocabulary the backend accepts (ADR 0047 §2): three frequencies, and no way to
 * express anything faster than hourly. It is deliberately NOT a cron expression — a full parser
 * accepts one that fires every second, which this substrate cannot honour.
 */
export const SCHEDULE_FREQUENCIES = ["hourly", "daily", "weekly"] as const;
export type ScheduleFrequency = (typeof SCHEDULE_FREQUENCIES)[number];

/** Weekday wire values, in the order the select shows them. */
export const SCHEDULE_WEEKDAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;

const PRIORITY_LABEL = strings.flows.priority;
const WEEKDAY_LABEL = strings.flows.weekday;
const COPY = strings.flows.blocks;
const SUMMARY = strings.flows.blockSummary;

function asNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isInteger(value)) return value;
  if (typeof value === "string" && value.trim() !== "" && /^\d+$/.test(value.trim())) {
    return Number(value.trim());
  }
  return null;
}

function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

/**
 * true when the schedule params are complete and in range — the SAME rule the backend applies in
 * `ScheduleSpec.parse`. Duplicated on purpose: the canvas has to refuse before the round trip, and
 * the backend has to refuse regardless of what the canvas sent.
 */
export function isValidSchedule(params: Record<string, unknown>): boolean {
  const minute = asNumber(params.minute);
  if (minute === null || minute < 0 || minute > 59) return false;
  if (params.frequency === "hourly") return true;
  const hour = asNumber(params.hour);
  if (hour === null || hour < 0 || hour > 23) return false;
  if (params.frequency === "daily") return true;
  if (params.frequency === "weekly") {
    return typeof params.weekday === "string" && params.weekday in WEEKDAY_LABEL;
  }
  return false;
}

/** One-line schedule rendering inside the canvas node — e.g. "todo dia 09:00". */
export function scheduleSummary(params: Record<string, unknown>): string {
  if (!isValidSchedule(params)) return SUMMARY.setSchedule;
  const minute = asNumber(params.minute) as number;
  if (params.frequency === "hourly") return SUMMARY.scheduleHourly(pad2(minute));
  const time = `${pad2(asNumber(params.hour) as number)}:${pad2(minute)}`;
  if (params.frequency === "daily") return SUMMARY.scheduleDaily(time);
  return SUMMARY.scheduleWeekly(WEEKDAY_LABEL[params.weekday as string], time);
}

export interface BlockMeta {
  kind: WorkflowNodeKind;
  /** Type accepted by the engine (do NOT rename — contract with the backend). */
  type: string;
  name: string;
  description: string;
  /** Initial params when the block lands on the canvas. */
  defaultParams: Record<string, unknown>;
  /** Summary line of the params shown inside the node (null = no summary). */
  summary: (params: Record<string, unknown>) => string | null;
  /** The block's own icon; when absent, uses the role icon (KindIcon). */
  Icon?: (props: { size?: number }) => ReactNode;
}

/**
 * Catalog v1 — exactly the types the engine runs today.
 *
 * The first three triggers are the three events `AnalysisService` publishes and `WorkflowEngine`
 * dispatches. The fourth, `schedule.cron`, is fired by `ScheduledFlowRunner` on a timer (US75,
 * ADR 0047) — until that existed it was refused on save, because a flow the backend never runs
 * must not be offered here.
 *
 * The FIRST trigger in this list is the one `/flows/new` drops on an empty canvas
 * (`flow-editor.tsx`), so keep the anchor trigger at the top.
 */
export const CATALOG: BlockMeta[] = [
  {
    kind: "trigger",
    type: "meeting.analysis_completed",
    ...COPY["meeting.analysis_completed"],
    defaultParams: {},
    summary: () => null,
  },
  {
    kind: "trigger",
    type: "action_item.created",
    ...COPY["action_item.created"],
    defaultParams: {},
    summary: () => null,
  },
  {
    kind: "trigger",
    type: "meeting.risk_detected",
    ...COPY["meeting.risk_detected"],
    defaultParams: {},
    summary: () => null,
  },
  {
    kind: "trigger",
    type: "schedule.cron",
    ...COPY["schedule.cron"],
    // Daily at 09:00 rather than hourly: the default a user keeps without thinking should be the
    // quiet one, and hourly on a workspace with meetings every day is a lot of Slack messages.
    defaultParams: { frequency: "daily", hour: 9, minute: 0 },
    summary: (p) => scheduleSummary(p),
    Icon: ClockIcon,
  },
  {
    kind: "condition",
    type: "productivity_score_below",
    ...COPY.productivity_score_below,
    defaultParams: { value: 70 },
    summary: (p) =>
      typeof p.value === "number" ? SUMMARY.scoreBelow(p.value) : SUMMARY.setThreshold,
  },
  {
    kind: "condition",
    type: "customer_confidence_below",
    ...COPY.customer_confidence_below,
    defaultParams: { value: 60 },
    summary: (p) =>
      typeof p.value === "number" ? SUMMARY.confidenceBelow(p.value) : SUMMARY.setThreshold,
  },
  {
    kind: "condition",
    type: "tag_equals",
    ...COPY.tag_equals,
    defaultParams: { value: "" },
    summary: (p) =>
      typeof p.value === "string" && p.value.trim()
        ? SUMMARY.tag(p.value.trim())
        : SUMMARY.setTag,
  },
  {
    kind: "condition",
    type: "priority_equals",
    ...COPY.priority_equals,
    defaultParams: { value: "HIGH" },
    summary: (p) =>
      typeof p.value === "string" && PRIORITY_LABEL[p.value]
        ? SUMMARY.priority(PRIORITY_LABEL[p.value])
        : SUMMARY.setPriority,
  },
  {
    kind: "action",
    type: "send_email",
    ...COPY.send_email,
    defaultParams: { to: "" },
    summary: (p) =>
      typeof p.to === "string" && p.to.trim()
        ? SUMMARY.recipient(p.to.trim())
        : SUMMARY.setRecipient,
  },
  {
    kind: "action",
    type: "gmail_send_email",
    ...COPY.gmail_send_email,
    defaultParams: { to: "" },
    summary: (p) =>
      typeof p.to === "string" && p.to.trim()
        ? SUMMARY.recipient(p.to.trim())
        : SUMMARY.setRecipient,
    Icon: GmailIcon,
  },
  {
    kind: "action",
    type: "calendar_create_event",
    ...COPY.calendar_create_event,
    defaultParams: { title: "", startInDays: 1, hour: 10, durationMinutes: 30 },
    summary: (p) => eventSummary(p),
    Icon: CalendarPlusIcon,
  },
  {
    kind: "action",
    type: "call_webhook",
    ...COPY.call_webhook,
    defaultParams: { url: "" },
    summary: (p) =>
      typeof p.url === "string" && p.url.trim() ? urlHost(p.url) : SUMMARY.setUrl,
    Icon: WebhookIcon,
  },
  {
    kind: "action",
    type: "discord_post_message",
    ...COPY.discord_post_message,
    defaultParams: { webhookUrl: "" },
    summary: (p) =>
      isDiscordWebhook(p.webhookUrl)
        ? SUMMARY.webhookConfigured
        : typeof p.webhookUrl === "string" && p.webhookUrl.trim()
          ? SUMMARY.webhookInvalid
          : SUMMARY.setWebhook,
    Icon: DiscordIcon,
  },
  {
    kind: "action",
    type: "github_create_issue",
    ...COPY.github_create_issue,
    defaultParams: { repo: "" },
    summary: (p) =>
      isGitHubRepo(p.repo)
        ? SUMMARY.repo(String(p.repo).trim())
        : typeof p.repo === "string" && p.repo.trim()
          ? SUMMARY.repoFormat
          : SUMMARY.setRepo,
    Icon: GitHubIcon,
  },
  {
    kind: "action",
    type: "notion_create_page",
    ...COPY.notion_create_page,
    defaultParams: { parentPageId: "" },
    summary: (p) =>
      typeof p.parentPageId === "string" && p.parentPageId.trim()
        ? SUMMARY.page(truncate(p.parentPageId.trim(), 12))
        : SUMMARY.setParentPage,
    Icon: NotionIcon,
  },
  {
    kind: "action",
    type: "todoist_create_task",
    ...COPY.todoist_create_task,
    defaultParams: {},
    summary: () => SUMMARY.oneTaskPerActionItem,
    Icon: TodoistIcon,
  },
  {
    kind: "action",
    type: "linear_create_issue",
    ...COPY.linear_create_issue,
    defaultParams: { teamKey: "" },
    summary: (p) =>
      typeof p.teamKey === "string" && p.teamKey.trim()
        ? SUMMARY.team(p.teamKey.trim())
        : SUMMARY.firstWorkspaceTeam,
    Icon: LinearIcon,
  },
  {
    kind: "action",
    type: "slack_post_message",
    ...COPY.slack_post_message,
    defaultParams: { channel: "" },
    summary: (p) =>
      typeof p.channel === "string" && p.channel.trim()
        ? SUMMARY.channel(p.channel.trim())
        : SUMMARY.setChannel,
    Icon: SlackIcon,
  },
  {
    kind: "action",
    type: "outlook_send_email",
    ...COPY.outlook_send_email,
    defaultParams: { to: "" },
    summary: (p) =>
      typeof p.to === "string" && p.to.trim()
        ? SUMMARY.recipient(p.to.trim())
        : SUMMARY.setRecipient,
    Icon: OutlookIcon,
  },
  {
    kind: "action",
    type: "mscalendar_create_event",
    ...COPY.mscalendar_create_event,
    defaultParams: { title: "", startInDays: 1, hour: 10, durationMinutes: 30 },
    summary: (p) => eventSummary(p),
    Icon: CalendarCheckIcon,
  },
  {
    kind: "action",
    type: "telegram_send_message",
    ...COPY.telegram_send_message,
    defaultParams: {},
    summary: () => SUMMARY.summaryInPairedChat,
    Icon: TelegramIcon,
  },
  {
    kind: "action",
    type: "trello_create_card",
    ...COPY.trello_create_card,
    defaultParams: { listId: "" },
    summary: (p) =>
      typeof p.listId === "string" && p.listId.trim()
        ? SUMMARY.list(truncate(p.listId.trim(), 12))
        : SUMMARY.setList,
    Icon: TrelloIcon,
  },
];

/** Truncates long values for the node summary (e.g. Notion page ID). */
function truncate(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max)}…` : value;
}

/** URL host for the node summary (e.g. "hooks.example.com"). */
function urlHost(url: string): string {
  try {
    return new URL(url.trim()).host || SUMMARY.invalidUrl;
  } catch {
    return SUMMARY.invalidUrl;
  }
}

/** calendar_create_event summary — e.g. "tomorrow at 10h, 30min", in pt-BR. */
function eventSummary(p: Record<string, unknown>): string {
  const days = typeof p.startInDays === "number" ? p.startInDays : 1;
  const hour = typeof p.hour === "number" ? p.hour : 10;
  const duration = typeof p.durationMinutes === "number" ? p.durationMinutes : 30;
  return SUMMARY.event(days, hour, duration);
}

/** Looks up a block's meta by type. Types outside the catalog return undefined. */
export function blockMeta(type: string): BlockMeta | undefined {
  return CATALOG.find((b) => b.type === type);
}

/**
 * Placeholders the backend supports in subject/body of the e-mail actions
 * (send_email, gmail_send_email, outlook_send_email) and in the title of the
 * calendar events (calendar_create_event, mscalendar_create_event).
 * Rendered as clickable chips in the parameters panel.
 */
export const EMAIL_PLACEHOLDERS: { token: string; hint: string }[] = Object.entries(
  strings.flows.placeholderHints,
).map(([token, hint]) => ({ token, hint }));
