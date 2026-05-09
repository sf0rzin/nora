package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.iam.AuditEventDto;
import br.com.nora.api.api.dto.iam.CreateGroupRequest;
import br.com.nora.api.api.dto.iam.CreatePolicyRequest;
import br.com.nora.api.api.dto.iam.GroupDto;
import br.com.nora.api.api.dto.iam.PolicyDto;
import br.com.nora.api.api.dto.iam.UpdatePolicyRequest;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.iam.IamException;
import br.com.nora.api.application.iam.IamService;
import br.com.nora.api.domain.iam.IamAuditEvent;
import br.com.nora.api.domain.iam.IamGroup;
import br.com.nora.api.domain.iam.IamPolicy;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints IAM estilo AWS (US35-US40). Exigem permissao {@code iam:*} no recurso {@code
 * nora:tenant/{tenantId}:iam/*}; Root do tenant tem bypass via {@link AuthorizationService}.
 *
 * <p>O resource canonico para verificacao e {@code nora:tenant/{tenantId}:iam/*}.
 */
@RestController
@RequestMapping("/iam")
public class IamController {

    private final IamService iam;
    private final AuthorizationService authz;
    private final ObjectMapper json;

    public IamController(IamService iam, AuthorizationService authz, ObjectMapper json) {
        this.iam = iam;
        this.authz = authz;
        this.json = json;
    }

    private String resource(UUID tenantId) {
        return "nora:tenant/" + tenantId + ":iam/*";
    }

    private void requireIam(String action) {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(p.userId(), p.tenantId(), action, resource(p.tenantId()));
    }

    // ---------- groups ----------

    @GetMapping("/groups")
    public List<GroupDto> listGroups() {
        requireIam("iam:group:read");
        AuthenticatedPrincipal p = CurrentUser.require();
        return iam.listGroups(p.tenantId()).stream().map(IamController::toGroupDto).toList();
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupDto createGroup(@RequestBody CreateGroupRequest body) {
        requireIam("iam:group:create");
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        AuthenticatedPrincipal p = CurrentUser.require();
        IamGroup g = iam.createGroup(p.tenantId(), p.userId(), body.name(), body.description());
        return toGroupDto(g);
    }

    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable("id") UUID id) {
        requireIam("iam:group:delete");
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.deleteGroup(p.tenantId(), p.userId(), id);
    }

    @GetMapping("/groups/{id}/members")
    public List<UUID> listGroupMembers(@PathVariable("id") UUID id) {
        requireIam("iam:group:read");
        AuthenticatedPrincipal p = CurrentUser.require();
        return iam.listGroupMembers(p.tenantId(), id);
    }

    @PostMapping("/groups/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId) {
        requireIam("iam:group:add-member");
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.addUserToGroup(p.tenantId(), p.userId(), id, userId);
    }

    @DeleteMapping("/groups/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId) {
        requireIam("iam:group:remove-member");
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.removeUserFromGroup(p.tenantId(), p.userId(), id, userId);
    }

    // ---------- policies ----------

    @GetMapping("/policies")
    public List<PolicyDto> listPolicies() {
        requireIam("iam:policy:read");
        AuthenticatedPrincipal p = CurrentUser.require();
        return iam.listPolicies(p.tenantId()).stream().map(this::toPolicyDto).toList();
    }

    @GetMapping("/policies/{id}")
    public PolicyDto getPolicy(@PathVariable("id") UUID id) {
        requireIam("iam:policy:read");
        AuthenticatedPrincipal p = CurrentUser.require();
        return toPolicyDto(iam.getPolicy(p.tenantId(), id));
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyDto createPolicy(@RequestBody CreatePolicyRequest body) {
        requireIam("iam:policy:create");
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (body.document() == null) {
            throw IamException.invalidDocument("document required");
        }
        AuthenticatedPrincipal p = CurrentUser.require();
        IamPolicy created =
                iam.createPolicy(
                        p.tenantId(),
                        p.userId(),
                        body.name(),
                        body.description(),
                        body.document().toString());
        return toPolicyDto(created);
    }

    @PutMapping("/policies/{id}")
    public PolicyDto updatePolicy(
            @PathVariable("id") UUID id, @RequestBody UpdatePolicyRequest body) {
        requireIam("iam:policy:update");
        if (body.document() == null) {
            throw IamException.invalidDocument("document required");
        }
        AuthenticatedPrincipal p = CurrentUser.require();
        return toPolicyDto(
                iam.updatePolicyDocument(p.tenantId(), p.userId(), id, body.document().toString()));
    }

    @DeleteMapping("/policies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePolicy(@PathVariable("id") UUID id) {
        requireIam("iam:policy:delete");
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.deletePolicy(p.tenantId(), p.userId(), id);
    }

    // ---------- attachments ----------

    @PostMapping("/groups/{groupId}/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attachToGroup(
            @PathVariable("groupId") UUID groupId, @PathVariable("policyId") UUID policyId) {
        requireIam("iam:attachment:create");
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.attachPolicyToGroup(p.tenantId(), p.userId(), policyId, groupId);
    }

    @DeleteMapping("/groups/{groupId}/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void detachFromGroup(
            @PathVariable("groupId") UUID groupId, @PathVariable("policyId") UUID policyId) {
        requireIam("iam:attachment:delete");
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.detachPolicyFromGroup(p.tenantId(), p.userId(), policyId, groupId);
    }

    @PostMapping("/users/{userId}/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attachToUser(
            @PathVariable("userId") UUID userId, @PathVariable("policyId") UUID policyId) {
        requireIam("iam:attachment:create");
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.attachPolicyToUser(p.tenantId(), p.userId(), policyId, userId);
    }

    @DeleteMapping("/users/{userId}/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void detachFromUser(
            @PathVariable("userId") UUID userId, @PathVariable("policyId") UUID policyId) {
        requireIam("iam:attachment:delete");
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.detachPolicyFromUser(p.tenantId(), p.userId(), policyId, userId);
    }

    // ---------- audit ----------

    @GetMapping("/audit")
    public List<AuditEventDto> listAudit(
            @RequestParam(name = "since", required = false) OffsetDateTime since,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
        requireIam("iam:audit:read");
        AuthenticatedPrincipal p = CurrentUser.require();
        return iam.listAudit(p.tenantId(), since, limit).stream()
                .map(IamController::toAuditDto)
                .toList();
    }

    // ---------- mapping ----------

    private static GroupDto toGroupDto(IamGroup g) {
        return new GroupDto(
                g.id(), g.name(), g.description(), g.createdBy(), g.createdAt(), g.updatedAt());
    }

    private PolicyDto toPolicyDto(IamPolicy p) {
        try {
            JsonNode node = json.valueToTree(p.document());
            return new PolicyDto(
                    p.id(),
                    p.name(),
                    p.description(),
                    node,
                    p.currentVersion(),
                    p.createdBy(),
                    p.createdAt(),
                    p.updatedAt());
        } catch (RuntimeException ex) {
            throw IamException.invalidDocument("could not serialize document");
        }
    }

    private static AuditEventDto toAuditDto(IamAuditEvent e) {
        return new AuditEventDto(
                e.id(),
                e.actorUserId(),
                e.action(),
                e.targetType(),
                e.targetId(),
                e.payload(),
                e.createdAt());
    }
}
