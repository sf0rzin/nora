package br.com.nora.api.api.mcp;

import br.com.nora.api.api.security.ResourceArns;
import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.customer.CustomerConfidenceService;
import br.com.nora.api.application.embedding.EmbeddingService;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.meeting.MeetingService;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.application.ports.TaskRepository.TaskRow;
import br.com.nora.api.application.task.TaskService;
import br.com.nora.api.domain.analysis.ActionItem;
import br.com.nora.api.domain.analysis.ActionItemStatus;
import br.com.nora.api.domain.analysis.Decision;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Opportunity;
import br.com.nora.api.domain.analysis.Risk;
import br.com.nora.api.domain.customer.BuyingSignal;
import br.com.nora.api.domain.customer.CustomerConfidenceAssessment;
import br.com.nora.api.domain.customer.Objection;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.Participant;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The five read-only MCP tools of ADR 0041 §4, each reproducing the authorization of the REST
 * handler it mirrors.
 *
 * <p><b>The contract this class exists to keep.</b> ADR 0041 §2 fixes the invariant: an MCP client
 * can never see more than the user it acts for can see in the web application. That is not a
 * property of the transport, it is a property of these five methods, so the authorization calls of
 * the controllers were read endpoint by endpoint and are reproduced here verbatim:
 *
 * <ul>
 *   <li>{@code list_meetings} mirrors {@code GET /meetings}: the {@code meeting:read} pre-gate over
 *       the tenant's meeting wildcard, then a per-item filter carrying each meeting's attributes.
 *   <li>{@code get_meeting} mirrors {@code GET /meetings/{id}}: resolve inside the tenant first (so
 *       a foreign id is not found rather than forbidden), then authorize {@code meeting:read} on
 *       that meeting's own ARN WITH its attributes in the context.
 *   <li>{@code search_meetings} mirrors {@code GET /meetings/search}: the pre-gate runs BEFORE the
 *       embedding call, then the candidates are filtered per item.
 *   <li>{@code list_tasks} mirrors {@code GET /tasks}: the {@code task:read} pre-gate, then a
 *       per-item filter over each task's own ARN.
 *   <li>{@code get_customer_confidence} mirrors the {@code customerConfidence} block of {@code GET
 *       /meetings/{id}}, and therefore evaluates {@code meeting:read} on the meeting — the block
 *       has no action of its own, exactly as it has none in the REST detail.
 * </ul>
 *
 * <p><b>No MCP permission vocabulary</b> (ADR 0041 §2). Every action above is one the web surface
 * already evaluates against the same policies. A tenant that wants to keep an agent out of a
 * meeting writes the same conditional Deny it would write for a person.
 *
 * <p><b>Why the pre-gate is not redundant.</b> On a listing, the per-item filter alone already
 * yields an empty result for a caller with no Allow — but only after loading the set. On {@code
 * search_meetings} that ordering is the difference between a refusal and an embedding call billed
 * to an external provider plus a scan of the tenant's vectors, which is the regression {@code
 * MeetingsController.search} documents in its own body.
 *
 * <p><b>Why no repository is reached from here.</b> Every read goes through the application service
 * the controllers use. A handler that queried {@code MeetingRepository} directly "because it is
 * only a read" would lose the per-item filter and return the whole tenant, and row-level security
 * would not catch it: RLS is off by default in this repository, so such a hole passes green in
 * development and leaks in production.
 *
 * <p><b>Deliberate divergence from {@code GET /meetings}: no fast path.</b> The REST listing calls
 * {@code uniformDecision} to keep pagination in SQL when no statement can tell two meetings apart.
 * That is a provably equivalent optimisation, and this adapter does not carry it. Duplicating an
 * optimisation whose correctness argument lives in another class is how two copies of an
 * authorization decision start to diverge; the general path is always taken here, and it is the one
 * the REST listing itself falls back to.
 */
@Component
public class McpToolInvoker {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private static final String MEETING_READ = "meeting:read";
    private static final String TASK_READ = "task:read";

    private final AuthorizationService authz;
    private final MeetingService meetings;
    private final AnalysisService analyses;
    private final CustomerConfidenceService customerConfidence;
    private final TaskService tasks;
    private final EmbeddingService embeddings;

    public McpToolInvoker(
            AuthorizationService authz,
            MeetingService meetings,
            AnalysisService analyses,
            CustomerConfidenceService customerConfidence,
            TaskService tasks,
            EmbeddingService embeddings) {
        this.authz = authz;
        this.meetings = meetings;
        this.analyses = analyses;
        this.customerConfidence = customerConfidence;
        this.tasks = tasks;
        this.embeddings = embeddings;
    }

