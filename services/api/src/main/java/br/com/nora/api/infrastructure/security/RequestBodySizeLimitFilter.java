package br.com.nora.api.infrastructure.security;

import br.com.nora.api.api.dto.ErrorResponse;
import br.com.nora.api.infrastructure.observability.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the size of a non-multipart request body.
 *
 * <p><b>Why a filter, and not configuration.</b> Nothing in this stack limited a JSON body. {@code
 * spring.servlet.multipart.max-file-size} covers only multipart, which is why the transcript upload
 * was the one well-defended entrance. {@code server.tomcat.max-http-form-post-size} covers only
 * {@code application/x-www-form-urlencoded}, because it bounds the parameter parser rather than the
 * stream. {@code spring.codec.max-in-memory-size} belongs to WebFlux and does nothing in a servlet
 * application. There is no property that bounds a JSON body in Spring MVC, so the bound has to be
 * code, and it has to sit in front of the message converter — by the time Jackson is reading, the
 * bytes are already arriving into the heap.
 *
 * <p><b>Both shapes are checked, and that is the point.</b> A declared {@code Content-Length} over
 * the cap is refused before a single byte of body is read. A request that declares nothing — {@code
 * Transfer-Encoding: chunked} — is refused by counting as it streams, because a limit that trusts a
 * header the client writes is a limit the client sets. The counting wrapper is what makes this a
 * control rather than a hint.
 *
 * <p><b>Public routes are the reason the cap is not merely tidy.</b> {@code POST /auth/signup} and
 * {@code POST /auth/login} take a body before any authentication; {@code POST /iam/policies}
 * deserializes an arbitrary {@code JsonNode} and persists it. This API runs as a single container
 * on one host (ADR 0036), so one body large enough is one JVM. The edge in front of it may impose
 * its own ceiling, but that ceiling lives in another repository and cannot be the reason this one
 * has none.
 *
 * <p>Multipart is skipped: {@code spring.servlet.multipart} already bounds it at 10MB per file and
 * 12MB per request, and applying a second, smaller cap here would reject transcript uploads that
 * the documented limit says are fine.
 *
 * <p>Runs immediately after {@link RequestIdFilter} so a refusal still carries a correlatable
 * {@code traceId}, and before authentication so an unauthenticated flood is refused as early as
 * everything else.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    /**
     * Error code of the refusal. Deliberately the same one {@code MaxUploadSizeExceededException}
     * already maps to, so a client has one code to handle for "your body was too big" whichever
     * limit it crossed.
     */
    private static final String CODE = "PAYLOAD_TOO_LARGE";

    /**
     * The one route whose own validation declares a body larger than the default cap.
     *
     * <p>{@code LiveAnalyzeDtos} accepts a {@code transcriptChunk} of up to 500,000 characters, and
     * the same body carries {@code previousHighlights} (~45KB). In pt-BR every accented character
     * costs two bytes in UTF-8 and the JSON still escapes quotes and newlines, so a request this
     * API declares <em>valid</em> can pass a megabyte — the default cap would refuse a body the
     * validation on the other side of it says is fine, which is the worst kind of limit: one that
     * contradicts the contract rather than bounding it.
     *
     * <p>It gets its own ceiling instead of raising the default because the default is what stands
     * in front of {@code POST /auth/signup} and {@code POST /auth/login}, which take a body before
     * anything authenticates. Widening the cap for everything to accommodate one authenticated
     * route would pay for it at the unauthenticated door.
     */
    private static final String LIVE_ANALYZE_PATH = "/meetings/live-analyze";

    private final long maxBytes;
    private final long maxLiveAnalyzeBytes;
    private final ObjectMapper json;

    public RequestBodySizeLimitFilter(
            @Value("${nora.security.max-request-body-bytes:1048576}") long maxBytes,
            @Value("${nora.security.max-live-analyze-body-bytes:2097152}") long maxLiveAnalyzeBytes,
            ObjectMapper json) {
        this.maxBytes = maxBytes;
        this.maxLiveAnalyzeBytes = maxLiveAnalyzeBytes;
        this.json = json;
    }

    /** The cap that applies to this request. See {@link #LIVE_ANALYZE_PATH}. */
    private long capFor(HttpServletRequest req) {
        return LIVE_ANALYZE_PATH.equals(req.getRequestURI()) ? maxLiveAnalyzeBytes : maxBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (isExempt(req)) {
            chain.doFilter(req, res);
            return;
        }
        long cap = capFor(req);
        // Declared length: refuse before reading anything at all.
        long declared = req.getContentLengthLong();
        if (declared > cap) {
            reject(res);
            return;
        }
        // Undeclared length (chunked): refuse while reading, at the byte that crosses the cap.
        try {
            chain.doFilter(new LimitedBodyRequest(req, cap), res);
        } catch (IOException | ServletException | RuntimeException ex) {
            // The cause chain is walked instead of catching the exception type directly: whoever
            // consumes the body wraps what the stream throws — Jackson's converter turns an
            // IOException into HttpMessageNotReadableException, which the global handler would
            // then report as a 400 "malformed JSON". The body was not malformed, it was too big,
            // and a client told the wrong thing retries the same request.
            if (!causedByBodyTooLarge(ex)) {
                throw ex;
            }
            if (!res.isCommitted()) {
                res.reset();
                reject(res);
            }
        }
    }

    private static boolean causedByBodyTooLarge(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof BodyTooLargeException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * Methods that carry no body, and multipart, which has its own configured limits. The check is
     * on the media type prefix because a multipart content type carries a boundary parameter.
     */
    private static boolean isExempt(HttpServletRequest req) {
        String method = req.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }
        String contentType = req.getContentType();
        return contentType != null
                && contentType
                        .toLowerCase(Locale.ROOT)
                        .startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    private void reject(HttpServletResponse res) throws IOException {
        res.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.setHeader(HttpHeaders.CONNECTION, "close");
        ErrorResponse body =
                new ErrorResponse(
                        CODE,
                        "Request body is too large.",
                        MDC.get(RequestIdFilter.MDC_KEY),
                        Instant.now(),
                        List.of());
        res.getWriter().write(json.writeValueAsString(body));
    }

    /** Signals that the counting stream crossed the cap. Never leaves this filter. */
    private static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("request body exceeds the configured maximum");
        }
    }

    /** Wraps the request so the body is counted as it is consumed, whoever consumes it. */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long limit;

        LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new CountingStream(super.getInputStream(), limit);
        }

        /**
         * Overridden for the same reason as {@link #getInputStream()}, and it is not redundant with
         * it. {@code HttpServletRequestWrapper.getReader()} delegates to the <em>wrapped</em>
         * request rather than to this class's {@code getInputStream()}, so a consumer that asks for
         * a Reader — which is what Spring's {@code StringHttpMessageConverter} does for a
         * {@code @RequestBody String}, and what form parsing does — would read the body straight
         * off the connection with nothing counting it. The cap would then hold for JSON and not for
         * text, which is a cap that depends on which converter the handler happens to use.
         *
         * <p>The charset is the request's own, falling back to UTF-8 the way the servlet spec says
         * to when the request declares none.
         */
        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /**
     * Counts bytes and fails at the one that crosses the cap.
     *
     * <p>Only {@code read()} and {@code read(byte[], int, int)} are overridden because every other
     * read method of {@code InputStream} is defined in terms of them, so the count cannot be
     * bypassed by choosing a different call.
     */
    private static final class CountingStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        CountingStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        private void count(long n) throws IOException {
            if (n <= 0) {
                return;
            }
            read += n;
            if (read > limit) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            count(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
