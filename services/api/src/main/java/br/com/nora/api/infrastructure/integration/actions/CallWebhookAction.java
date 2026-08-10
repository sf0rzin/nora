package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.infrastructure.integration.WebhookHttpClient;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * NORA Flows "Call webhook" action — generic n8n-style JSON POST to a user URL, with no
 * credential at all. Required param: {@code url} (HTTPS only; validated on save AND at run time).
 *
 * <p><strong>STABLE payload contract</strong> (external consumers depend on it — additive changes
 * only):
 *
 * <pre>{@code
 * {
 *   "event": "meeting.analysis_completed",
 *   "sampleData": false,
 *   "meeting": { "id": "uuid", "title": "...", "url": "https://...", "tags": ["..."], "summary": "..." },
 *   "stats": { "decisions": 2, "actionItems": 3, "risks": 1 },
 *   "scores": { "productivity": 70, "customerConfidence": 65 },
 *   "actionItems": [ { "title": "...", "assignee": "...", "dueDate": "2026-06-20" } ]
 * }
 * }</pre>
 *
 * Null fields are OMITTED (e.g. {@code meeting.id} on a test run with sample data, {@code
 * assignee}/{@code dueDate} with no value, the whole {@code scores} object when no score exists).
 * {@code actionItems} is always present (empty list when there are no items). Headers: {@code
 * X-Nora-Event: <event type>} and {@code User-Agent: NORA-Flows}.
 *
 * <p><strong>SSRF guard</strong>: rejects {@code http://} and resolves the hostname, rejecting
 * private/loopback/link-local/metadata IPs (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, ::1,
 * fc00::/7). A failure (non-2xx status, 10s timeout or transport) PROPAGATES — {@link
 * ActionExecutor} contract, the engine writes FAILED in the log.
 */
@Component
public class CallWebhookAction implements ActionExecutor {

    private final WebhookHttpClient http;

    public CallWebhookAction(WebhookHttpClient http) {
        this.http = http;
    }

    @Override
    public String type() {
        return "call_webhook";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String url = requiredUrl(params);
        validateUrl(url);
        int status =
                http.postJson(
                        "webhook",
                        url,
                        Map.of("X-Nora-Event", ctx.eventType(), "User-Agent", "NORA-Flows"),
                        buildPayload(ctx));
        return "Webhook chamado: " + status;
    }

    static String requiredUrl(Map<String, Object> params) {
        String url = WorkflowActionTemplates.stringParam(params, "url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "URL required in params.url (e.g.: https://hooks.example.com/nora)");
        }
        return url.trim();
    }

    /**
     * SSRF guard: HTTPS only and only hosts that resolve to public addresses. Called at run time
     * (last gate) — the save validates the format only.
     */
    static void validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "only https:// URLs are accepted — http:// and other schemes are blocked");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL with no valid host: " + url);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("could not resolve host \"" + host + "\"");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException(
                        "host \""
                                + host
                                + "\" resolves to a private/internal address ("
                                + address.getHostAddress()
                                + ") — webhooks may only target public services");
            }
        }
    }

    /**
     * Blocked addresses: loopback (127/8, ::1), private (10/8, 172.16/12, 192.168/16),
     * link-local/metadata (169.254/16, fe80::/10), wildcard, multicast and IPv6 unique-local
     * (fc00::/7).
     */
    static boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 unique-local fc00::/7 — not covered by isSiteLocalAddress (which is fec0::/10).
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** Builds the payload of the contract documented on the class. Null fields are omitted. */
    static Map<String, Object> buildPayload(WorkflowEventContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", ctx.eventType());
        payload.put("sampleData", ctx.sampleData());

        Map<String, Object> meeting = new LinkedHashMap<>();
        putIfPresent(meeting, "id", ctx.meetingId() == null ? null : ctx.meetingId().toString());
        putIfPresent(meeting, "title", ctx.meetingTitle());
        putIfPresent(meeting, "url", ctx.meetingUrl());
        if (ctx.tags() != null) {
            meeting.put("tags", ctx.tags());
        }
        putIfPresent(meeting, "summary", ctx.summary());
        payload.put("meeting", meeting);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("decisions", ctx.decisionsCount());
        stats.put("actionItems", ctx.actionItemsCount());
        stats.put("risks", ctx.risksCount());
        payload.put("stats", stats);

        Map<String, Object> scores = new LinkedHashMap<>();
        putIfPresent(scores, "productivity", ctx.productivityScore());
        putIfPresent(scores, "customerConfidence", ctx.customerConfidenceScore());
        if (!scores.isEmpty()) {
            payload.put("scores", scores);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (WorkflowEventContext.ActionItemView item : ctx.actionItems()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfPresent(entry, "title", item.title());
            putIfPresent(entry, "assignee", item.assignee());
            putIfPresent(
                    entry, "dueDate", item.dueDate() == null ? null : item.dueDate().toString());
            items.add(entry);
        }
        payload.put("actionItems", items);
        return payload;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
