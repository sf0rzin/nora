package br.com.nora.api.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Ensures the {@link RequestIdFilter} contract: generates/propagates the id, exposes it in the
 * header and clears the MDC.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesRequestId_whenNoHeader_setsHeaderAndClearsMdc() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        // Captures the MDC value during the chain (it is cleared later in the finally).
        final String[] mdcDuringChain = new String[1];
        FilterChain chain =
                (request, response) -> mdcDuringChain[0] = MDC.get(RequestIdFilter.MDC_KEY);

        filter.doFilter(req, res, chain);

        String header = res.getHeader(RequestIdFilter.HEADER);
        assertThat(header).isNotBlank();
        assertThat(mdcDuringChain[0]).isEqualTo(header);
        // Cleared the MDC after the request (does not leak across reused threads).
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void reusesIncomingRequestId_whenHeaderIsSafe() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.HEADER, "edge-abc123-XYZ");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seen = new String[1];
        FilterChain chain = (request, response) -> seen[0] = MDC.get(RequestIdFilter.MDC_KEY);

        filter.doFilter(req, res, chain);

        assertThat(seen[0]).isEqualTo("edge-abc123-XYZ");
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo("edge-abc123-XYZ");
    }

    @Test
    void ignoresUnsafeIncomingHeader_andGeneratesInstead() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        // Log injection attempt / far too short value: must be discarded and a UUID generated.
        req.addHeader(RequestIdFilter.HEADER, "bad id\nINJECT");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seen = new String[1];
        FilterChain chain = (request, response) -> seen[0] = MDC.get(RequestIdFilter.MDC_KEY);

        filter.doFilter(req, res, chain);

        assertThat(seen[0]).isNotNull().doesNotContain("INJECT").doesNotContain("\n");
        // A generated UUID has 36 chars.
        assertThat(seen[0]).hasSize(36);
    }

    @Test
    void clearsMdc_evenWhenChainThrows() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain boom =
                (request, response) -> {
                    throw new RuntimeException("boom");
                };

        try {
            filter.doFilter(req, res, boom);
        } catch (Exception ignored) {
            // expected
        }
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
