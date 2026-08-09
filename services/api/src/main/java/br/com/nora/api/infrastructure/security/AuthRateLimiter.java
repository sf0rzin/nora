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
 *   <li>Login: 10 / minuto por e-mail alvo — teto que sobrevive à troca de IP
 *   <li>Reset request: 3 / 10 minutos por email (evita spam de reset)
 *   <li>Signup: 5 / minuto por IP
 * </ul>
 *
 * <p>Ver {@link #clientKey} sobre por que a identificação por IP não usa X-Forwarded-For.
 */
@Component
public class AuthRateLimiter {

    /** Tamanho máximo do cache de buckets por endpoint. ~1MB heap por cache. */
    private static final long MAX_BUCKETS_PER_CACHE = 10_000;

    private final Cache<String, Bucket> loginBuckets;
    private final Cache<String, Bucket> loginEmailBuckets;
    private final Cache<String, Bucket> resetBuckets;
    private final Cache<String, Bucket> signupBuckets;

    private final long loginPerMinute;
    private final long loginPerMinutePerEmail;
    private final long signupPerMinute;
    private final long resetPer10Minutes;
    private final String trustedClientIpHeader;

    public AuthRateLimiter(
            @Value("${nora.security.rate-limit.login-per-minute:10}") long loginPerMinute,
            @Value("${nora.security.rate-limit.login-per-minute-per-email:10}")
                    long loginPerMinutePerEmail,
            @Value("${nora.security.rate-limit.signup-per-minute:5}") long signupPerMinute,
            @Value("${nora.security.rate-limit.reset-per-10-minutes:3}") long resetPer10Minutes,
            @Value("${nora.security.trusted-client-ip-header:CF-Connecting-IP}")
                    String trustedClientIpHeader) {
        this.loginPerMinute = loginPerMinute;
        this.loginPerMinutePerEmail = loginPerMinutePerEmail;
        this.signupPerMinute = signupPerMinute;
        this.resetPer10Minutes = resetPer10Minutes;
        this.trustedClientIpHeader =
                trustedClientIpHeader == null ? "" : trustedClientIpHeader.trim();

        this.loginBuckets = buildCache(Duration.ofMinutes(3));
        this.loginEmailBuckets = buildCache(Duration.ofMinutes(3));
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

    /**
     * Teto por conta alvo, independente de origem. O limite por IP sozinho não segura brute force:
     * quem tem uma botnet ou um pool de saída troca de IP e recomeça com bucket limpo. Este aqui é
     * o que amarra o custo de adivinhar a senha de UM e-mail conhecido.
     *
     * <p>É throttle, não lockout: o bucket recarrega dentro da janela, então o pior que um atacante
     * consegue é atrasar o login legítimo do alvo por um minuto — não trancar a conta. Lockout
     * persistente seria trocar brute force por negação de serviço contra qualquer usuário cujo
     * e-mail se conheça.
     */
    public boolean allowLoginForEmail(String email) {
        if (email == null || email.isBlank()) {
            // Sem e-mail não há o que limitar por conta; o bucket por IP ainda se aplica, e a
            // validação do @Valid rejeita o request logo em seguida.
            return true;
        }
        String key = email.trim().toLowerCase(Locale.ROOT);
        return bucketFor(loginEmailBuckets, key, loginPerMinutePerEmail, Duration.ofMinutes(1))
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
     * Identifica o cliente pelo header que o proxy de borda escreve — por padrão o {@code
     * CF-Connecting-IP} da Cloudflare —, com fallback pro IP de conexão.
     *
     * <p><strong>Por que não X-Forwarded-For:</strong> o XFF é uma lista {@code cliente, proxy1,
     * proxy2...} em que cada salto ANEXA. O hop mais à esquerda é justamente o que o cliente pode
     * forjar: mandando {@code X-Forwarded-For: 1.2.3.<n>} com n novo a cada request, o atacante
     * ganha um bucket novo por tentativa e o limite nunca dispara. O hop mais à direita seria
     * confiável, mas nesta topologia é sempre o IP do cloudflared na bridge privada — igual pra
     * todo mundo, o que transformaria o limitador num bucket global e derrubaria usuários
     * legítimos. Nenhum dos dois serve.
     *
     * <p>O {@code CF-Connecting-IP} serve porque a Cloudflare o SOBRESCREVE na borda, descartando o
     * que o cliente tenha mandado. Isso vale enquanto o túnel for o único ingresso — a stack não
     * publica porta nenhuma da API (ver {@code infra/proxmox/docker-compose.yml}). Se um dia a API
     * ficar acessível por outro caminho, este header volta a ser forjável: por isso o nome dele é
     * configurável, e deixá-lo vazio força o uso do IP de conexão.
     */
    private String clientKey(HttpServletRequest request) {
        if (!trustedClientIpHeader.isBlank()) {
            String edgeIp = request.getHeader(trustedClientIpHeader);
            if (edgeIp != null && !edgeIp.isBlank()) {
                return edgeIp.trim();
            }
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
