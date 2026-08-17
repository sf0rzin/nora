package br.com.nora.api.application.mcp;

/** Exceptions of the MCP subdomain (ADR 0041). Each code mapped to HTTP in the handler. */
public class McpException extends RuntimeException {

    private final String code;

    public McpException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * The token id does not resolve inside the caller's own (tenant, user) pair. Deliberately not
     * distinguished from "belongs to somebody else": a 404 either way, so an id cannot be probed
     * for existence.
     */
    public static McpException tokenNotFound() {
        return new McpException("MCP_TOKEN_NOT_FOUND", "MCP token not found.");
    }

    /** Per-user cap on live credentials. Revoke one before minting another. */
    public static McpException tooManyTokens(int max) {
        return new McpException(
                "MCP_TOKEN_LIMIT_REACHED",
                "This user already holds the maximum of " + max + " active MCP tokens.");
    }

    /** Empty, blank or over-long label. The name is how a human recognises what to revoke. */
    public static McpException invalidName() {
        return new McpException(
                "MCP_TOKEN_INVALID_NAME", "Token name must be between 1 and 80 characters.");
    }
}
