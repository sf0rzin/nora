package br.com.nora.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
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
        // always emptied first -- the account cap could never reject anything.
        //
        // The limiter is built through a Spring context ON PURPOSE, with no properties set, so
        // the @Value defaults in the constructor are what is under test. The first version of
        // this passed the numbers positionally -- `new AuthRateLimiter(10, 5, ...)` -- which
        // reads no shipped default at all: reverting the fix left it green, and the asserted 5
        // was the same literal the test had just handed in. A test for a default has to make
        // the default be resolved.
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.register(PropertySourcesPlaceholderConfigurer.class, AuthRateLimiter.class);
            context.refresh();
            AuthRateLimiter rl = context.getBean(AuthRateLimiter.class);

            String victim = "alvo@cliente.com";
            int passedTheOriginCap = 0;
            int accepted = 0;
            for (int i = 0; i < 50; i++) {
                MockHttpServletRequest req = request(null, null);
                if (!rl.allowLogin(req)) {
                    continue;
                }
                passedTheOriginCap++;
                if (rl.allowLoginForEmail(req, victim)) {
                    accepted++;
                }
            }

            // Both counted in the SAME pass -- a second loop would find the origin bucket
            // already drained by the first and measure nothing. The exact numbers belong to the
            // configuration; the invariant is that the account cap turns some of the attempts
            // the origin cap let through into rejections. At equal caps the two counts are
            // identical, which is the dead configuration this test exists for.
            assertThat(accepted)
                    .as("the account cap must reject before the origin cap empties")
                    .isLessThan(passedTheOriginCap);
            assertThat(accepted).as("the account cap must let something through").isPositive();
        }
    }

    @Test
    void theAccountCapIsConfiguredStrictlyBelowTheOriginCapInEveryShippedProfile()
            throws Exception {
        // Reads the two @Value defaults out of the constructor and compares them, so a revert
        // of the default is caught even if the behavioural test above were somehow satisfied.
        Constructor<?> ctor = AuthRateLimiter.class.getConstructors()[0];
        long perOrigin = defaultOf(ctor, 0);
        long perEmail = defaultOf(ctor, 1);
        assertThat(perEmail)
                .as(
                        "login-per-minute-per-email (%d) must be < login-per-minute (%d)",
                        perEmail, perOrigin)
                .isLessThan(perOrigin);

        // ...and the same invariant in the property files that ship with the application.
        for (String resource : new String[] {"/application.yml", "/application-test.yml"}) {
            try (InputStream in = AuthRateLimiterTest.class.getResourceAsStream(resource)) {
                if (in == null) {
                    continue;
                }
                String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Long origin = valueOf(yaml, "login-per-minute");
                Long email = valueOf(yaml, "login-per-minute-per-email");
                if (origin != null && email != null) {
                    assertThat(email)
                            .as("%s sets the account cap at or above the origin cap", resource)
                            .isLessThan(origin);
                }
            }
        }
    }

    /** The `:N` fallback of the `@Value` on constructor parameter `index`. */
    private static long defaultOf(Constructor<?> ctor, int index) {
        Value value = ctor.getParameters()[index].getAnnotation(Value.class);
        assertThat(value).as("parameter %d carries no @Value", index).isNotNull();
        Matcher m = Pattern.compile(":(\\d+)}$").matcher(value.value());
        assertThat(m.find()).as("no numeric default in %s", value.value()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private static Long valueOf(String yaml, String key) {
        Matcher m =
                Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":\\s*(\\d+)\\s*$")
                        .matcher(yaml);
        return m.find() ? Long.parseLong(m.group(1)) : null;
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
