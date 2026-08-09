package br.com.nora.api.infrastructure.security;

import br.com.nora.api.application.ports.SecureTokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Generates random URL-safe 32-byte (256-bit) tokens and stores their hexadecimal SHA-256.
 *
 * <p>SHA-256 is enough because the raw token already has maximum entropy (256 bits). We do not need
 * a salt: the goal is to make the token unrecoverable from the hash, not to defend against
 * dictionary attacks (impossible with 256 random bits).
 */
@Component
public class SecureRandomTokenGenerator implements SecureTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom random = new SecureRandom();

    @Override
    public GeneratedToken generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedToken(raw, hash(raw));
    }

    @Override
    public String hash(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
