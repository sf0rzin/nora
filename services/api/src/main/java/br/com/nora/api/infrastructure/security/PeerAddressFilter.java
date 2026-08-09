package br.com.nora.api.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stores the TCP PEER address before any other filter rewrites it.
 *
 * <p>The application runs with {@code server.forward-headers-strategy: framework}, which installs
 * Spring's {@code ForwardedHeaderFilter}. That filter wraps the request so that {@code
 * getRemoteAddr()} starts returning the LEFTMOST element of {@code X-Forwarded-For} — which is
 * precisely the segment the client writes. That is: after it, {@code getRemoteAddr()} is not the
 * address of whoever connected, it is a value chosen by the caller.
 *
 * <p>This makes {@code getRemoteAddr()} unusable as the {@link AuthRateLimiter} fallback: on any
 * request without the edge header — or with {@code nora.security.trusted-client-ip-header} empty,
 * as in the tests — the attacker would again pick their own bucket by sending a new XFF on every
 * attempt, which is exactly the failure the limiter exists to close.
 *
 * <p>Running with {@link Ordered#HIGHEST_PRECEDENCE} this filter sees the raw request and publishes
 * the peer in an attribute, immune to the later wrapping.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PeerAddressFilter extends OncePerRequestFilter {

    static final String ATTRIBUTE = PeerAddressFilter.class.getName() + ".peer";

    /**
     * TCP peer address, or the current {@code getRemoteAddr()} when the filter did not run (e.g.
     * MockMvc standalone). Never {@code null}.
     */
    static String peerAddress(HttpServletRequest request) {
        Object stored = request.getAttribute(ATTRIBUTE);
        if (stored instanceof String s && !s.isBlank()) {
            return s;
        }
        String fallback = request.getRemoteAddr();
        return fallback == null ? "" : fallback;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(ATTRIBUTE, request.getRemoteAddr());
        chain.doFilter(request, response);
    }
}
