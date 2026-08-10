package br.com.nora.api.infrastructure.integration;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts OAuth tokens at rest (AES-256-GCM, random IV per value). Stored format: {@code
 * enc:v1:base64(iv):base64(ciphertext)}. 32-byte key in base64 via {@code
 * NORA_INTEGRATIONS_ENC_KEY}; with NO key (local dev), stores {@code plain:...} with a WARN at boot
 * — honest and visible, never silent. Decrypt accepts both formats (smooth migration once the key
 * lands).
 */
@Component
public class TokenCipher {

    private static final Logger LOG = LoggerFactory.getLogger(TokenCipher.class);
    private static final String ENC_PREFIX = "enc:v1:";
    private static final String PLAIN_PREFIX = "plain:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(@Value("${nora.integrations.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            LOG.warn(
                    "NORA_INTEGRATIONS_ENC_KEY missing — OAuth tokens will be stored UNENCRYPTED"
                            + " (acceptable only in local dev; configure 32 base64 bytes in"
                            + " production)");
        } else {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 32) {
                throw new IllegalStateException(
                        "NORA_INTEGRATIONS_ENC_KEY must be 32 bytes (base64 of 32 bytes)");
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        if (key == null) {
            return PLAIN_PREFIX + plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ENC_PREFIX
                    + Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to encrypt token", ex);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (stored.startsWith(PLAIN_PREFIX)) {
            return stored.substring(PLAIN_PREFIX.length());
        }
        if (!stored.startsWith(ENC_PREFIX)) {
            // Legacy value with no prefix (should not exist) — returns it as is.
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException(
                    "token is encrypted in the database but NORA_INTEGRATIONS_ENC_KEY is missing");
        }
        try {
            String[] parts = stored.substring(ENC_PREFIX.length()).split(":", 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to decrypt token", ex);
        }
    }
}
