package br.com.nora.api.api.security;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Construcao centralizada dos cookies httpOnly de autenticacao (Round 2 / Subfase 1.3 A).
 *
 * <p>Dois cookies:
 *
 * <ul>
 *   <li>{@value #ACCESS_COOKIE} — JWT curto (15min default). {@code SameSite=Lax}, {@code Path=/}.
 *       Vai em toda request mas e curto-vivido.
 *   <li>{@value #REFRESH_COOKIE} — opaque (30 dias default). {@code SameSite=Strict}, {@code
 *       Path=/auth}. So chega no endpoint de refresh/logout, reduz exposicao em logs/network.
 * </ul>
 *
 * <p>Flag {@code Secure} controlada por {@code nora.auth.cookie-secure} (default false em dev, true
 * em prod). Sempre {@code HttpOnly} — impede JavaScript do navegador de ler.
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
     * @param domain quando informado (ex.: {@code salmonbeach-X.centralus.azurecontainerapps.io}),
     *     emite os cookies com {@code Domain=}, fazendo o navegador compartilhá-los entre os
     *     subdomínios (web e api). Necessário no Container Apps quando web e api estão em hosts
     *     irmãos do mesmo registrable domain — sem isso, o cookie fica scoped só na api e o
     *     middleware do Next no domínio do web não enxerga, redirecionando pra login infinito.
     */
    public AuthCookies(boolean secure, String domain) {
        this.secure = secure;
        this.domain = (domain == null || domain.isBlank()) ? null : domain;
    }

    private ResponseCookie.ResponseCookieBuilder applyDomain(
            ResponseCookie.ResponseCookieBuilder b) {
        return domain == null ? b : b.domain(domain);
    }

    /** Cookie do access JWT. Path raiz para ser enviado em qualquer chamada autenticada. */
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
     * Cookie do refresh opaque. Path restrito a {@code /auth} para nao vazar em requests gerais —
     * so vai pra rotas de refresh/logout/etc.
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

    /** Cookie de limpeza do access (Max-Age=0). */
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

    /** Cookie de limpeza do refresh (Max-Age=0, mesmo Path da emissao para casar o delete). */
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

    /** Helper de teste/observabilidade: e indica se {@code Secure} esta ligado. */
    public boolean isSecure() {
        return secure;
    }

    /** Atalho para appendar um cookie em {@code HttpHeaders} como Set-Cookie. */
    public static void appendSetCookie(HttpHeaders headers, ResponseCookie cookie) {
        headers.add(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
