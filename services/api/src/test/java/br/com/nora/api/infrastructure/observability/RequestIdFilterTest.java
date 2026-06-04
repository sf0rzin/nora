package br.com.nora.api.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Garante o contrato do {@link RequestIdFilter}: gera/propaga o id, expõe no header e limpa o MDC.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesRequestId_whenNoHeader_setsHeaderAndClearsMdc() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        // Captura o valor do MDC durante a cadeia (depois é limpo no finally).
        final String[] mdcDuringChain = new String[1];
        FilterChain chain =
                (request, response) -> mdcDuringChain[0] = MDC.get(RequestIdFilter.MDC_KEY);

        filter.doFilter(req, res, chain);

        String header = res.getHeader(RequestIdFilter.HEADER);
        assertThat(header).isNotBlank();
        assertThat(mdcDuringChain[0]).isEqualTo(header);
        // Limpou o MDC após o request (não vaza entre threads reusadas).
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
        // Tentativa de log injection / valor muito curto: deve ser descartado e gerado um UUID.
        req.addHeader(RequestIdFilter.HEADER, "bad id\nINJECT");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seen = new String[1];
        FilterChain chain = (request, response) -> seen[0] = MDC.get(RequestIdFilter.MDC_KEY);

        filter.doFilter(req, res, chain);

        assertThat(seen[0]).isNotNull().doesNotContain("INJECT").doesNotContain("\n");
        // UUID gerado tem 36 chars.
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
            // esperado
        }
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
