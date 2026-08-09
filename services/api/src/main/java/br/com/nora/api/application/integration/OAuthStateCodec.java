package br.com.nora.api.application.integration;

import br.com.nora.api.domain.integration.IntegrationProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encodes/validates the OAuth {@code state} parameter as a self-contained signed token
 * (HMAC-SHA256): {@code base64url(tenantId:userId:provider:expEpoch:nonce):base64url(hmac)}. The
 * provider callback arrives by browser redirect — the signed state identifies the tenant/user
 * without relying on a cookie and blocks CSRF/forging (signature) and late replay (10 min exp).
 *
 * <p>Secret via {@code NORA_INTEGRATIONS_STATE_SECRET}; without the env var (dev), it generates one
 * per boot — states do not survive a restart, which is acceptable in dev and impossible to forget
 * in prod (the flow breaks visibly, not silently).
 */
@Component
public class OAuthStateCodec {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthStateCodec.class);
    private static final long TTL_SECONDS = 600;

    private final byte[] secret;

    public OAuthStateCodec(@Value("${nora.integrations.state-secret:}") String configured) {
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            LOG.info(
                    "NORA_INTEGRATIONS_STATE_SECRET ausente — usando segredo efêmero por boot"
                            + " (ok em dev; configure em produção)");
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String encode(UUID tenantId, UUID userId, IntegrationProvider provider, Instant now) {
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String payload =
                tenantId
                        + ":"
                        + userId
                        + ":"
                        + provider.wire()
                        + ":"
                        + now.plusSeconds(TTL_SECONDS).getEpochSecond()
                        + ":"
                        + nonce;
        String body = b64(payload.getBytes(StandardCharsets.UTF_8));
        return body + ":" + b64(hmac(body));
    }

    /** Validates signature + expiry and returns the context. Failure = {@code InvalidState}. */
    public DecodedState decode(String state, Instant now) {
        if (state == null || state.isBlank()) {
            throw new IntegrationException.InvalidState();
        }
        int sep = state.lastIndexOf(':');
        if (sep <= 0) {
            throw new IntegrationException.InvalidState();
        }
        String body = state.substring(0, sep);
        String signature = state.substring(sep + 1);
        byte[] expected = hmac(body);
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException ex) {
            throw new IntegrationException.InvalidState();
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new IntegrationException.InvalidState();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IntegrationException.InvalidState();
        }
        String[] parts = payload.split(":");
        if (parts.length != 5) {
            throw new IntegrationException.InvalidState();
        }
        try {
            UUID tenantId = UUID.fromString(parts[0]);
            UUID userId = UUID.fromString(parts[1]);
            IntegrationProvider provider = IntegrationProvider.fromWire(parts[2]);
            long exp = Long.parseLong(parts[3]);
            if (now.getEpochSecond() > exp) {
                throw new IntegrationException.InvalidState();
            }
            return new DecodedState(tenantId, userId, provider);
        } catch (IllegalArgumentException ex) {
            throw new IntegrationException.InvalidState();
        }
    }

    public record DecodedState(UUID tenantId, UUID userId, IntegrationProvider provider) {}

    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC indisponível", ex);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
