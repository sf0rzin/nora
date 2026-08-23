package br.com.nora.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The JSON body cap. Nothing in the stack had one — the multipart limits cover multipart, the
 * Tomcat post limit covers form-urlencoded, and Spring MVC has no property for a JSON body — so
 * these tests pin the three behaviours the filter exists for.
 */
class RequestBodySizeLimitFilterTest {

    private static final int LIMIT = 64;
    private static final int LIVE_ANALYZE_LIMIT = LIMIT * 4;

    private static RequestBodySizeLimitFilter filter() {
        // The mapper is built the way the application builds it, not with `new ObjectMapper()`.
        // A bare mapper has no JSR-310 module, so serialising ErrorResponse — whose `timestamp` is
        // an Instant — throws, and the test would fail on the refusal path while production, which
        // is injected the auto-configured mapper, serialises it fine. A test that cannot render the
        // error body is testing a filter the application does not have.
        return new RequestBodySizeLimitFilter(
                LIMIT, LIVE_ANALYZE_LIMIT, Jackson2ObjectMapperBuilder.json().build());
    }

    private static MockHttpServletRequest jsonPost(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    @Test
    void aBodyWithinTheCapPassesThrough() throws Exception {
        MockHttpServletRequest req = jsonPost("{\"email\":\"a@b.dev\"}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    /**
     * The declared-length path: refused before a single byte of body is read, which is the whole
     * point on a public route that needs no authentication to reach.
     */
    @Test
    void aDeclaredLengthOverTheCapIsRefusedWithoutReadingTheBody() throws Exception {
        MockHttpServletRequest req = jsonPost("x".repeat(LIMIT + 1));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(res.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        // The chain was never entered: nothing downstream saw the request.
        assertThat(chain.getRequest()).isNull();
    }

    /**
     * The undeclared-length path. A client sending {@code Transfer-Encoding: chunked} declares no
     * length, so a filter that trusted the header would be a limit the client sets. The wrapper
     * counts as the body streams and fails at the byte that crosses the cap — here the downstream
     * handler is the one reading it, which is exactly how a message converter behaves.
     */
    @Test
    void anUndeclaredLengthIsRefusedWhileItStreams() throws Exception {
        // The wrapper is what makes this test the one it claims to be: MockHttpServletRequest
        // always reports the length of the content it holds, so without hiding it the request
        // would be caught by the cheap header check and the counting stream would never run.
        MockHttpServletRequest backing = jsonPost("x".repeat(LIMIT * 4));
        HttpServletRequest undeclared =
                new HttpServletRequestWrapper(backing) {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }

                    @Override
                    public int getContentLength() {
                        return -1;
                    }
                };
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain readsEverything =
                (request, response) -> request.getInputStream().readAllBytes();

        filter().doFilter(undeclared, res, readsEverything);

        assertThat(res.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }

    /**
     * Multipart keeps its own, larger limits. Applying this cap to it would reject transcript
     * uploads that the documented 10MB limit says are fine.
     */
    @Test
    void multipartIsLeftToItsOwnLimits() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/meetings");
        req.setContentType("multipart/form-data; boundary=abc");
        req.setContent("x".repeat(LIMIT * 10).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    /**
     * A Reader is the other way to consume a body, and it used to go uncounted.
     *
     * <p>{@code HttpServletRequestWrapper.getReader()} delegates to the wrapped request, not to
     * this filter's {@code getInputStream()}, so before {@code getReader()} was overridden a
     * handler that took a {@code @RequestBody String} — which is what Spring's {@code
     * StringHttpMessageConverter} produces — read the body straight off the connection with nothing
     * counting it. The cap held for JSON and not for text.
     */
    @Test
    void aBodyReadThroughTheReaderIsCountedToo() throws Exception {
        MockHttpServletRequest backing = jsonPost("x".repeat(LIMIT * 4));
        HttpServletRequest undeclared =
                new HttpServletRequestWrapper(backing) {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }

                    @Override
                    public int getContentLength() {
                        return -1;
                    }
                };
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain readsTheReader =
                (request, response) -> {
                    try (var reader = request.getReader()) {
                        while (reader.read() != -1) {
                            // drain
                        }
                    }
                };

        filter().doFilter(undeclared, res, readsTheReader);

        assertThat(res.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }

    /**
     * The one route whose own validation declares a body larger than the default cap gets its own
     * ceiling. {@code LiveAnalyzeDtos} accepts 500,000 characters of transcript, and in pt-BR each
     * accented character costs two UTF-8 bytes — under the default cap the filter would refuse a
     * body {@code @Size} says is valid.
     */
    @Test
    void liveAnalyzeGetsItsOwnLargerCeiling() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/meetings/live-analyze");
        req.setContentType("application/json");
        req.setContent("x".repeat(LIMIT * 2).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(req, res, chain);

        // Over the default cap, under its own: the default would have refused this.
        assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    /** Its own ceiling is a ceiling, not an exemption. */
    @Test
    void liveAnalyzeIsStillRefusedOverItsOwnCeiling() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/meetings/live-analyze");
        req.setContentType("application/json");
        req.setContent("x".repeat(LIVE_ANALYZE_LIMIT + 1).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(chain.getRequest()).isNull();
    }

    /** A GET carries no body and must not pay for a check that has nothing to look at. */
    @Test
    void bodylessMethodsAreExempt() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/meetings");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }
}
