package br.com.nora.api.infrastructure.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rate limiter para endpoints públicos de autenticação. Defesa contra credential stuffing / brute
 * force / spam de e-mails de reset.
 *
 * <p>Buckets em memória por chave (`IP` para login, `email` para reset). Por instância — em
 * scale-out cap real = N×instances. Bom o suficiente pra MVP; migrar pra Redis quando rodar com
 * mais de 1 réplica permanente.
 *
 * <p><strong>Eviction:</strong> os caches usam Caffeine com {@code maximumSize} (limita memória sob
 * ataque distribuído com 10k+ IPs únicos) e {@code expireAfterAccess} ~3× a janela (garante que
 * buckets ociosos sejam coletados sem recriar o estado de quem ainda está dentro da janela).
 *
 * <p>Limites baseados em postura padrão OWASP, configuráveis via properties para que testes
 * integração (que rodam dezenas de logins do mesmo loopback no mesmo JVM) possam relaxar sem afetar
 * produção.
 *
 * <ul>
 *   <li>Login: 10 / minuto por IP (humanidade real raramente erra 10x/min)
 *   <li>Reset request: 3 / 10 minutos por email (evita spam de reset)
 *   <li>Signup: 5 / minuto por IP
 * </ul>
 */
@Component
public class AuthRateLimiter {

    /** Tamanho máximo do cache de buckets por endpoint. ~1MB heap por cache. */
    private static final long MAX_BUCKETS_PER_CACHE = 10_000;

    private final Cache<String, Bucket> loginBuckets;
    private final Cache<String, Bucket> resetBuckets;
    private final Cache<String, Bucket> signupBuckets;

    private final long loginPerMinute;
    private final long signupPerMinute;
    private final long resetPer10Minutes;

    public AuthRateLimiter(
            @Value("${nora.security.rate-limit.login-per-minute:10}") long loginPerMinute,
            @Value("${nora.security.rate-limit.signup-per-minute:5}") long signupPerMinute,
            @Value("${nora.security.rate-limit.reset-per-10-minutes:3}") long resetPer10Minutes) {
        this.loginPerMinute = loginPerMinute;
        this.signupPerMinute = signupPerMinute;
        this.resetPer10Minutes = resetPer10Minutes;

        this.loginBuckets = buildCache(Duration.ofMinutes(3));
        this.signupBuckets = buildCache(Duration.ofMinutes(3));
        this.resetBuckets = buildCache(Duration.ofMinutes(30));
    }

    private static Cache<String, Bucket> buildCache(Duration expireAfterAccess) {
        return Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS_PER_CACHE)
                .expireAfterAccess(expireAfterAccess)
                .build();
    }

    public boolean allowLogin(HttpServletRequest request) {
        return bucketFor(loginBuckets, clientKey(request), loginPerMinute, Duration.ofMinutes(1))
                .tryConsume(1);
    }

    public boolean allowSignup(HttpServletRequest request) {
        return bucketFor(signupBuckets, clientKey(request), signupPerMinute, Duration.ofMinutes(1))
                .tryConsume(1);
    }

    public boolean allowPasswordReset(String email) {
        if (email == null) {
            return false;
        }
        String key = email.trim().toLowerCase(Locale.ROOT);
        return bucketFor(resetBuckets, key, resetPer10Minutes, Duration.ofMinutes(10))
                .tryConsume(1);
    }

    private Bucket bucketFor(
            Cache<String, Bucket> store, String key, long capacity, Duration window) {
        return store.get(
                key, k -> Bucket.builder().addLimit(Bandwidth.simple(capacity, window)).build());
    }

    /**
     * Identifica cliente preferindo X-Forwarded-For (presente em Container Apps / proxy) com
     * fallback pro IP de conexão. Pega só o primeiro hop do XFF pra não ser enganável por cliente
     * que sete header próprio.
     */
    private String clientKey(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
