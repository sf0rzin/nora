package br.com.nora.api.api.controllers;

import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.api.security.ResourceArns;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.meeting.MeetingService;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.application.ports.TrendsRepository.Granularity;
import br.com.nora.api.application.ports.TrendsRepository.Scope;
import br.com.nora.api.application.usage.UsageService;
import br.com.nora.api.application.usage.UsageView;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant usage panel (US33): how much of the product one tenant consumed over a period, answered to
 * that tenant.
 *
 * <p><b>This is not the operator endpoint.</b> {@code GET /admin/platform/telemetry/business} and
 * the cost report next to it are US83: the platform owner's view of every tenant, reached through
 * Cloudflare Access on the control plane and authenticated by an operator identity (ADR 0023/0025).
 * This route is the opposite side — the normal tenant JWT, the normal IAM evaluation, and a query
 * whose WHERE clause names one tenant. The operator's aggregation is not reused: it has no tenant
 * predicate at all, and filtering a cross-tenant result in application code is exactly the shape of
 * mistake the comment at {@code MeetingsController.search} records this codebase having made.
 *
 * <p><b>Aggregate authorization.</b> A number computed by {@code WHERE tenant_id = ?} respects the
 * tenant and NOT the per-item policy, so this endpoint answers it the way {@code GET /trends} does:
 * {@code uniformDecision} asks whether any statement of this caller can tell two meetings of the
 * tenant apart, and when one can, the visible meetings are resolved item by item with {@code
 * filterAllowed} and the meeting counts are restricted to those ids. The answer reports which path
 * ran, in {@code scopeStrategy}.
 *
 * <p><b>The AI half has no per-item answer, so it is withheld rather than approximated.</b> {@code
 * usage_events} is denormalized for the operator's dimensions and carries no meeting id, so a
 * restricted caller cannot be given a tenant-wide AI total without disclosing activity from
 * meetings they may not open. In that case {@code ai.state} comes back as {@code
 * WITHHELD_RESTRICTED_SCOPE}, which is a statement and not a zero.
 *
 * <p>{@code meeting:read} is the gate for both halves, and no new action is invented: the panel
 * describes work done on the tenant's meetings, and a permission nothing grants would leave the
 * screen invisible until an operator wrote a policy for it — the alternative {@code
 * TrendsController} considered and rejected for the same reason.
 */
@RestController
@RequestMapping("/usage")
public class UsageController {

    private final UsageService usage;
    private final MeetingService meetings;
    private final AuthorizationService authz;

    public UsageController(
            UsageService usage, MeetingService meetings, AuthorizationService authz) {
        this.usage = usage;
        this.meetings = meetings;
        this.authz = authz;
    }

    /**
     * The panel. {@code granularity} is {@code WEEK} or {@code MONTH} and defaults to {@code
     * MONTH}; {@code from} and {@code to} are ISO-8601 offset timestamps and both are optional —
     * absent means the last six months (or twelve weeks), and a range wider than the ceiling is
     * clamped rather than refused.
     */
    @GetMapping
    @RequiresPermission(action = "meeting:read", resource = ResourceType.MEETING, anyAllow = true)
    public UsageView get(
            @RequestParam(name = "granularity", required = false) String granularity,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return usage.compute(
                resolveScope(principal),
                parseGranularity(granularity),
                parseInstant(from, "from"),
                parseInstant(to, "to"));
    }

    /**
     * Turns the caller's IAM position into the set the aggregation may see. The annotation above is
     * only the pre-gate; this is where the visible set is decided. Same resolution {@code
     * TrendsController} performs, deliberately duplicated rather than shared: the two panels must
     * be free to disagree later about which action gates them, and a shared helper would hide that.
     */
    private Scope resolveScope(AuthenticatedPrincipal principal) {
        UUID tenantId = principal.tenantId();
        Optional<Boolean> uniform =
                authz.uniformDecision(
                        principal.userId(),
                        tenantId,
                        "meeting:read",
                        ResourceArns.meeting(tenantId, null));
        if (uniform.isPresent()) {
            // A uniform Deny cannot get here — requireAnyAllow refused already — but an aggregate
            // is not the place to rely on that, so it is spelled out as the empty set.
            return Boolean.TRUE.equals(uniform.get())
                    ? Scope.wholeTenant(tenantId)
                    : Scope.ofMeetings(tenantId, List.of());
        }
        // Some statement distinguishes meetings, so only an item-by-item evaluation knows which
        // ones count. No date filter here on purpose: the range is applied inside the aggregation,
        // and the filter answers "may this caller open it", which has no date.
        List<Meeting> candidates = meetings.listAllForAuthFilter(tenantId, MeetingFilter.empty());
        List<Meeting> visible =
                authz.filterAllowed(
                        principal.userId(),
                        tenantId,
                        "meeting:read",
                        candidates,
                        m -> ResourceArns.meeting(tenantId, m.id()),
                        Meeting::attributes);
        return Scope.ofMeetings(tenantId, visible.stream().map(Meeting::id).toList());
    }

    private Granularity parseGranularity(String raw) {
        if (raw == null || raw.isBlank()) {
            return Granularity.MONTH;
        }
        try {
            return Granularity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid granularity: " + raw);
        }
    }

    private OffsetDateTime parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("invalid " + field + " timestamp: " + value);
        }
    }
}
