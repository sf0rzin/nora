package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.stt.SttSessionRequest;
import br.com.nora.api.api.dto.stt.SttSessionResponse;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.application.stt.RealtimeSttSession;
import br.com.nora.api.application.stt.SttSessionService;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Realtime speech-to-text session broker (ADR 0039, ADR 0045).
 *
 * <p>ADR 0039 §Decision 4 named this endpoint {@code POST /stt/realtime-session} as a working name
 * and left the real path open. It is {@code POST /stt/sessions}: the qualifier "realtime" carries
 * no information — realtime streaming is the only kind of session this API mints, and file/batch
 * transcription is explicitly out of scope there — and every other collection in this API is
 * plural with the create verb on the collection.
 *
 * <p>Gated by IAM the same way the deleted {@code SpeechController} was, and for the same reason
 * its Javadoc gave: the credential this mints reaches a paid external provider billed to the
 * tenant, so it is a tenant-wide capability rather than a "self" endpoint, even though the rate
 * limit keys on the individual caller.
 */
@RestController
@RequestMapping("/stt")
public class SttController {

    private final SttSessionService sessions;

    public SttController(SttSessionService sessions) {
        this.sessions = sessions;
    }

    /**
     * Creates one transcription session and returns only its short-lived client secret.
     *
     * <p>One call per stream: {@code commands.rs} opens one session per track, and a reconnection
     * after a mid-meeting drop is a new call, which means a new authorization check and a new
     * telemetry row. The credential is not renewable into a second session by design.
     */
    @PostMapping("/sessions")
    @RequiresPermission(action = "stt:session:create", resource = ResourceType.TENANT)
    public ResponseEntity<SttSessionResponse> openSession(
            @RequestBody(required = false) SttSessionRequest request) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        String language = request == null ? null : request.language();
        RealtimeSttSession session =
                sessions.openSession(principal.userId(), principal.tenantId(), language);
        return ResponseEntity.ok(SttSessionResponse.from(session));
    }
}
