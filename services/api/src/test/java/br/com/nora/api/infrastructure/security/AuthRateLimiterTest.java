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
    void perEmailCapSurvivesIpRotation() {
        // O teto por IP sozinho nao segura brute force distribuido: quem tem pool de saida troca
        // de IP e recomeca limpo. O teto por conta alvo e o que amarra o custo de adivinhar a
        // senha de um e-mail conhecido.
        AuthRateLimiter rl = limiter(100, 2);

        assertThat(rl.allowLoginForEmail("alvo@cliente.com")).isTrue();
        assertThat(rl.allowLoginForEmail("ALVO@cliente.com")).isTrue(); // mesma conta, outro casing
        assertThat(rl.allowLoginForEmail(" alvo@cliente.com ")).isFalse();
    }

    @Test
    void perEmailCapDoesNotLeakBetweenAccounts() {
        AuthRateLimiter rl = limiter(100, 1);

        assertThat(rl.allowLoginForEmail("um@cliente.com")).isTrue();
        assertThat(rl.allowLoginForEmail("um@cliente.com")).isFalse();
        assertThat(rl.allowLoginForEmail("outro@cliente.com")).isTrue();
    }

    @Test
    void missingEmailIsLeftToTheIpBucketAndTheValidator() {
        AuthRateLimiter rl = limiter(100, 1);

        assertThat(rl.allowLoginForEmail(null)).isTrue();
        assertThat(rl.allowLoginForEmail("  ")).isTrue();
    }
}
