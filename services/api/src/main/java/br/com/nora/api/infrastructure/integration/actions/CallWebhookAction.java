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
 * Ação "Chamar webhook" do NORA Flows — POST JSON genérico estilo n8n para uma URL do usuário, sem
 * credencial nenhuma. Param obrigatório: {@code url} (HTTPS apenas; validado no save E na
 * execução).
 *
 * <p><strong>Contrato ESTÁVEL do payload</strong> (consumidores externos dependem dele — mudanças
 * só aditivas):
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
 * Campos null são OMITIDOS (ex.: {@code meeting.id} em execução de teste com dados de exemplo,
 * {@code assignee}/{@code dueDate} sem valor, o objeto {@code scores} inteiro quando nenhum score
 * existe). {@code actionItems} é sempre presente (lista vazia quando não há itens). Headers: {@code
 * X-Nora-Event: <tipo do evento>} e {@code User-Agent: NORA-Flows}.
 *
 * <p><strong>Guarda SSRF</strong>: rejeita {@code http://} e resolve o hostname rejeitando IPs
 * privados/loopback/link-local/metadata (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, ::1,
 * fc00::/7). Falha (status não-2xx, timeout de 10s ou transporte) PROPAGA — contrato do {@link
 * ActionExecutor}, o engine grava FAILED no log.
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
                    "URL obrigatória em params.url (ex.: https://hooks.exemplo.com/nora)");
        }
        return url.trim();
    }

    /**
     * Guarda SSRF: só HTTPS e só hosts que resolvem para endereços públicos. Chamada na execução
     * (último gate) — o save valida apenas o formato.
     */
    static void validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception ex) {
            throw new IllegalArgumentException("URL inválida: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "apenas URLs https:// são aceitas — http:// e outros esquemas são bloqueados");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL sem host válido: " + url);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("não foi possível resolver o host \"" + host + "\"");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException(
                        "o host \""
                                + host
                                + "\" resolve para um endereço privado/interno ("
                                + address.getHostAddress()
                                + ") — webhooks só podem apontar para serviços públicos");
            }
        }
    }

    /**
     * Endereços proibidos: loopback (127/8, ::1), privados (10/8, 172.16/12, 192.168/16),
     * link-local/metadata (169.254/16, fe80::/10), wildcard, multicast e IPv6 unique-local
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
        // IPv6 unique-local fc00::/7 — não coberto por isSiteLocalAddress (que é fec0::/10).
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** Monta o payload do contrato documentado na classe. Campos null são omitidos. */
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
