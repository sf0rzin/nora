package br.com.nora.api.application.mcp;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.McpTokenRepository;
import br.com.nora.api.application.ports.SecureTokenGenerator;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.identity.McpToken;
import br.com.nora.api.domain.identity.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Lifecycle of the MCP bearer credentials of ADR 0041 §3: mint, list, revoke, and the edge exchange
 * that turns a presented token back into the (tenant, user) pair the rest of the request runs as.
 *
 * <p>Only the SHA-256 hash is ever persisted, following {@code refresh_tokens} (V011) and the
 * invitation token since V018. The raw value is returned exactly once, by {@link #mint}, and there
 * is no path in this class or anywhere else that can produce it again — a lost token is replaced,
 * never recovered.
 *
 * <p><b>The prefix is load-bearing, not decoration.</b> Every minted token starts with {@value
 * #TOKEN_PREFIX}. It lets the edge tell an MCP credential from a session JWT without parsing
 * either, it makes the value recognisable in a secret scanner, and it means a token pasted into the
 * wrong field is identifiable at a glance. The hash covers the WHOLE presented string, prefix
 * included, so nothing can be replayed with the prefix stripped or swapped.
 */
@Service
public class McpTokenService {

    /** Recognisable, greppable prefix. Part of the credential, not a decoration around it. */
    public static final String TOKEN_PREFIX = "nora_mcp_";

    /** Per-user cap on live credentials, so a runaway client cannot mint without bound. */
    public static final int MAX_ACTIVE_TOKENS = 20;

    private static final int MAX_NAME_LENGTH = 80;

    private final McpTokenRepository tokens;
    private final UserRepository users;
    private final SecureTokenGenerator generator;
    private final Clock clock;

    public McpTokenService(
            McpTokenRepository tokens,
            UserRepository users,
            SecureTokenGenerator generator,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.generator = generator;
        this.clock = clock;
    }

    /**
     * The credential resolved from a presented token. Carries what the edge needs to build the same
     * authenticated principal the JWT filter produces, and nothing else — no roles, no claims.
     */
    public record ResolvedToken(UUID tenantId, UUID userId, String email) {}

    /** A freshly minted token together with its plaintext. The plaintext exists only here. */
    public record MintedToken(McpToken token, String plaintext) {}

    /**
     * Mints a credential for {@code userId} inside {@code tenantId}.
     *
     * @param ttl optional hard expiry; {@code null} means the token lives until it is revoked
     */
    public MintedToken mint(UUID tenantId, UUID userId, String rawName, Duration ttl) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw McpException.invalidName();
        }
        Instant now = clock.now();
        long active =
                tokens.findByOwner(tenantId, userId).stream().filter(t -> t.isActive(now)).count();
        if (active >= MAX_ACTIVE_TOKENS) {
            throw McpException.tooManyTokens(MAX_ACTIVE_TOKENS);
        }

        String plaintext = TOKEN_PREFIX + generator.generate().rawToken();
        Instant expiresAt = ttl == null ? null : now.plus(ttl);
        McpToken minted =
                McpToken.issue(
                        UUID.randomUUID(),
                        tenantId,
                        userId,
                        name,
                        generator.hash(plaintext),
                        now,
                        expiresAt);
        return new MintedToken(tokens.save(minted), plaintext);
    }

    /** The caller's own tokens, newest first. Revoked ones stay in the list, marked as such. */
    public List<McpToken> list(UUID tenantId, UUID userId) {
        return tokens.findByOwner(tenantId, userId);
    }

    /**
     * Revokes one of the caller's own tokens. Idempotent: revoking an already revoked token is a
     * no-op rather than an error, so a retried request cannot fail for having succeeded.
     */
    public void revoke(UUID tokenId, UUID tenantId, UUID userId) {
        McpToken token =
                tokens.findByIdAndOwner(tokenId, tenantId, userId)
                        .orElseThrow(McpException::tokenNotFound);
        if (token.isRevoked()) {
            return;
        }
        token.revoke(clock.now());
        tokens.save(token);
    }

    /**
     * The edge exchange. Turns a presented bearer value into the principal the request will run as,
     * or {@link Optional#empty()} when it is not a live credential.
     *
     * <p>Every refusal is silent and indistinguishable from the others — unknown hash, revoked,
     * expired, deleted user, user who can no longer log in. The caller answers 401 without saying
     * which, because the difference between "this token never existed" and "this token was revoked
     * yesterday" is information the holder of a stolen token should not get.
     *
     * <p>The user is re-read on every call rather than trusted from the token row. That is what
     * makes disabling an account, or deleting it, take effect on the MCP surface immediately
     * instead of at the next revocation.
     */
    public Optional<ResolvedToken> authenticate(String presented) {
        if (presented == null || !presented.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Instant now = clock.now();
        Optional<McpToken> found = tokens.findByTokenHash(generator.hash(presented));
        if (found.isEmpty() || !found.get().isActive(now)) {
            return Optional.empty();
        }
        McpToken token = found.get();
        Optional<User> owner = users.findById(token.userId());
        if (owner.isEmpty()
                || !owner.get().canLogin()
                || !owner.get().tenantId().equals(token.tenantId())) {
            return Optional.empty();
        }
        String email = owner.get().email().value();
        token.markUsed(now);
        tokens.save(token);
        return Optional.of(new ResolvedToken(token.tenantId(), token.userId(), email));
    }
}
