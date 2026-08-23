import Link from "next/link";
import type { Route } from "next";

import { listParticipants } from "@/lib/api/client";
import type { ParticipantIdentity } from "@/lib/api/types";
import { extraVariants, summarize } from "@/lib/people/identities";
import { LOCALE, strings } from "@/lib/strings";

/**
 * NORA Core — People (US13, ADR 0048).
 *
 * The consumer `GET /meetings/participants` did not have. The endpoint shipped complete — matched,
 * deduplicated, authorized over the caller's visible set — and no screen called it, so the story
 * was delivered with no way for a user to reach it.
 *
 * Nothing is stored and nothing is edited here: a person IS the set of roster entries that matched
 * each other, exactly as a project is a tag. Two things the screen must not hide, both from the
 * endpoint's own contract: the grouping is fuzzy, so the spellings that produced it are printed
 * beside the name; and the numbers are relative to what THIS user may read, which the note under
 * the header says rather than leaving to be discovered.
 */

export const dynamic = "force-dynamic";

const copy = strings.people;

function fmtDate(iso?: string | null): string {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleDateString(LOCALE, { day: "2-digit", month: "short", year: "numeric" });
  } catch {
    return iso;
  }
}

function PersonCard({ p }: { p: ParticipantIdentity }) {
  const extras = extraVariants(p);
  return (
    <div
      style={{
        border: "1px solid var(--border)",
        borderRadius: 14,
        background: "var(--canvas)",
        padding: 18,
        display: "flex",
        flexDirection: "column",
        gap: 12,
      }}
    >
      <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 10 }}>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 15, fontWeight: 500, letterSpacing: "-0.012em", color: "var(--ink)" }}>
            {p.displayName}
          </div>
          {p.email && (
            <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 2, overflowWrap: "anywhere" }}>
              {p.email}
            </div>
          )}
        </div>
        <span className="chip">{p.isInternal ? copy.internal : copy.external}</span>
      </div>

      <div style={{ display: "flex", gap: 14, fontSize: 12, color: "var(--muted)", flexWrap: "wrap" }}>
        <span>
          <strong style={{ fontWeight: 500, color: "var(--ink)" }}>{p.meetingCount}</strong>{" "}
          {copy.meetingCount(p.meetingCount)}
        </span>
        <span>
          {copy.lastSeenPrefix} {fmtDate(p.lastSeenAt)}
        </span>
        <span>
          {copy.firstSeenPrefix} {fmtDate(p.firstSeenAt)}
        </span>
      </div>

      {/* The matching is fuzzy on the name side. Printing the spellings that produced the group is
          what lets a reader catch two different people merged into one. */}
      {extras.length > 0 && (
        <div style={{ fontSize: 11.5, color: "var(--muted)", lineHeight: 1.5 }}>
          {copy.alsoKnownAs}: {extras.join(" · ")}
        </div>
      )}

      {p.meetings.length > 0 && (
        <div style={{ borderTop: "1px solid var(--border)", paddingTop: 12 }}>
          <div className="sec-label" style={{ marginBottom: 6 }}>
            {copy.recentMeetings}
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
            {p.meetings.map((m) => (
              <Link
                key={m.id}
                href={`/meetings/${m.id}` as Route}
                className="nora-row"
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  padding: "6px 8px",
                  borderRadius: 8,
                  fontSize: 12.5,
                  color: "var(--ink)",
                }}
              >
                <span
                  style={{
                    flex: 1,
                    minWidth: 0,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap",
                  }}
                >
                  {m.title}
                </span>
                <span style={{ color: "var(--muted)", flexShrink: 0 }}>{fmtDate(m.startedAt)}</span>
              </Link>
            ))}
          </div>
          {/* The list is capped at ten by the API; the count above it is not. */}
          {p.meetingCount > p.meetings.length && (
            <div style={{ fontSize: 11, color: "var(--muted)", marginTop: 6 }}>
              {copy.meetingsTruncated(p.meetingCount)}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default async function PeoplePage() {
  let items: ParticipantIdentity[] = [];
  let errorMessage: string | null = null;
  try {
    items = (await listParticipants()).items ?? [];
  } catch (err) {
    errorMessage = err instanceof Error ? err.message : copy.loadFailed;
  }

  const summary = summarize(items);

  return (
    <div className="page">
      <header style={{ marginBottom: 8 }}>
        <h1 className="h1">{copy.title}</h1>
        <p className="lede" style={{ marginTop: 8, maxWidth: 620 }}>
          {copy.lede}
        </p>
      </header>

      {errorMessage && (
        <div style={{ marginTop: 16, padding: "10px 14px", borderRadius: 9, border: "1px solid var(--border)", background: "var(--chip)", fontSize: 13, color: "var(--muted)" }}>
          {copy.loadFailed} ({errorMessage}). {copy.loadFailedSuffix}
        </div>
      )}

      {items.length > 0 && (
        <>
          <div style={{ display: "flex", gap: 14, fontSize: 12.5, color: "var(--muted)", marginTop: 18, flexWrap: "wrap" }}>
            <span>
              <strong style={{ fontWeight: 500, color: "var(--ink)" }}>{summary.people}</strong>{" "}
              {copy.peopleCount(summary.people)}
            </span>
            <span>
              {summary.internal} {copy.internal.toLowerCase()}
            </span>
            <span>
              {summary.external} {copy.external.toLowerCase()}
            </span>
          </div>

          {/* Two users of one tenant can legitimately see different people here. Saying so is
              cheaper than letting somebody discover it by comparing screens with a colleague. */}
          <p style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 10, lineHeight: 1.6, maxWidth: 620 }}>
            {copy.scopeNote}
          </p>

          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))",
              gap: 12,
              marginTop: 20,
            }}
          >
            {items.map((p) => (
              <PersonCard key={p.id} p={p} />
            ))}
          </div>
        </>
      )}

      {items.length === 0 && errorMessage === null && (
        <div
          style={{
            border: "1px dashed var(--border-strong)",
            borderRadius: 16,
            padding: "56px 24px",
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            gap: 20,
            textAlign: "center",
            marginTop: 24,
            background: "var(--canvas)",
          }}
        >
          <div style={{ maxWidth: 420 }}>
            <h2 style={{ fontFamily: "var(--display)", fontSize: 18, fontWeight: 500, letterSpacing: "-0.018em", margin: "0 0 6px", color: "var(--ink)" }}>
              {copy.emptyTitle}
            </h2>
            <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
              {copy.emptyBody}
            </p>
          </div>
          <Link
            href={"/meetings/upload" as Route}
            style={{ display: "inline-flex", alignItems: "center", gap: 7, padding: "9px 16px", background: "var(--ink)", color: "var(--canvas)", borderRadius: 9, fontSize: 13, fontWeight: 500 }}
          >
            {copy.emptyUploadCta}
          </Link>
        </div>
      )}
    </div>
  );
}
