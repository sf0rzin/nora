package br.com.nora.api.api.controllers;

import br.com.nora.api.api.security.AuthorizationNotRequired;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.ResourceArns;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.privacy.PrivacyService;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Privacy/LGPD endpoints (ADR 0029). Scoped by the tenant from the JWT.
 *
 * <p>Right to be forgotten: PERMANENTLY deletes a meeting (physical hard-delete, distinct from the
 * default soft-delete of ADR 0021). The database cascade purges transcript ({@code raw_text} =
 * PII), participants, tags and analyses.
 */
@RestController
@RequestMapping("/privacy")
public class PrivacyController {

    private final PrivacyService privacy;
    private final AuthorizationService authz;

    public PrivacyController(PrivacyService privacy, AuthorizationService authz) {
        this.privacy = privacy;
        this.authz = authz;
    }

    private static String meetingResource(UUID tenantId, UUID meetingId) {
        return ResourceArns.meeting(tenantId, meetingId);
    }

    /**
     * The IAM action gating the permanent erasure. Its own action, and that is the point of this
     * constant existing at all.
     *
     * <p>This used to be {@code meeting:update} — the same string that gates renaming a goal. Any
     * policy granting "may edit the meeting" therefore also granted an irreversible physical delete
     * of the transcript, participants, tags and analyses, with no bin and no undo. The repository
     * had already made this exact call three times in the other direction and written down why:
     * {@code workflow:test} is separate because running a flow fires real external actions, {@code
     * iam:policy:simulate} is separate because it reveals the attachment graph, and {@code
     * iam:boundary:set}/{@code delete} are separate so bounding a team can be delegated without
     * delegating unbounding. The most destructive operation in the API was the one that had not
     * received the same treatment.
     *
     * <p>It is deliberately in NO built-in policy template, {@code meeting-analyst} included: a
     * template is a starting point handed to someone who has not yet thought about it, and the
     * right default for permanent erasure is that it must be granted on purpose. The tenant Root
     * keeps it through the bypass, and {@code meeting:*} picks it up like any other action — so an
     * existing wildcard grant is unaffected, while an existing {@code meeting:update} grant loses a
     * power it was never meant to carry.
     *
     * <p>Distinct from {@code meeting:delete} ({@code DELETE /meetings/{id}}), which is the
     * REVERSIBLE removal. The two are different powers over different outcomes and must be
     * grantable apart.
     */
    private static final String ERASE_ACTION = "meeting:erase";

    /**
     * Permanently deletes the meeting and all the linked PII. 204 on success; 404 if it does not
     * exist in the tenant (does not leak cross-tenant existence). Requires {@link #ERASE_ACTION}.
     *
     * <p>The check runs inside the service transaction, on the resolved meeting, so it sees the
     * meeting's attributes. It used to authorize on the id alone, with an empty condition context:
     * every other meeting mutation ({@code MeetingsController} get / putGoal / deleteGoal /
     * reprocess) passes {@code m.attributes()}, and without them a condition never resolves — which
     * is fail-closed for an Allow but drops an attribute-scoped Deny, here over a hard-delete.
     */
    @DeleteMapping("/meetings/{id}")
    @AuthorizationNotRequired(reason = "Body: authorizes on the loaded meeting's attributes.")
    public ResponseEntity<Void> eraseMeeting(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        privacy.eraseMeeting(
                id,
                principal.tenantId(),
                m ->
                        authz.require(
                                principal.userId(),
                                principal.tenantId(),
                                ERASE_ACTION,
                                meetingResource(principal.tenantId(), m.id()),
                                m.attributes()));
        return ResponseEntity.noContent().build();
    }
}
