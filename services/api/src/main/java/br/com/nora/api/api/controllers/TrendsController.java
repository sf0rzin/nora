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
import br.com.nora.api.application.trends.TrendsService;
import br.com.nora.api.application.trends.TrendsView;
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
 * Trends panel (US21): task load over time and the themes that keep coming back. Its own controller
 * rather than a tenth endpoint on {@code MeetingsController}, which is past 800 lines, and it
 * changes nothing about authorization by being here — it uses the same {@code requireAnyAllow} plus
 * {@code filterAllowed} pair the listings use.
 *
 * <p><b>Aggregate authorization, which is where an endpoint like this leaks without looking like
 * it.</b> A number computed by {@code SELECT ... WHERE tenant_id = ?} respects the tenant and NOT
 * the per-item policy: a user carrying a conditional Deny over some meetings would see those
 * meetings inside a count, and a count is information — "the legal department opened 40 tasks last
 * week" is a fact about meetings that user was refused. This is the same shape of hole {@code GET
 * /meetings/search} documents having had.
 *
 * <p>Two ways out were available. The one NOT taken was declaring the panel a tenant-level
 * aggregate behind a new permission such as {@code meeting:trends:read}: it is cheaper, but it
 * invents an action no existing policy grants, so the panel would be invisible until an operator
 * writes one, and the instruction attached to it — "grant it to whoever may see everything" — is
 * the kind of rule that gets granted broadly once and then quietly outlives the reason.
 *
 * <p>What ships instead is the listing's own answer, unchanged. {@code uniformDecision} asks
 * whether any statement of this user can tell two meetings of the tenant apart. When it cannot —
 * Root, or a plain {@code meeting:read} Allow over the wildcard — the decision is the same for
 * every meeting and the aggregate runs tenant-wide, which is provably the same set. When it can,
 * the visible meetings are resolved item by item with {@code filterAllowed} and every aggregate is
 * restricted to exactly those ids. No meeting is ever counted that the caller could not open.
 *
 * <p>The cost of the second path is the same one {@code GET /meetings} already pays: it loads the
 * tenant's meetings to filter them. It is only reached by a tenant that actually writes per-meeting
 * or conditional statements, and the response says which path answered ({@code scopeStrategy}).
 */
@RestController
@RequestMapping("/trends")
public class TrendsController {

    private final TrendsService trends;
    private final MeetingService meetings;
    private final AuthorizationService authz;

    public TrendsController(
            TrendsService trends, MeetingService meetings, AuthorizationService authz) {
        this.trends = trends;
        this.meetings = meetings;
        this.authz = authz;
    }

    /**
     * The panel. {@code granularity} is {@code WEEK} or {@code MONTH}; {@code from} and {@code to}
     * are ISO-8601 offset timestamps and both are optional — absent means the last twelve weeks (or
     * six months), and a range wider than the ceiling is clamped rather than refused.
     */
    @GetMapping
    @RequiresPermission(action = "meeting:read", resource = ResourceType.MEETING, anyAllow = true)
    public TrendsView get(
            @RequestParam(name = "granularity", required = false) String granularity,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return trends.compute(
                resolveScope(principal),
                parseGranularity(granularity),
                parseInstant(from, "from"),
                parseInstant(to, "to"));
    }

    /**
     * Turns the caller's IAM position into the set the aggregation may see. The annotation above is
     * only the pre-gate; this is where the visible set is decided.
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
        // ones count. No date filter here on purpose: a task opened this week can belong to a
        // meeting held months ago, and the range is applied to the events inside the aggregation.
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
            return Granularity.WEEK;
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