    /** Raised for a bad argument. Reported as a tool result, never as a JSON-RPC error. */
    public static final class ToolArgumentException extends RuntimeException {
        public ToolArgumentException(String message) {
            super(message);
        }
    }

    /** Raised for a tool name this server does not implement, which IS a JSON-RPC error. */
    public static final class UnknownToolException extends RuntimeException {
        public UnknownToolException(String name) {
            super("Unknown tool: " + name);
        }
    }

    /**
     * Dispatches one {@code tools/call}. Returns the structured payload; the caller wraps it in the
     * protocol's result envelope.
     */
    public ObjectNode invoke(AuthenticatedPrincipal principal, String tool, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() ? F.objectNode() : arguments;
        return switch (tool) {
            case McpToolCatalog.LIST_MEETINGS -> listMeetings(principal, args);
            case McpToolCatalog.GET_MEETING -> getMeeting(principal, args);
            case McpToolCatalog.SEARCH_MEETINGS -> searchMeetings(principal, args);
            case McpToolCatalog.LIST_TASKS -> listTasks(principal, args);
            case McpToolCatalog.GET_CUSTOMER_CONFIDENCE -> getConfidence(principal, args);
            default -> throw new UnknownToolException(tool);
        };
    }

    /* ============================== list_meetings ============================== */

    private ObjectNode listMeetings(AuthenticatedPrincipal principal, JsonNode args) {
        UUID tenantId = principal.tenantId();
        // Pre-gate, same one the annotation on GET /meetings applies: at least one Allow for
        // meeting:read somewhere in the tenant, evaluated before the database is touched.
        authz.requireAnyAllow(
                principal.userId(), tenantId, MEETING_READ, ResourceArns.meeting(tenantId, null));

        int limit = intArg(args, "limit", 20, 1, McpToolCatalog.MAX_LIMIT);
        int offset = intArg(args, "offset", 0, 0, 100_000);
        MeetingFilter filter =
                new MeetingFilter(
                        blankToNull(stringArg(args, "search")), parseStatus(args), null, null);

        List<Meeting> candidates = meetings.listAllForAuthFilter(tenantId, filter);
        List<Meeting> visible = visibleMeetings(principal, candidates);

        int from = Math.min(offset, visible.size());
        int to = Math.min(from + limit, visible.size());
        ArrayNode items = F.arrayNode();
        for (Meeting m : visible.subList(from, to)) {
            items.add(meetingSummary(m));
        }
        ObjectNode result = F.objectNode();
        result.set("meetings", items);
        result.put("totalVisible", visible.size());
        result.put("offset", from);
        return result;
    }

    /* =============================== get_meeting =============================== */

    private ObjectNode getMeeting(AuthenticatedPrincipal principal, JsonNode args) {
        UUID tenantId = principal.tenantId();
        Meeting meeting = requireReadableMeeting(principal, uuidArg(args, "meetingId"));
        ObjectNode result = meetingSummary(meeting);
        result.set("participants", participants(meeting));
        Optional<MeetingAnalysis> found = analyses.findByMeeting(meeting.id(), tenantId);
        if (found.isPresent()) {
            result.set("analysis", analysis(found.get()));
        } else {
            result.putNull("analysis");
        }
        return result;
    }

    /* ============================= search_meetings ============================= */

    private ObjectNode searchMeetings(AuthenticatedPrincipal principal, JsonNode args) {
        UUID tenantId = principal.tenantId();
        // Ordering matters and is the point: the pre-gate runs BEFORE the embedding call, so a
        // caller with no meeting:read is refused without an external request being billed and
        // without the tenant's vectors being scanned to build a result it may not see.
        authz.requireAnyAllow(
                principal.userId(), tenantId, MEETING_READ, ResourceArns.meeting(tenantId, null));

        String query = stringArg(args, "query");
        if (query == null || query.isBlank()) {
            throw new ToolArgumentException("'query' is required and must not be empty.");
        }
        int limit = intArg(args, "limit", 5, 1, 10);

        List<Meeting> candidates = new ArrayList<>();
        for (UUID id : embeddings.search(tenantId, query, limit)) {
            try {
                candidates.add(meetings.getById(id, tenantId));
            } catch (RuntimeException ignored) {
                // Orphan embedding (race with delete/erasure) — skipped, same as the REST search.
            }
        }

        ArrayNode items = F.arrayNode();
        for (Meeting m : visibleMeetings(principal, candidates)) {
            ObjectNode item = F.objectNode();
            item.put("id", m.id().toString());
            item.put("title", m.title());
            putNullable(item, "summarySnippet", m.summarySnippet());
            putNullable(item, "startedAt", m.startedAt() == null ? null : m.startedAt().toString());
            item.put("status", m.processingStatus().name());
            items.add(item);
        }
        ObjectNode result = F.objectNode();
        result.set("meetings", items);
        return result;
    }

