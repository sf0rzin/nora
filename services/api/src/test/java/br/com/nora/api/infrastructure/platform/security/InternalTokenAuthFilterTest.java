package br.com.nora.api.infrastructure.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Internal token: authenticates with the scope role when it matches; fail-closed (does not
 * authenticate) when absent, wrong, or when the expected token was not configured.
 */
class InternalTokenAuthFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tokenCorreto_autenticaComRoleService() throws Exception {
        Authentication auth = run("s3cr3t", "service", "s3cr3t");
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_SERVICE"));
    }

    @Test
    void escopoAdmin_geraRolePlatformAdmin() throws Exception {
        Authentication auth = run("adm", "admin", "adm");
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
    }

    @Test
    void tokenErrado_naoAutentica() throws Exception {
        assertThat(run("s3cr3t", "service", "errado")).isNull();
    }

    @Test
    void headerAusente_naoAutentica() throws Exception {
        assertThat(run("s3cr3t", "service", null)).isNull();
    }

    @Test
    void tokenEsperadoVazio_failClosed() throws Exception {
        assertThat(run("", "service", "qualquer")).isNull();
    }

    private Authentication run(String expected, String scope, String headerValue)
            throws ServletException, IOException {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(expected, scope);
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (headerValue != null) {
            req.addHeader(InternalTokenAuthFilter.HEADER, headerValue);
        }
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
