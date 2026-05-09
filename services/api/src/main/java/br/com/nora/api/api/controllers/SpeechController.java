package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.speech.SpeechTokenResponse;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.speech.SpeechToken;
import br.com.nora.api.application.speech.SpeechTokenService;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/speech")
public class SpeechController {

    private final SpeechTokenService speechTokenService;

    public SpeechController(SpeechTokenService speechTokenService) {
        this.speechTokenService = speechTokenService;
    }

    @PostMapping("/token")
    public ResponseEntity<SpeechTokenResponse> issueToken(
            @RequestParam(required = false) String region) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        SpeechToken token =
                speechTokenService.issueFor(principal.userId(), principal.tenantId(), region);
        return ResponseEntity.ok(
                new SpeechTokenResponse(token.token(), token.region(), token.expiresAt()));
    }
}
