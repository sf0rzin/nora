package br.com.nora.api.infrastructure.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HexFormat;
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
 *   <li>Login: 10 / minuto por origem (humanidade real raramente erra 10x/min)
 *   <li>Login: 10 / minuto por (e-mail alvo, origem) — ver {@link #allowLoginForEmail}
 *   <li>Reset request: 3 / 10 minutos por email (evita spam de reset)
 *   <li>Signup: 5 / minuto por origem
 * </ul>
 *
 * <p>"Origem" é a rede do cliente (/32 em IPv4, /64 em IPv6), não o endereço exato — ver {@link
 * #clientKey}.
 *
 * <p>Ver {@link #clientKey} sobre por que a identificação por IP não usa X-Forwarded-For.
 */
@Component
public class AuthRateLimiter {

    /** Tamanho máximo do cache de buckets por endpoint. ~1MB heap por cache. */
    private static final long MAX_BUCKETS_PER_CACHE = 10_000;

    /**
     * Só dígitos/pontos (IPv4) ou hex/dois-pontos (IPv6, incl. a forma mista ::ffff:1.2.3.4). Serve
     * de portão antes do {@code InetAddress}, que resolveria DNS para qualquer outra coisa.
     */
    private static final java.util.regex.Pattern IP_LITERAL =
            java.util.regex.Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

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
     * Teto por (conta alvo, origem). Fecha o buraco de trocar de IP e recomeçar do zero contra o
     * MESMO e-mail, sem dar a terceiros o poder de gastar o orçamento da vítima.
     *
     * <p><strong>Por que a chave inclui a origem.</strong> A primeira versão disto era um balde só
     * por e-mail, descrito como "throttle, não lockout". Estava errado: o {@code Bandwidth.simple}
     * do Bucket4j recarrega de forma gradual — 10/minuto é um token a cada 6s —, então bastava um
     * atacante mandar uma tentativa a cada 6s, de um único IP e sem estourar o próprio teto, para
     * consumir cada token no instante em que ele nascia. O dono da conta, com a senha certa, levava
     * 429 para sempre. Era um lockout remoto de qualquer conta cujo e-mail se conheça — trocar
     * brute force por negação de serviço não é troca aceitável.
     *
     * <p>Com a origem na chave, o atacante só consegue esgotar o próprio par (e-mail, IP): a
     * vítima, vindo de outro endereço, tem o balde dela intacto. Contra um atacante distribuído
     * este teto vale menos, mas aí quem limita é o balde por origem — e o custo por IP continua de
     * pé porque {@link #clientKey} não é mais escolhível pelo cliente.
     */
    public boolean allowLoginForEmail(HttpServletRequest request, String email) {
        if (email == null || email.isBlank()) {
            // Sem e-mail não há o que limitar por conta; o bucket por IP ainda se aplica, e a
            // validação do @Valid rejeita o request logo em seguida.
            return true;
        }
        String key = email.trim().toLowerCase(Locale.ROOT) + "|" + clientKey(request);
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
                // A Cloudflare manda um IP só, mas normalizar defende contra uma borda futura
                // que anexe em vez de sobrescrever: o primeiro elemento seria de novo escolhível
                // pelo cliente, então fica o ÚLTIMO, que é quem a borda escreveu.
                int lastComma = edgeIp.lastIndexOf(',');
                return networkKey(
                        lastComma < 0 ? edgeIp.trim() : edgeIp.substring(lastComma + 1).trim());
            }
        }
        return networkKey(PeerAddressFilter.peerAddress(request));
    }

    /**
     * Agrupa o endereço na rede a que pertence: /32 em IPv4, /64 em IPv6.
     *
     * <p>Sem isto o balde é por endereço exato, e em IPv6 isso não limita nada: qualquer ligação
     * doméstica ou VPS recebe um /64 delegado, ou seja 2^64 endereços de origem. Trocar de endereço
     * dentro do próprio prefixo dava um balde novo por request, exatamente o problema que o
     * X-Forwarded-For tinha. O /64 é a menor unidade que um operador atribui, então é o que
     * corresponde a "um cliente".
     *
     * <p>Valor que não parseia como IP não vira chave: cai num balde comum de lixo, porque aceitar
     * texto arbitrário como chave é o mesmo que deixar o cliente escolher o balde.
     */
    private static String networkKey(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "unknown";
        }
        String candidate = rawIp.trim();
        // Forma [2001:db8::1]:443 e 1.2.3.4:443 — descarta a porta, mantém o endereço.
        if (candidate.startsWith("[")) {
            int close = candidate.indexOf(']');
            if (close > 0) {
                candidate = candidate.substring(1, close);
            }
        } else {
            // Um único ':' num valor que também tem pontos é "ipv4:porta". Zero ':' é IPv4 puro;
            // dois ou mais é IPv6 sem colchetes, onde os ':' são o próprio endereço.
            int colon = candidate.indexOf(':');
            if (colon >= 0 && colon == candidate.lastIndexOf(':') && candidate.indexOf('.') >= 0) {
                candidate = candidate.substring(0, colon);
            }
        }
        // SÓ literal. `InetAddress.getByName` resolve DNS quando recebe um nome, então um
        // `CF-Connecting-IP: evil.example.com` viraria um lookup por request — resolução de nome
        // controlada pelo cliente, no caminho de login.
        if (!IP_LITERAL.matcher(candidate).matches()) {
            return "unparseable";
        }
        try {
            byte[] addr = InetAddress.getByName(candidate).getAddress();
            if (addr.length == 16) {
                // /64: os 8 primeiros bytes identificam a rede.
                return HexFormat.of().formatHex(addr, 0, 8) + "::/64";
            }
            return InetAddress.getByAddress(addr).getHostAddress();
        } catch (UnknownHostException e) {
            return "unparseable";
        }
    }
}
