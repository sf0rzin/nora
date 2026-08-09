package br.com.nora.api.api.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of {@code POST /auth/refresh}.
 *
 * <p>The tokens go out in the httpOnly cookies {@code nora_access} / {@code nora_refresh} (web
 * clients) and, ONLY for native clients that authenticate the refresh via {@code Authorization:
 * Bearer} (Tauri desktop, OS keyring), in the body as well. When the refresh arrives via the cookie
 * (browser), the token fields come back null and are omitted from the JSON — that way an XSS cannot
 * read the 30-day refresh (ADR 0020). The web reads only {@code expiresInSeconds} from this
 * response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefreshResponse(
        String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}
