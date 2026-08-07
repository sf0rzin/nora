package br.com.nora.api.api.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.nora.api.application.speech.SpeechException;
import br.com.nora.api.application.speech.SpeechToken;
import br.com.nora.api.application.speech.SpeechTokenService;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

// @WebMvcTest ja inclui @ControllerAdvice no slice — o GlobalExceptionHandler entra sozinho.
@WebMvcTest(SpeechController.class)
@AutoConfigureMockMvc(addFilters = false)
class SpeechControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private SpeechTokenService speechTokenService;
    @MockBean private JjwtJwtIssuer jwtIssuer;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        JjwtJwtIssuer.AuthenticatedPrincipal principal =
                new JjwtJwtIssuer.AuthenticatedPrincipal(
                        userId, tenantId, "user@nora.ai", List.of("USER"));
        AbstractAuthenticationToken auth =
                new AbstractAuthenticationToken(List.of(new SimpleGrantedAuthority("ROLE_USER"))) {
                    @Override
                    public Object getCredentials() {
                        return "fake-jwt";
                    }

                    @Override
                    public Object getPrincipal() {
                        return principal;
                    }
                };
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void shouldReturnTokenWhenAuthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SpeechToken token =
                new SpeechToken(
                        "fake-jwt-token", "brazilsouth", Instant.parse("2026-05-08T12:09:00Z"));

        when(speechTokenService.issueFor(any(), any(), eq((String) null))).thenReturn(token);

        mockMvc.perform(post("/speech/token").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("fake-jwt-token"))
                .andExpect(jsonPath("$.region").value("brazilsouth"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void shouldReturnTokenWithRegionParam() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SpeechToken token =
                new SpeechToken("fake-jwt-token", "eastus", Instant.parse("2026-05-08T12:09:00Z"));

        when(speechTokenService.issueFor(any(), any(), eq("eastus"))).thenReturn(token);

        mockMvc.perform(
                        post("/speech/token")
                                .param("region", "eastus")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("eastus"));
    }

    /**
     * Com nora.speech.provider=local o broker recusa. Tem que sair 410 GONE — nao 500 — para o
     * desktop antigo tratar como sinal terminal e cair no STT local em vez de entrar em retry.
     */
    @Test
    void shouldReturn410WhenProviderIsLocal() throws Exception {
        when(speechTokenService.issueFor(any(), any(), eq((String) null)))
                .thenThrow(new SpeechException.ProviderGone());

        mockMvc.perform(post("/speech/token").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SPEECH_PROVIDER_GONE"));
    }
}
