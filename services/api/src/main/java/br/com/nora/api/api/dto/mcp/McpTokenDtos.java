package br.com.nora.api.api.dto.mcp;

import br.com.nora.api.domain.identity.McpToken;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the MCP token endpoints (US27, ADR 0041 §3). */
public final class McpTokenDtos {

    private McpTokenDtos() {}

    /**
     * @param name label the user will recognise in the list when deciding what to revoke
     * @param expiresInDays optional hard expiry; absent or null means "until revoked"
     */
    public record CreateRequest(String name, Integer expiresInDays) {}

    /**
     * The only response that ever carries {@code token}. The plaintext is not stored, so this is
     * the one and only moment it can be read; the UI shows it once and the API cannot repeat it.
     */
    public record CreatedResponse(
            UUID id, String name, String token, Instant createdAt, Instant expiresAt) {

        public static CreatedResponse from(McpToken token, String plaintext) {
            return new CreatedResponse(
                    token.id(), token.name(), plaintext, token.createdAt(), token.expiresAt());
        }
    }

    /** A token as it appears in the listing. Carries no secret material of any kind. */
    public record TokenSummary(
            UUID id,
            String name,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            Instant lastUsedAt,
            boolean active) {

        public static TokenSummary from(McpToken token, Instant now) {
            return new TokenSummary(
                    token.id(),
                    token.name(),
                    token.createdAt(),
                    token.expiresAt(),
                    token.revokedAt(),
                    token.lastUsedAt(),
                    token.isActive(now));
        }
    }

    public record TokenListResponse(List<TokenSummary> items) {}
}