    /* ================================ list_tasks ================================ */

    private ObjectNode listTasks(AuthenticatedPrincipal principal, JsonNode args) {
        UUID tenantId = principal.tenantId();
        authz.requireAnyAllow(
                principal.userId(), tenantId, TASK_READ, ResourceArns.task(tenantId, null));

        ActionItemStatus status = parseTaskStatus(args);
        int limit = intArg(args, "limit", 20, 1, McpToolCatalog.MAX_LIMIT);

        List<TaskRow> rows = tasks.list(tenantId, status);
        // Per-item filter over each task's own ARN, so a Deny written against one task id fires.
        // The literal `...:task/*` is matched as plain text on the resource side and would not.
        List<TaskRow> visible =
                authz.filterAllowed(
                        principal.userId(),
                        tenantId,
                        TASK_READ,
                        rows,
                        r -> ResourceArns.task(tenantId, r.id()),
                        r -> Map.of());

        ArrayNode items = F.arrayNode();
        for (TaskRow r : visible.subList(0, Math.min(limit, visible.size()))) {
            items.add(task(r));
        }
        ObjectNode result = F.objectNode();
        result.set("tasks", items);
        result.put("totalVisible", visible.size());
        return result;
    }

    /* ========================= get_customer_confidence ========================== */

    private ObjectNode getConfidence(AuthenticatedPrincipal principal, JsonNode args) {
        Meeting meeting = requireReadableMeeting(principal, uuidArg(args, "meetingId"));
        ObjectNode result = F.objectNode();
        result.put("meetingId", meeting.id().toString());
        Optional<CustomerConfidenceService.ConfidenceView> view =
                customerConfidence.findViewByMeetingId(meeting.id(), principal.tenantId()).stream()
                        .findFirst();
        if (view.isPresent()) {
            result.set("customerConfidence", confidence(view.get()));
        } else {
            result.putNull("customerConfidence");
        }
        return result;
    }

    /* ============================== authorization =============================== */

    /**
     * The two calls {@code GET /meetings/{id}} makes, in its order. Resolving first means a meeting
     * of another tenant raises "not found" instead of "forbidden", which is what keeps the answer
     * from confirming that the id exists somewhere. Authorizing second means the meeting's own
     * attributes are in the evaluation context, which is what lets an attribute-scoped Deny fire.
     */
    private Meeting requireReadableMeeting(AuthenticatedPrincipal principal, UUID meetingId) {
        Meeting meeting = meetings.getById(meetingId, principal.tenantId());
        authz.require(
                principal.userId(),
                principal.tenantId(),
                MEETING_READ,
                ResourceArns.meeting(principal.tenantId(), meeting.id()),
                meeting.attributes());
        return meeting;
    }

    /** Per-item filter with each meeting's attributes as the condition context. */
    private List<Meeting> visibleMeetings(AuthenticatedPrincipal principal, List<Meeting> items) {
        return authz.filterAllowed(
                principal.userId(),
                principal.tenantId(),
                MEETING_READ,
                items,
                m -> ResourceArns.meeting(principal.tenantId(), m.id()),
                Meeting::attributes);
    }

    /* ================================ rendering ================================= */

    private static ObjectNode meetingSummary(Meeting m) {
        ObjectNode node = F.objectNode();
        node.put("id", m.id().toString());
        node.put("title", m.title());
        putNullable(node, "startedAt", m.startedAt() == null ? null : m.startedAt().toString());
        putNullable(node, "endedAt", m.endedAt() == null ? null : m.endedAt().toString());
        if (m.durationSeconds() == null) {
            node.putNull("durationSeconds");
        } else {
            node.put("durationSeconds", m.durationSeconds());
        }
        putNullable(node, "language", m.language());
        node.put("status", m.processingStatus().name());
        putNullable(node, "summarySnippet", m.summarySnippet());
        node.set("tags", strings(m.tags()));
        node.set("participantNames", strings(participantNames(m)));
        return node;
    }

    private static ArrayNode participants(Meeting m) {
        ArrayNode array = F.arrayNode();
        if (m.participants() == null) {
            return array;
        }
        for (Participant p : m.participants()) {
            ObjectNode node = F.objectNode();
            putNullable(node, "displayName", p.displayName());
            putNullable(node, "email", p.email());
            node.put("isInternal", p.isInternal());
            array.add(node);
        }
        return array;
    }

    private static List<String> participantNames(Meeting m) {
        if (m.participants() == null) {
            return List.of();
        }
        return m.participants().stream()
                .map(Participant::displayName)
                .filter(n -> n != null && !n.isBlank())
                .limit(12)
                .toList();
    }

