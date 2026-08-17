package br.com.nora.api.api.mcp;

import java.util.List;

/**
 * Wire constants of the Model Context Protocol surface (ADR 0041).
 *
 * <p><b>Which revisions this server speaks, and why exactly these.</b> MCP versions are dated
 * strings and the protocol has two eras. The <i>legacy</i> era ({@code 2025-11-25} and earlier)
 * opens a session with an {@code initialize} handshake that negotiates one version for the whole
 * connection. The <i>modern</i> era ({@code 2026-07-28}, the current revision) drops the handshake:
 * every request carries its own version in {@code _meta} and the server answers each one
 * independently.
 *
 * <p>This server implements the legacy era, and that is a decision rather than an oversight. The
 * point of ADR 0041 is that a reviewer can attach NORA to the MCP client they already run, and the
 * deployed client base speaks the handshake. A modern-only server, by the specification's own
 * compatibility matrix, simply fails against a legacy client — there is no fall-forward for them.
 * The reverse direction is covered: a modern or dual-era client that opens with a version this
 * server does not implement gets a loud, machine-readable refusal naming what it does implement,
 * which is exactly the signal such a client uses to fall back.
 *
 * <p>{@link #SUPPORTED_VERSIONS} carries two revisions because the surface this server exposes —
 * {@code initialize}, {@code tools/list}, {@code tools/call}, text plus structured content — has
 * the same shape in both, so supporting both is an accurate statement rather than a shim. Anything
 * outside the list is refused; degrading silently into "whatever the client asked for" is the
 * failure this list exists to prevent.
 */
public final class McpProtocol {

    private McpProtocol() {}

    /** Answered on a handshake that does not name a version this server implements. */
    public static final String PREFERRED_VERSION = "2025-11-25";

    /** Every revision this server actually implements. Order is newest first. */
    public static final List<String> SUPPORTED_VERSIONS = List.of("2025-11-25", "2025-06-18");

    /** Header the Streamable HTTP transport carries on every request after the handshake. */
    public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    public static final String SERVER_NAME = "nora";
    public static final String SERVER_TITLE = "NORA";
    public static final String SERVER_VERSION = "0.1.0";

    /* ---- JSON-RPC 2.0 reserved error codes (§5.1 of the JSON-RPC specification) ---- */

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static boolean supports(String version) {
        return version != null && SUPPORTED_VERSIONS.contains(version);
    }
}
