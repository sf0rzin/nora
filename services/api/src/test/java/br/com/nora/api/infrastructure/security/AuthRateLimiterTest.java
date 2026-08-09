package br.com.nora.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * O application-test.yml afirmava que "a defesa anti brute-force fica testada via unit test do
 * AuthRateLimiter proprio" -- e nao existia teste nenhum. Estes cobrem o contrato que importa: de
 * onde vem a identidade do cliente, e o que sobra de protecao quando essa identidade e trocada.
 */
class AuthRateLimiterTest {

    private static final String EDGE_HEADER = "CF-Connecting-IP";

    private static AuthRateLimiter limiter(long perIp, long perEmail) {
        return new AuthRateLimiter(perIp, perEmail, 100, 100, EDGE_HEADER);
    }

    private static MockHttpServletRequest request(String edgeIp, String forwardedFor) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.7"); // cloudflared na bridge privada: igual pra todo mundo
        if (edgeIp != null) {
            req.addHeader(EDGE_HEADER, edgeIp);
        }
        if (forwardedFor != null) {
            req.addHeader("X-Forwarded-For", forwardedFor);
        }
        return req;
    }

    @Test
    void forgingXForwardedForDoesNotBuyAFreshBucket() {
        // Antes: clientKey lia o PRIMEIRO hop do XFF, que e exatamente o que o cliente escreve.
        // Trocando o valor a cada request, cada tentativa caia num bucket novo e o teto de 2/min
        // nunca disparava. Agora o XFF e ignorado, entao as tres tentativas dividem o bucket.
        AuthRateLimiter rl = limiter(2, 100);

        assertThat(rl.allowLogin(request("203.0.113.9", "1.2.3.1"))).isTrue();
        assertThat(rl.allowLogin(request("203.0.113.9", "1.2.3.2"))).isTrue();
        assertThat(rl.allowLogin(request("203.0.113.9", "1.2.3.3"))).isFalse();
    }

    @Test
    void distinctEdgeIpsGetDistinctBuckets() {
        // Contraprova: o limitador precisa continuar separando clientes de verdade, senao viraria
        // um bucket global e derrubaria usuario legitimo.
        AuthRateLimiter rl = limiter(1, 100);

        assertThat(rl.allowLogin(request("203.0.113.9", null))).isTrue();
        assertThat(rl.allowLogin(request("203.0.113.9", null))).isFalse();
        assertThat(rl.allowLogin(request("198.51.100.4", null))).isTrue();
    }

    @Test
    void fallsBackToRemoteAddrWhenTheEdgeHeaderIsAbsent() {
        AuthRateLimiter rl = limiter(1, 100);

        assertThat(rl.allowLogin(request(null, null))).isTrue();
        assertThat(rl.allowLogin(request(null, "1.2.3.4"))).isFalse();
    }

    @Test
    void emptyHeaderNameForcesRemoteAddrAndIgnoresAForgedHeader() {
        // Configuracao usada em teste/dev, onde nao ha borda Cloudflare: mesmo mandando o header,
        // o cliente nao consegue escolher o proprio bucket.
        AuthRateLimiter rl = new AuthRateLimiter(1, 100, 100, 100, "");

        assertThat(rl.allowLogin(request("203.0.113.9", null))).isTrue();
        assertThat(rl.allowLogin(request("198.51.100.4", null))).isFalse();
    }

    @Test
    void perEmailCapBindsTheSameAccountAcrossAttemptsFromOneOrigin() {
        AuthRateLimiter rl = limiter(100, 2);
        MockHttpServletRequest attacker = request("203.0.113.9", null);

        assertThat(rl.allowLoginForEmail(attacker, "alvo@cliente.com")).isTrue();
        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), "ALVO@cliente.com"))
                .isTrue();
        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), " alvo@cliente.com "))
                .isFalse();
    }

    @Test
    void oneAttackerCannotLockTheVictimOutOfTheirOwnAccount() {
        // A primeira versao chaveava o balde SO no e-mail. Como o refill do Bucket4j e gradual,
        // um atacante consumindo cada token assim que nascia trancava a conta da vitima para
        // sempre -- brute force trocado por negacao de servico. Com a origem na chave, o atacante
        // esgota apenas o proprio par (e-mail, origem).
        AuthRateLimiter rl = limiter(100, 1);
        String victim = "alvo@cliente.com";

        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), victim)).isTrue();
        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), victim)).isFalse();

        // A vitima, de outro endereco, entra normalmente.
        assertThat(rl.allowLoginForEmail(request("198.51.100.4", null), victim)).isTrue();
    }

    @Test
    void perEmailCapDoesNotLeakBetweenAccounts() {
        AuthRateLimiter rl = limiter(100, 1);
        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), "um@cliente.com")).isTrue();
        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), "um@cliente.com")).isFalse();
        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), "outro@cliente.com"))
                .isTrue();
    }

    @Test
    void missingEmailIsLeftToTheIpBucketAndTheValidator() {
        AuthRateLimiter rl = limiter(100, 1);
        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), null)).isTrue();
        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), "  ")).isTrue();
    }

    // --------------------------------------------------------- normalizacao de rede

    @Test
    void addressesInTheSameIpv6SlashSixtyFourShareABucket() {
        // Toda ligacao domestica ou VPS recebe um /64 delegado: sem normalizar, trocar de
        // endereco dentro do proprio prefixo dava balde novo por request -- a mesma falha do XFF.
        AuthRateLimiter rl = limiter(2, 100);

        assertThat(rl.allowLogin(request("2001:db8:a:b::1", null))).isTrue();
        assertThat(rl.allowLogin(request("2001:db8:a:b::2", null))).isTrue();
        assertThat(rl.allowLogin(request("2001:db8:a:b::dead:beef", null))).isFalse();
    }

    @Test
    void differentIpv6PrefixesGetDifferentBuckets() {
        AuthRateLimiter rl = limiter(1, 100);
        assertThat(rl.allowLogin(request("2001:db8:a:b::1", null))).isTrue();
        assertThat(rl.allowLogin(request("2001:db8:a:b::2", null))).isFalse();
        assertThat(rl.allowLogin(request("2001:db8:a:c::1", null))).isTrue();
    }

    @Test
    void aPortSuffixDoesNotMintANewBucket() {
        AuthRateLimiter rl = limiter(1, 100);
        assertThat(rl.allowLogin(request("203.0.113.9:443", null))).isTrue();
        assertThat(rl.allowLogin(request("203.0.113.9:1024", null))).isFalse();
        assertThat(rl.allowLogin(request("[2001:db8:a:b::1]:443", null))).isTrue();
        assertThat(rl.allowLogin(request("[2001:db8:a:b::9]:8443", null))).isFalse();
    }

    @Test
    void aHostnameIsNeverResolvedAndAllSuchValuesShareOneBucket() {
        // InetAddress.getByName resolveria DNS: um header com nome de host viraria um lookup por
        // request, controlado pelo cliente, no caminho de login. Valor nao-literal cai num balde
        // unico de lixo -- aceitar texto arbitrario como chave e deixar o cliente escolher balde.
        AuthRateLimiter rl = limiter(1, 100);
        assertThat(rl.allowLogin(request("evil.example.com", null))).isTrue();
        assertThat(rl.allowLogin(request("outro.example.com", null))).isFalse();
    }
}