    private static ObjectNode analysis(MeetingAnalysis a) {
        ObjectNode node = F.objectNode();
        node.put("summary", a.summary());
        node.put("sentiment", a.sentimentOverall().name());
        node.set("topics", strings(a.topics()));

        ArrayNode decisions = F.arrayNode();
        for (Decision d : a.decisions()) {
            ObjectNode item = F.objectNode();
            item.put("text", d.text());
            item.put("confidence", d.confidence());
            decisions.add(item);
        }
        node.set("decisions", decisions);

        ArrayNode actionItems = F.arrayNode();
        for (ActionItem i : a.actionItems()) {
            ObjectNode item = F.objectNode();
            item.put("title", i.title());
            putNullable(item, "assignee", i.assignee());
            putNullable(item, "dueDate", i.dueDate() == null ? null : i.dueDate().toString());
            item.put("priority", i.priority().name());
            item.put("status", i.status().name());
            putNullable(item, "sourceQuote", i.sourceQuote());
            actionItems.add(item);
        }
        node.set("actionItems", actionItems);

        ArrayNode risks = F.arrayNode();
        for (Risk r : a.risks()) {
            ObjectNode item = F.objectNode();
            item.put("text", r.text());
            item.put("severity", r.severity().name());
            item.put("category", r.category().name());
            putNullable(item, "sourceQuote", r.sourceQuote());
            risks.add(item);
        }
        node.set("risks", risks);

        ArrayNode opportunities = F.arrayNode();
        for (Opportunity o : a.opportunities()) {
            ObjectNode item = F.objectNode();
            item.put("text", o.text());
            item.put("estimatedValue", o.estimatedValue().name());
            item.put("category", o.category().name());
            putNullable(item, "sourceQuote", o.sourceQuote());
            opportunities.add(item);
        }
        node.set("opportunities", opportunities);
        return node;
    }

    private static ObjectNode task(TaskRow r) {
        ObjectNode node = F.objectNode();
        node.put("id", r.id().toString());
        node.put("title", r.title());
        putNullable(node, "assignee", r.assignee());
        putNullable(node, "dueDate", r.dueDate() == null ? null : r.dueDate().toString());
        node.put("priority", r.priority().name());
        node.put("status", r.status().name());
        node.put("meetingId", r.meetingId().toString());
        putNullable(node, "meetingTitle", r.meetingTitle());
        return node;
    }

    private static ObjectNode confidence(CustomerConfidenceService.ConfidenceView view) {
        CustomerConfidenceAssessment a = view.assessment();
        ObjectNode node = F.objectNode();
        node.put("score", a.score());
        node.put("band", a.band().name());
        putNullable(node, "trend", a.trend() == null ? null : a.trend().name());
        putNullable(node, "accountName", view.accountName());
        putNullable(node, "rationale", a.rationale());

        ArrayNode signals = F.arrayNode();
        for (BuyingSignal s : a.buyingSignals()) {
            ObjectNode item = F.objectNode();
            item.put("type", s.type().name());
            item.put("quote", s.quote());
            item.put("weight", s.weight());
            signals.add(item);
        }
        node.set("buyingSignals", signals);

        ArrayNode objections = F.arrayNode();
        for (Objection o : a.objections()) {
            ObjectNode item = F.objectNode();
            item.put("type", o.type().name());
            item.put("quote", o.quote());
            item.put("severity", o.severity().name());
            putNullable(item, "competitor", o.competitor());
            objections.add(item);
        }
        node.set("objections", objections);
        return node;
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode array = F.arrayNode();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /* ================================ arguments ================================= */

    private static String stringArg(JsonNode args, String name) {
        JsonNode value = args.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int intArg(JsonNode args, String name, int fallback, int min, int max) {
        JsonNode value = args.get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber()) {
            throw new ToolArgumentException("'" + name + "' must be an integer.");
        }
        return Math.min(max, Math.max(min, value.asInt()));
    }

    private static UUID uuidArg(JsonNode args, String name) {
        String raw = stringArg(args, name);
        if (raw == null || raw.isBlank()) {
            throw new ToolArgumentException("'" + name + "' is required.");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ToolArgumentException("'" + name + "' is not a valid identifier.");
        }
    }

    private static ProcessingStatus parseStatus(JsonNode args) {
        String raw = blankToNull(stringArg(args, "status"));
        if (raw == null) {
            return null;
        }
        try {
            return ProcessingStatus.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ToolArgumentException(
                    "'status' must be PENDING, PROCESSING, COMPLETED or FAILED.");
        }
    }

    private static ActionItemStatus parseTaskStatus(JsonNode args) {
        String raw = blankToNull(stringArg(args, "status"));
        if (raw == null) {
            return null;
        }
        try {
            return ActionItemStatus.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ToolArgumentException("'status' must be OPEN, IN_PROGRESS or DONE.");
        }
    }
}
