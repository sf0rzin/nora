package br.com.nora.api.api.controllers;

import br.com.nora.api.api.mcp.McpProtocol;
import br.com.nora.api.api.mcp.McpToolCatalog;
import br.com.nora.api.api.mcp.McpToolInvoker;
import br.com.nora.api.api.security.AuthorizationNotRequired;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.iam.IamException;
import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * NORA's MCP endpoint — the inbound adapter of ADR 0041 (US27).
 *
 * <p>One path, one method: the Streamable HTTP transport wants a single MCP endpoint answering POST
 * with a JSON-RPC 2.0 message. GET and DELETE are not mapped, so Spring answers 405 to both, which
 * is the transport's own way of saying "this server offers no server-initiated SSE stream and no
 * client-terminated session". That is a deliberate simplification and it is complete rather than
 * partial: every tool here is a request/response read, so there is nothing to stream and no session
 * state worth keeping. No {@code MCP-Session-Id} is issued for the same reason — the credential
 * travels on every request, so the server has nothing to remember between them.
 *
 * <p><b>Authentication does not happen here.</b> The MCP bearer token is exchanged for the same
 * authenticated principal the JWT filter produces, one filter earlier, on a security chain that
 * matches this path and only this path (see {@code McpSecurityConfig}). By the time a request
 * reaches this method, {@code anyRequest().authenticated()} has already refused everything without
 * a live credential — the failure mode is a 401 from the chain, never a handler that forgot to
 * check.
 *
 * <p><b>Protocol version: pinned, and loud when it does not match.</b> The revisions this server
 * implements are in {@link McpProtocol}. A handshake naming anything else is refused with the
 * specification's own error shape, carrying the list of what is supported; the alternative —
 * echoing the client's version back and hoping the shapes line up — is the silent degradation this
 * refusal exists to prevent. The transport's {@code MCP-Protocol-Version} header is validated the
 * way the transport requires: an unsupported value is a 400, which is also the signal a dual-era
 * client uses to fall back to the handshake it can speak.
 *
 * <p><b>Errors follow the protocol's two-channel split.</b> A malformed message, an unknown method
 * or an unknown tool is a JSON-RPC error, because a model cannot fix it by retrying differently. A
 * bad argument, an unreadable meeting or a policy denial comes back as a tool result with {@code
 * isError} set, which is the channel the specification reserves for failures a model can act on.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger LOG = LoggerFactory.getLogger(McpController.class);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private static final String JSONRPC_VERSION = "2.0";
    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_PING = "ping";
    private static final String METHOD_TOOLS_LIST = "tools/list";
    private static final String METHOD_TOOLS_CALL = "tools/call";

    private final McpToolInvoker tools;
    private final ObjectMapper objectMapper;

    public McpController(McpToolInvoker tools, ObjectMapper objectMapper) {
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizationNotRequired(
            reason = "Body: each tool authorizes the action of the resource it reads.")
    public ResponseEntity<JsonNode> rpc(@RequestBody JsonNode body, HttpServletRequest request) {
        String protocolHeader = request.getHeader(McpProtocol.PROTOCOL_VERSION_HEADER);
        // Absent header is tolerated: the transport only requires it AFTER a handshake, and this
        // server holds no session to contradict it with. A value it does not implement is a 400,
        // which the transport mandates and which a dual-era client reads as "fall back".
        if (protocolHeader != null && !McpProtocol.supports(protocolHeader.trim())) {
            ObjectNode refusal =
                    errorEnvelope(
                            F.nullNode(),
                            McpProtocol.INVALID_REQUEST,
                            "Unsupported MCP protocol version",
                            versionData(protocolHeader.trim()));
            return ResponseEntity.badRequest().body(refusal);
        }
        if (body == null || !body.isObject() || !JSONRPC_VERSION.equals(text(body, "jsonrpc"))) {
            ObjectNode refusal =
                    errorEnvelope(
                            F.nullNode(),
                            McpProtocol.INVALID_REQUEST,
                            "Body is not a JSON-RPC 2.0 message",
                            null);
            return ResponseEntity.badRequest().body(refusal);
        }

        String method = text(body, "method");
        JsonNode id = body.get("id");
        // No id means a notification. The transport asks for 202 with no body, and this server has
        // nothing to do with any of them: `notifications/initialized` is the client saying it is
        // ready, and there is no session here for it to make ready.
        if (id == null || id.isNull()) {
            return ResponseEntity.accepted().build();
        }
        if (method == null || method.isBlank()) {
            return ok(errorEnvelope(id, McpProtocol.INVALID_REQUEST, "Missing 'method'", null));
        }

        JsonNode params = body.get("params");
        JsonNode envelope =
                switch (method) {
                    case METHOD_INITIALIZE -> initialize(id, params);
                    case METHOD_PING -> resultEnvelope(id, F.objectNode());
                    case METHOD_TOOLS_LIST -> resultEnvelope(id, toolsList());
                    case METHOD_TOOLS_CALL -> toolsCall(id, params);
                    default -> methodNotFound(id, method);
                };
        return ok(envelope);
    }

    private static ObjectNode methodNotFound(JsonNode id, String method) {
        String message = "Method not found: " + method;
        return errorEnvelope(id, McpProtocol.METHOD_NOT_FOUND, message, null);
    }

    /* ================================= handshake ================================= */

    private JsonNode initialize(JsonNode id, JsonNode params) {
        String requested = params == null ? null : text(params, "protocolVersion");
        if (!McpProtocol.supports(requested)) {
            return errorEnvelope(
                    id,
                    McpProtocol.INVALID_PARAMS,
                    "Unsupported protocol version",
                    versionData(requested));
        }

        ObjectNode capabilities = F.objectNode();
        // Tools only, and `listChanged` is absent rather than false: the catalogue is a constant,
        // so there is no notification this server could ever send about it.
        capabilities.set("tools", F.objectNode());

        ObjectNode serverInfo = F.objectNode();
        serverInfo.put("name", McpProtocol.SERVER_NAME);
        serverInfo.put("title", McpProtocol.SERVER_TITLE);
        serverInfo.put("version", McpProtocol.SERVER_VERSION);

        ObjectNode result = F.objectNode();
        result.put("protocolVersion", requested);
        result.set("capabilities", capabilities);
        result.set("serverInfo", serverInfo);
        return resultEnvelope(id, result);
    }

    private static ObjectNode versionData(String requested) {
        ArrayNode supported = F.arrayNode();
        McpProtocol.SUPPORTED_VERSIONS.forEach(supported::add);
        ObjectNode data = F.objectNode();
        data.set("supported", supported);
        if (requested == null) {
            data.putNull("requested");
        } else {
            data.put("requested", requested);
        }
        return data;
    }

    /* =================================== tools =================================== */

    private static ObjectNode toolsList() {
        ObjectNode result = F.objectNode();
        result.set("tools", McpToolCatalog.tools());
        return result;
    }

    private JsonNode toolsCall(JsonNode id, JsonNode params) {
        String name = params == null ? null : text(params, "name");
        if (name == null || name.isBlank()) {
            return errorEnvelope(id, McpProtocol.INVALID_PARAMS, "Missing tool 'name'", null);
        }
        AuthenticatedPrincipal principal = CurrentUser.require();
        JsonNode arguments = params.get("arguments");
        LOG.debug("MCP tool call: {}", name);
        try {
            ObjectNode payload = tools.invoke(principal, name, arguments);
            return resultEnvelope(id, toolResult(payload));
        } catch (McpToolInvoker.UnknownToolException ex) {
            return errorEnvelope(id, McpProtocol.INVALID_PARAMS, ex.getMessage(), null);
        } catch (McpToolInvoker.ToolArgumentException ex) {
            return resultEnvelope(id, toolFailure(ex.getMessage()));
        } catch (IamException ex) {
            // A policy denial is something the caller can act on (ask for access, try another
            // resource), so it belongs in the tool-result channel, not in the protocol one. The
            // message says no more than the REST 403 does.
            return resultEnvelope(id, toolFailure("Not allowed: this principal lacks permission."));
        } catch (MeetingException ex) {
            return resultEnvelope(id, toolFailure("Meeting not found in this workspace."));
        }
    }

    /**
     * Structured payload plus the serialized JSON as a text block. Both, because the protocol asks
     * a tool returning {@code structuredContent} to repeat it in a text content item for clients
     * that read only the unstructured channel.
     */
    private ObjectNode toolResult(ObjectNode payload) {
        ObjectNode result = F.objectNode();
        result.set("content", textContent(serialize(payload)));
        result.set("structuredContent", payload);
        result.put("isError", false);
        return result;
    }

    private static ObjectNode toolFailure(String message) {
        ObjectNode result = F.objectNode();
        result.set("content", textContent(message));
        result.put("isError", true);
        return result;
    }

    private static ArrayNode textContent(String text) {
        ObjectNode block = F.objectNode();
        block.put("type", "text");
        block.put("text", text);
        ArrayNode content = F.arrayNode();
        content.add(block);
        return content;
    }

    private String serialize(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // A node tree built in this process does not fail to serialize; if it ever did, the
            // structured half of the result is still intact and correct.
            return "{}";
        }
    }

    /* ================================= envelopes ================================= */

    private static ResponseEntity<JsonNode> ok(JsonNode envelope) {
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(envelope);
    }

    private static ObjectNode resultEnvelope(JsonNode id, ObjectNode result) {
        ObjectNode envelope = F.objectNode();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        envelope.set("id", id);
        envelope.set("result", result);
        return envelope;
    }

    private static ObjectNode errorEnvelope(
            JsonNode id, int code, String message, ObjectNode data) {
        ObjectNode error = F.objectNode();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", data);
        }
        ObjectNode envelope = F.objectNode();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        envelope.set("id", id);
        envelope.set("error", error);
        return envelope;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }
}
