package br.com.nora.api.api.security;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Centralized construction of the httpOnly authentication cookies (Round 2 / Subphase 1.3 A).
 *
 * <p>Two cookies:
 *
 * <ul>
 *   <li>{@value #ACCESS_COOKIE} — short JWT (15min default). {@code SameSite=Lax}, {@code Path=/}.
 *       Goes on every request but is short-lived.
 *   <li>{@value #REFRESH_COOKIE} — opaque (30 days default). {@code SameSite=Strict}, {@code
 *       Path=/auth}. Only reaches the refresh/logout endpoint, reduces exposure in logs/network.
 * </ul>
 *
 * <p>The {@code Secure} flag is controlled by {@code nora.auth.cookie-secure} (default false in
 * dev, true in prod). Always {@code HttpOnly} — stops browser JavaScript from reading it.
 */
public final class AuthCookies {

    public static final String ACCESS_COOKIE = "nora_access";
    public static final String REFRESH_COOKIE = "nora_refresh";

    private static final String ROOT_PATH = "/";
    private static final String AUTH_PATH = "/auth";
    private static final String SAMESITE_LAX = "Lax";
    private static final String SAMESITE_STRICT = "Strict";

    private final boolean secure;
    private final String domain;

    public AuthCookies(boolean secure) {
        this(secure, null);
    }

    /**
     * @param domain when provided (e.g.: {@code salmonbeach-X.centralus.azurecontainerapps.io}),
     *     emits the cookies with {@code Domain=}, making the browser share them across the
     *     subdomains (web and api). Needed on Container Apps when web and api are on sibling hosts
     *     of the same registrable domain — without it, the cookie is scoped only to the api and the
     *     Next middleware on the web domain does not see it, redirecting to login forever.
     */
    public AuthCookies(boolean secure, String domain) {
        this.secure = secure;
        this.domain = (domain == null || domain.isBlank()) ? null : domain;
    }

    private ResponseCookie.ResponseCookieBuilder applyDomain(
            ResponseCookie.ResponseCookieBuilder b) {
        return domain == null ? b : b.domain(domain);
    }

    /** Access JWT cookie. Root path so it is sent on any authenticated call. */
    public ResponseCookie buildAccessCookie(String value, Duration ttl) {
        return applyDomain(
                        ResponseCookie.from(ACCESS_COOKIE, value)
                                .httpOnly(true)
                                .secure(secure)
                                .sameSite(SAMESITE_LAX)
                                .path(ROOT_PATH)
                                .maxAge(ttl))
                .build();
    }

    /**
     * Opaque refresh cookie. Path restricted to {@code /auth} so it does not leak on general
     * requests — it only goes to refresh/logout/etc routes.
     */
    public ResponseCookie buildRefreshCookie(String value, Duration ttl) {
        return applyDomain(
                        ResponseCookie.from(REFRESH_COOKIE, value)
                                .httpOnly(true)
                                .secure(secure)
                                .sameSite(SAMESITE_STRICT)
                                .path(AUTH_PATH)
                                .maxAge(ttl))
                .build();
    }

    /** Access clearing cookie (Max-Age=0). */
    public ResponseCookie buildClearAccessCookie() {
        return applyDomain(
                        ResponseCookie.from(ACCESS_COOKIE, "")
                                .httpOnly(true)
                                .secure(secure)
                                .sameSite(SAMESITE_LAX)
                                .path(ROOT_PATH)
                                .maxAge(0))
                .build();
    }

    /** Refresh clearing cookie (Max-Age=0, same Path as issuance so the delete matches). */
    public ResponseCookie buildClearRefreshCookie() {
        return applyDomain(
                        ResponseCookie.from(REFRESH_COOKIE, "")
                                .httpOnly(true)
                                .secure(secure)
                                .sameSite(SAMESITE_STRICT)
                                .path(AUTH_PATH)
                                .maxAge(0))
                .build();
    }

    /** Test/observability helper: indicates whether {@code Secure} is on. */
    public boolean isSecure() {
        return secure;
    }

    /** Shortcut to append a cookie to {@code HttpHeaders} as Set-Cookie. */
    public static void appendSetCookie(HttpHeaders headers, ResponseCookie cookie) {
        headers.add(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
