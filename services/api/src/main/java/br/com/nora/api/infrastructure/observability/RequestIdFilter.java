package br.com.nora.api.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a correlatable request id to every HTTP request and exposes it in the MDC ({@value
 * #MDC_KEY}), in the response header ({@value #HEADER}) and — via {@code logging.pattern.level}
 * (application.yml) — on every log line of the request. The {@code GlobalExceptionHandler} uses the
 * same id as the error response's {@code traceId}, so an id reported by the user ties request →
 * logs → tenant / user, making it feasible to investigate incidents in production (before, the
 * traceId was a throwaway UUID with no correlation to any log).
 *
 * <p>Runs BEFORE the Spring Security chain ({@link Ordered#HIGHEST_PRECEDENCE}) so the id is
 * already in the MDC during authentication. Reuses an incoming {@code X-Request-Id} (from a proxy /
 * edge such as Cloudflare) when it is sane — sanitized against log injection — otherwise generates
 * a UUID. Clears the MDC in the finally block so it does not leak between requests that reuse the
 * same thread from the Tomcat pool.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // Only accepts an incoming id that looks safe (8–64 alphanumeric chars, hyphen, dot,
    // underscore) — avoids log injection / MDC pollution with an arbitrary client header.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = sanitizeOrGenerate(req.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        res.setHeader(HEADER, requestId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitizeOrGenerate(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
