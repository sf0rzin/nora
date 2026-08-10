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
 * Rate limiter for public authentication endpoints. Defense against credential stuffing / brute
 * force / reset e-mail spam.
 *
 * <p>In-memory buckets per key (`IP` for login, `email` for reset). Per instance — on scale-out the
 * real cap = N×instances. Good enough for the MVP; migrate to Redis when running with more than 1
 * permanent replica.
 *
 * <p><strong>Eviction:</strong> the caches use Caffeine with {@code maximumSize} (bounds memory
 * under a distributed attack with 10k+ unique IPs) and {@code expireAfterAccess} ~3× the window
 * (ensures idle buckets get collected without recreating the state of whoever is still inside the
 * window).
 *
 * <p>Limits based on standard OWASP posture, configurable via properties so that integration tests
 * (which run dozens of logins from the same loopback in the same JVM) can relax them without
 * affecting production.
 *
 * <ul>
 *   <li>Login: 10 / minute per origin (real humans rarely fail 10x/min)
 *   <li>Login: 5 / minute per (target e-mail, origin) — see {@link #allowLoginForEmail}
 *   <li>Reset request: 3 / 10 minutes per email (prevents reset spam)
 *   <li>Signup: 5 / minute per origin
 * </ul>
 *
 * <p>The per-account cap has to sit STRICTLY BELOW the per-origin one or it can never bind: a login
 * consumes a token from both buckets, so at equal capacity the origin bucket always empties first
 * and the account cap rejects nothing. It shipped at 10 against 10 and was therefore dead code
 * pretending to be a control.
 *
 * <p>"Origin" is the client's network (/32 in IPv4, /64 in IPv6), not the exact address — see
 * {@link #clientKey}.
 *
 * <p>See {@link #clientKey} for why IP identification does not use X-Forwarded-For.
 */
@Component
public class AuthRateLimiter {

    /** Maximum size of the bucket cache per endpoint. ~1MB heap per cache. */
    private static final long MAX_BUCKETS_PER_CACHE = 10_000;

    /**
     * Longest address RFC 5321 allows, and therefore the cap on the length of an e-mail bucket key.
     * See {@link #emailKey}.
     */
    private static final int MAX_EMAIL_KEY_LENGTH = 254;

    /**
     * Digits/dots only (IPv4) or hex/colons (IPv6, incl. the mixed form ::ffff:1.2.3.4). Acts as a
     * gate before {@code InetAddress}, which would resolve DNS for anything else.
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
            @Value("${nora.security.rate-limit.login-per-minute-per-email:5}")
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
     * Cap per (target account, origin). Closes the hole of switching IP and starting over from zero
     * against the SAME e-mail, without giving third parties the power to spend the victim's budget.
     *
     * <p><strong>Why the key includes the origin.</strong> The first version of this was a single
     * bucket per e-mail, described as "throttle, not lockout". That was wrong: Bucket4j's {@code
     * Bandwidth.simple} refills gradually — 10/minute is one token every 6s —, so all an attacker
     * had to do was send one attempt every 6s, from a single IP and without blowing their own cap,
     * to consume each token the instant it was born. The account owner, with the right password,
     * got a 429 forever. It was a remote lockout of any account whose e-mail is known — trading
     * brute force for denial of service is not an acceptable trade.
     *
     * <p>With the origin in the key, the attacker can only exhaust their own (e-mail, IP) pair: the
     * victim, coming from another address, has their bucket intact. Against a distributed attacker
     * this cap is worth less, but then the per-origin bucket is what limits — and the per-IP cost
     * still stands because {@link #clientKey} is no longer client-choosable.
     */
    public boolean allowLoginForEmail(HttpServletRequest request, String email) {
        if (email == null || email.isBlank()) {
            // With no e-mail there is nothing to limit per account; the per-IP bucket still
            // applies, and @Valid validation rejects the request right after.
            return true;
        }
        String key = emailKey(email) + "|" + clientKey(request);
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
        return bucketFor(resetBuckets, emailKey(email), resetPer10Minutes, Duration.ofMinutes(10))
                .tryConsume(1);
    }

    /**
     * Normalizes an address into a bucket key and bounds its length.
     *
     * <p>Bean validation on the request DTOs already caps the field, but the cap is repeated here
     * because this class is the thing that RETAINS the value: a key lives in the cache for as long
     * as the eviction window, up to {@link #MAX_BUCKETS_PER_CACHE} of them at once. What a caller
     * can put in the heap should be bounded by the component that holds it, not only by the
     * annotation on the layer above — a new caller, or an endpoint whose DTO drifts, must not be
     * able to widen that.
     *
     * <p>Anything longer than a valid address is truncated rather than rejected: the bucket only
     * has to be stable per caller, and values in that range share one, which is the correct
     * treatment for input no legitimate flow produces.
     */
    private static String emailKey(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= MAX_EMAIL_KEY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_EMAIL_KEY_LENGTH);
    }

    private Bucket bucketFor(
            Cache<String, Bucket> store, String key, long capacity, Duration window) {
        return store.get(
                key, k -> Bucket.builder().addLimit(Bandwidth.simple(capacity, window)).build());
    }

    /**
     * Identifies the client by the header the edge proxy writes — by default Cloudflare's {@code
     * CF-Connecting-IP} —, falling back to the connection IP.
     *
     * <p><strong>Why not X-Forwarded-For:</strong> XFF is a list {@code client, proxy1, proxy2...}
     * in which each hop APPENDS. The leftmost hop is precisely the one the client can forge: by
     * sending {@code X-Forwarded-For: 1.2.3.<n>} with a new n on every request, the attacker gets a
     * new bucket per attempt and the limit never fires. The rightmost hop would be trustworthy, but
     * in this topology it is always the cloudflared IP on the private bridge — the same for
     * everyone, which would turn the limiter into a global bucket and take down legitimate users.
     * Neither one works.
     *
     * <p>{@code CF-Connecting-IP} works because Cloudflare OVERWRITES it at the edge, discarding
     * whatever the client sent. That holds as long as the tunnel is the only ingress — the stack
     * publishes no API port at all (see {@code infra/host/docker-compose.yml}). If one day the API
     * becomes reachable by another path, this header becomes forgeable again: that is why its name
     * is configurable, and leaving it empty forces the use of the connection IP.
     */
    private String clientKey(HttpServletRequest request) {
        if (!trustedClientIpHeader.isBlank()) {
            String edgeIp = request.getHeader(trustedClientIpHeader);
            if (edgeIp != null && !edgeIp.isBlank()) {
                // Cloudflare sends a single IP, but normalizing defends against a future edge
                // that appends instead of overwriting: the first element would again be
                // client-choosable, so we keep the LAST one, which is what the edge wrote.
                int lastComma = edgeIp.lastIndexOf(',');
                return networkKey(
                        lastComma < 0 ? edgeIp.trim() : edgeIp.substring(lastComma + 1).trim());
            }
        }
        return networkKey(PeerAddressListener.peerAddress(request));
    }

    /**
     * Groups the address into the network it belongs to: /32 in IPv4, /64 in IPv6.
     *
     * <p>Without this the bucket is per exact address, and in IPv6 that limits nothing: any home
     * connection or VPS gets a delegated /64, that is 2^64 source addresses. Switching address
     * within your own prefix gave a new bucket per request, exactly the problem X-Forwarded-For
     * had. The /64 is the smallest unit an operator assigns, so it is what corresponds to "one
     * client".
     *
     * <p>A value that does not parse as an IP does not become a key: it falls into a common junk
     * bucket, because accepting arbitrary text as a key is the same as letting the client pick the
     * bucket.
     */
    private static String networkKey(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "unknown";
        }
        String candidate = rawIp.trim();
        // Forms [2001:db8::1]:443 and 1.2.3.4:443 — drops the port, keeps the address.
        if (candidate.startsWith("[")) {
            int close = candidate.indexOf(']');
            if (close > 0) {
                candidate = candidate.substring(1, close);
            }
        } else {
            // A single ':' in a value that also has dots is "ipv4:port". Zero ':' is plain IPv4;
            // two or more is IPv6 without brackets, where the ':' are the address itself.
            int colon = candidate.indexOf(':');
            if (colon >= 0 && colon == candidate.lastIndexOf(':') && candidate.indexOf('.') >= 0) {
                candidate = candidate.substring(0, colon);
            }
        }
        // LITERAL only. `InetAddress.getByName` resolves DNS when given a name, so a
        // `CF-Connecting-IP: evil.example.com` would become a lookup per request —
        // client-controlled name resolution, on the login path.
        if (!IP_LITERAL.matcher(candidate).matches()) {
            return "unparseable";
        }
        try {
            byte[] addr = InetAddress.getByName(candidate).getAddress();
            if (addr.length == 16) {
                // /64: the first 8 bytes identify the network.
                return HexFormat.of().formatHex(addr, 0, 8) + "::/64";
            }
            return InetAddress.getByAddress(addr).getHostAddress();
        } catch (UnknownHostException e) {
            return "unparseable";
        }
    }
}
