package br.com.nora.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * application-test.yml claimed that "the anti brute-force defence is tested via the
 * AuthRateLimiter's own unit test" -- and there was no test at all. These cover the contract that
 * matters: where the client identity comes from, and what is left of the protection when that
 * identity is swapped.
 */
class AuthRateLimiterTest {

    private static final String EDGE_HEADER = "CF-Connecting-IP";

    private static AuthRateLimiter limiter(long perIp, long perEmail) {
        return new AuthRateLimiter(perIp, perEmail, 100, 100, EDGE_HEADER);
    }

    private static MockHttpServletRequest request(String edgeIp, String forwardedFor) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.7"); // cloudflared on the private bridge: same for everyone
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
        // Before: clientKey read the FIRST XFF hop, which is exactly what the client writes.
        // Changing the value on every request, each attempt landed in a new bucket and the 2/min
        // cap never fired. Now the XFF is ignored, so the three attempts share the bucket.
        AuthRateLimiter rl = limiter(2, 100);

        assertThat(rl.allowLogin(request("203.0.113.9", "1.2.3.1"))).isTrue();
        assertThat(rl.allowLogin(request("203.0.113.9", "1.2.3.2"))).isTrue();
        assertThat(rl.allowLogin(request("203.0.113.9", "1.2.3.3"))).isFalse();
    }

    @Test
    void distinctEdgeIpsGetDistinctBuckets() {
        // Counter-proof: the limiter must keep separating real clients, otherwise it would become
        // a global bucket and take down legitimate users.
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
        // Configuration used in test/dev, where there is no Cloudflare edge: even sending the
        // header, the client cannot choose its own bucket.
        AuthRateLimiter rl = new AuthRateLimiter(1, 100, 100, 100, "");

        assertThat(rl.allowLogin(request("203.0.113.9", null))).isTrue();
        assertThat(rl.allowLogin(request("198.51.100.4", null))).isFalse();
    }

    @Test
    void theShippedDefaultsLetTheAccountCapActuallyBind() {
        // The whole point of the account cap is to reject before the origin cap does. It shipped
        // at 10 against 10, and since a login consumes a token from BOTH buckets the origin one
        // always emptied first -- the account cap could never reject anything. This pins the
        // relationship, not the numbers: whatever the defaults become, the account cap has to be
        // the one that fires first for a single account attacked from a single origin.
        AuthRateLimiter rl = new AuthRateLimiter(10, 5, 5, 3, EDGE_HEADER);
        String victim = "alvo@cliente.com";
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = request("203.0.113.9", null);
            if (rl.allowLogin(req) && rl.allowLoginForEmail(req, victim)) {
                accepted++;
            }
        }
        assertThat(accepted)
                .as("o teto por conta tem de morder antes do teto por origem")
                .isEqualTo(5);
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
        // The first version keyed the bucket ONLY on the e-mail. Since the Bucket4j refill is
        // gradual, an attacker consuming each token as soon as it was born locked the victim's
        // account forever -- brute force swapped for denial of service. With the origin in the
        // key, the attacker only exhausts its own (e-mail, origin) pair.
        AuthRateLimiter rl = limiter(100, 1);
        String victim = "alvo@cliente.com";

        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), victim)).isTrue();
        assertThat(rl.allowLoginForEmail(request("203.0.113.9", null), victim)).isFalse();

        // The victim, from another address, gets in normally.
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

    // --------------------------------------------------------- network normalization

    @Test
    void addressesInTheSameIpv6SlashSixtyFourShareABucket() {
        // Every home connection or VPS gets a delegated /64: without normalizing, changing the
        // address inside your own prefix gave a new bucket per request -- same failure as the XFF.
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
        // InetAddress.getByName would resolve DNS: a header with a host name would become one
        // lookup per request, controlled by the client, on the login path. A non-literal value
        // falls into a single junk bucket -- accepting arbitrary text as the key is letting the
        // client choose its bucket.
        AuthRateLimiter rl = limiter(1, 100);
        assertThat(rl.allowLogin(request("evil.example.com", null))).isTrue();
        assertThat(rl.allowLogin(request("outro.example.com", null))).isFalse();
    }
}
