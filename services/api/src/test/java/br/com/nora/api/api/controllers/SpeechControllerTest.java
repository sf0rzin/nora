package br.com.nora.api.api.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.nora.api.application.speech.SpeechToken;
import br.com.nora.api.application.speech.SpeechTokenService;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

@WebMvcTest(SpeechController.class)
@AutoConfigureMockMvc(addFilters = false)
class SpeechControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private SpeechTokenService speechTokenService;

    @Test
    void shouldReturnTokenWhenAuthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SpeechToken token = new SpeechToken("fake-jwt-token", "brazilsouth", Instant.parse("2026-05-08T12:09:00Z"));

        when(speechTokenService.issueFor(any(), eq(tenantId), eq((String) null))).thenReturn(token);

        mockMvc.perform(
                        post("/speech/token")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("fake-jwt-token"))
                .andExpect(jsonPath("$.region").value("brazilsouth"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void shouldReturnTokenWithRegionParam() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SpeechToken token = new SpeechToken("fake-jwt-token", "eastus", Instant.parse("2026-05-08T12:09:00Z"));

        when(speechTokenService.issueFor(any(), any(), eq("eastus"))).thenReturn(token);

        mockMvc.perform(
                        post("/speech/token")
                                .param("region", "eastus")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("eastus"));
    }
}
