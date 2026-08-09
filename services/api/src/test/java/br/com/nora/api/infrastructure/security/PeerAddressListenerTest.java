package br.com.nora.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Proves the listener actually runs, against a real embedded Tomcat over a real socket.
 *
 * <p>This is the part that cannot be checked by reading: {@link PeerAddressListener} only earns its
 * place if the container really invokes it, and earlier than {@code ForwardedHeaderFilter}. A
 * listener that is never registered fails silently — {@code peerAddress()} falls back to {@code
 * getRemoteAddr()}, which by then is whatever the caller wrote in {@code X-Forwarded-For}, and the
 * rate limiter goes back to letting an attacker pick their own bucket. Nothing throws, no test
 * turns red, the control is simply not there. That is the exact failure this file exists to catch,
 * so it is checked end to end rather than argued for in a comment.
 *
 * <p>The context is built by hand with only four beans — no datasource, no security, no JPA — so it
 * runs in CI without Docker, unlike the {@code *IntegrationTest} classes.
 */
class PeerAddressListenerTest {

    @Test
    void theContainerInvokesTheListenerBeforeForwardedHeaderFilterRewritesTheAddress()
            throws Exception {
        try (AnnotationConfigServletWebServerApplicationContext context =
                new AnnotationConfigServletWebServerApplicationContext(Setup.class)) {

            int port = context.getWebServer().getPort();
            HttpResponse<String> response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:" + port + "/probe"))
                                            .header("X-Forwarded-For", "203.0.113.9")
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            // The filter did its job: by the time the servlet runs, getRemoteAddr() is the value
            // the CALLER chose. This half is the counter-proof -- without it, the assertion below
            // would pass just as well on a machine where the filter never ran.
            assertThat(response.body())
                    .as("ForwardedHeaderFilter must have rewritten getRemoteAddr()")
                    .contains("remoteAddr=203.0.113.9");
            // And the listener still holds the address of whoever actually opened the socket.
            assertThat(response.body())
                    .as("the listener must have captured the peer before the chain ran")
                    .contains("peer=127.0.0.1");
        }
    }

    @Test
    void theListenerIsAComponentSoTheApplicationContextPicksItUp() {
        // The test above registers the bean explicitly, which is what lets it run without the
        // full application context -- so it cannot see the stereotype disappearing. Production
        // registration depends on the component scan finding this class, and losing the
        // annotation is a silent way to remove the control.
        assertThat(PeerAddressListener.class.isAnnotationPresent(Component.class)).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    static class Setup {

        @Bean
        ServletWebServerFactory webServerFactory() {
            return new TomcatServletWebServerFactory(0);
        }

        @Bean
        PeerAddressListener peerAddressListener() {
            return new PeerAddressListener();
        }

        /**
         * Registered exactly as Spring Boot does for {@code forward-headers-strategy: framework}.
         */
        @Bean
        FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
            FilterRegistrationBean<ForwardedHeaderFilter> registration =
                    new FilterRegistrationBean<>(new ForwardedHeaderFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }

        @Bean
        ServletRegistrationBean<HttpServlet> probeServlet() {
            HttpServlet servlet =
                    new HttpServlet() {
                        @Override
                        protected void doGet(HttpServletRequest request, HttpServletResponse resp)
                                throws IOException {
                            resp.getWriter()
                                    .write(
                                            "remoteAddr="
                                                    + request.getRemoteAddr()
                                                    + ";peer="
                                                    + PeerAddressListener.peerAddress(request));
                        }
                    };
            return new ServletRegistrationBean<>(servlet, "/probe");
        }
    }
}
