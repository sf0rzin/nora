package br.com.nora.api.infrastructure.security;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Records the TCP peer address before anything can rewrite it.
 *
 * <p>The application runs with {@code server.forward-headers-strategy: framework}, which installs
 * Spring's {@code ForwardedHeaderFilter}. That filter wraps the request so {@code getRemoteAddr()}
 * returns the LEFTMOST element of {@code X-Forwarded-For} — precisely the segment the client
 * writes. After it, {@code getRemoteAddr()} is not who connected; it is a value the caller chose.
 *
 * <p>That makes {@code getRemoteAddr()} unusable as {@link AuthRateLimiter}'s fallback: on any
 * request without the edge header — or with {@code nora.security.trusted-client-ip-header} empty,
 * as in tests — the attacker would again pick his own bucket by sending a fresh XFF each attempt,
 * which is exactly the hole the limiter exists to close.
 *
 * <p>This was first attempted with a filter at {@code HIGHEST_PRECEDENCE}. It does not work: Spring
 * Boot registers {@code ForwardedHeaderFilter} at {@code HIGHEST_PRECEDENCE} too, and two filters
 * with the same order are ordered arbitrarily — a coin flip decided whether the peer was captured
 * before or after the wrapping, and there is no order below the minimum to claim. A listener
 * sidesteps the race entirely: the container invokes {@code requestInitialized} before the filter
 * chain runs at all, so the address recorded here is always the socket's.
 */
@Component
public class PeerAddressListener implements ServletRequestListener {

    static final String ATTRIBUTE = PeerAddressListener.class.getName() + ".peer";

    /**
     * The TCP peer address, or the current {@code getRemoteAddr()} when the listener did not run
     * (standalone MockMvc, for instance). Never {@code null}.
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
    public void requestInitialized(ServletRequestEvent event) {
        event.getServletRequest()
                .setAttribute(ATTRIBUTE, event.getServletRequest().getRemoteAddr());
    }
}
