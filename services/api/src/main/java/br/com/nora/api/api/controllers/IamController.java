package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.iam.AuditEventDto;
import br.com.nora.api.api.dto.iam.CreateGroupRequest;
import br.com.nora.api.api.dto.iam.CreatePolicyRequest;
import br.com.nora.api.api.dto.iam.GroupDto;
import br.com.nora.api.api.dto.iam.PolicyDto;
import br.com.nora.api.api.dto.iam.SimulatePolicyRequest;
import br.com.nora.api.api.dto.iam.SimulatePolicyResponse;
import br.com.nora.api.api.dto.iam.UpdatePolicyRequest;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.application.iam.IamException;
import br.com.nora.api.application.iam.IamService;
import br.com.nora.api.application.iam.PolicyExplanation;
import br.com.nora.api.domain.iam.IamAuditEvent;
import br.com.nora.api.domain.iam.IamGroup;
import br.com.nora.api.domain.iam.IamPolicy;
import br.com.nora.api.domain.iam.PolicyDecision;
import br.com.nora.api.domain.iam.PolicyStatement;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * AWS-style IAM endpoints (US35-US40). They require the {@code iam:*} permission on the {@code
 * nora:tenant/{tenantId}:iam/*} resource; the tenant Root has a bypass in the authorization
 * service.
 *
 * <p>The canonical resource for the check is {@code nora:tenant/{tenantId}:iam/*} — the {@code IAM}
 * resource type of the annotation. Each handler used to build that ARN by hand and call {@code
 * authz.require} as its first statement; the declaration is now on the method, evaluated by the
 * interceptor before the body runs (#51). None of these checks reads resource attributes, so the
 * decision is identical.
 */
@RestController
@RequestMapping("/iam")
public class IamController {

    private final IamService iam;
    private final ObjectMapper json;

    public IamController(IamService iam, ObjectMapper json) {
        this.iam = iam;
        this.json = json;
    }

    // ---------- groups ----------

    @GetMapping("/groups")
    @RequiresPermission(action = "iam:group:read", resource = ResourceType.IAM)
    public List<GroupDto> listGroups() {
        AuthenticatedPrincipal p = CurrentUser.require();
        return iam.listGroups(p.tenantId()).stream().map(IamController::toGroupDto).toList();
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(action = "iam:group:create", resource = ResourceType.IAM)
    public GroupDto createGroup(@RequestBody CreateGroupRequest body) {
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        AuthenticatedPrincipal p = CurrentUser.require();
        IamGroup g = iam.createGroup(p.tenantId(), p.userId(), body.name(), body.description());
        return toGroupDto(g);
    }

    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(action = "iam:group:delete", resource = ResourceType.IAM)
    public void deleteGroup(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.deleteGroup(p.tenantId(), p.userId(), id);
    }

    @GetMapping("/groups/{id}/members")
    @RequiresPermission(action = "iam:group:read", resource = ResourceType.IAM)
    public List<UUID> listGroupMembers(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal p = CurrentUser.require();
        return iam.listGroupMembers(p.tenantId(), id);
    }

    @PostMapping("/groups/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(action = "iam:group:add-member", resource = ResourceType.IAM)
    public void addMember(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId) {
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.addUserToGroup(p.tenantId(), p.userId(), id, userId);
    }

    @DeleteMapping("/groups/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(action = "iam:group:remove-member", resource = ResourceType.IAM)
    public void removeMember(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId) {
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.removeUserFromGroup(p.tenantId(), p.userId(), id, userId);
    }

    // ---------- policies ----------

    @GetMapping("/policies")
    @RequiresPermission(action = "iam:policy:read", resource = ResourceType.IAM)
    public List<PolicyDto> listPolicies() {
        AuthenticatedPrincipal p = CurrentUser.require();
        return iam.listPolicies(p.tenantId()).stream().map(this::toPolicyDto).toList();
    }

    @GetMapping("/policies/{id}")
    @RequiresPermission(action = "iam:policy:read", resource = ResourceType.IAM)
    public PolicyDto getPolicy(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal p = CurrentUser.require();
        return toPolicyDto(iam.getPolicy(p.tenantId(), id));
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(action = "iam:policy:create", resource = ResourceType.IAM)
    public PolicyDto createPolicy(@RequestBody CreatePolicyRequest body) {
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
    @RequiresPermission(action = "iam:policy:update", resource = ResourceType.IAM)
    public PolicyDto updatePolicy(
            @PathVariable("id") UUID id, @RequestBody UpdatePolicyRequest body) {
        if (body.document() == null) {
            throw IamException.invalidDocument("document required");
        }
        AuthenticatedPrincipal p = CurrentUser.require();
        return toPolicyDto(
                iam.updatePolicyDocument(p.tenantId(), p.userId(), id, body.document().toString()));
    }

    @DeleteMapping("/policies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(action = "iam:policy:delete", resource = ResourceType.IAM)
    public void deletePolicy(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.deletePolicy(p.tenantId(), p.userId(), id);
    }

    // ---------- attachments ----------

    @PostMapping("/groups/{groupId}/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(action = "iam:attachment:create", resource = ResourceType.IAM)
    public void attachToGroup(
            @PathVariable("groupId") UUID groupId, @PathVariable("policyId") UUID policyId) {
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.attachPolicyToGroup(p.tenantId(), p.userId(), policyId, groupId);
    }

    @DeleteMapping("/groups/{groupId}/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(action = "iam:attachment:delete", resource = ResourceType.IAM)
    public void detachFromGroup(
            @PathVariable("groupId") UUID groupId, @PathVariable("policyId") UUID policyId) {
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.detachPolicyFromGroup(p.tenantId(), p.userId(), policyId, groupId);
    }

    @PostMapping("/users/{userId}/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(action = "iam:attachment:create", resource = ResourceType.IAM)
    public void attachToUser(
            @PathVariable("userId") UUID userId, @PathVariable("policyId") UUID policyId) {
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.attachPolicyToUser(p.tenantId(), p.userId(), policyId, userId);
    }

    @DeleteMapping("/users/{userId}/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(action = "iam:attachment:delete", resource = ResourceType.IAM)
    public void detachFromUser(
            @PathVariable("userId") UUID userId, @PathVariable("policyId") UUID policyId) {
        AuthenticatedPrincipal p = CurrentUser.require();
        iam.detachPolicyFromUser(p.tenantId(), p.userId(), policyId, userId);
    }

    // ---------- audit ----------

    @GetMapping("/audit")
    @RequiresPermission(action = "iam:audit:read", resource = ResourceType.IAM)
    public List<AuditEventDto> listAudit(
            @RequestParam(name = "since", required = false) OffsetDateTime since,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
        AuthenticatedPrincipal p = CurrentUser.require();
        return iam.listAudit(p.tenantId(), since, limit).stream()
                .map(IamController::toAuditDto)
                .toList();
    }

    // ---------- simulation ----------

    /**
     * US43 — answers "can this user do this action on this resource?" without performing the
     * action, and names the statement that decided.
     *
     * <p>Gated by its own {@code iam:policy:simulate} rather than by {@code iam:policy:read}: the
     * answer is derived from which policies are ATTACHED to which user, and no endpoint of this API
     * exposes that graph, so reusing the policy-read grant would silently widen what its holders
     * can learn. Being a separate action it is deny-by-default — an existing grant of {@code
     * iam:policy:read} does not acquire it, while the {@code iam:*} shape admin policies use picks
     * it up on its own.
     */
    @PostMapping("/simulate")
    @RequiresPermission(action = "iam:policy:simulate", resource = ResourceType.IAM)
    public SimulatePolicyResponse simulate(@RequestBody SimulatePolicyRequest body) {
        if (body.userId() == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (body.action() == null || body.action().isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        if (body.resource() == null || body.resource().isBlank()) {
            throw new IllegalArgumentException("resource is required");
        }
        AuthenticatedPrincipal p = CurrentUser.require();
        PolicyExplanation explained =
                iam.simulate(
                        p.tenantId(),
                        body.userId(),
                        body.action(),
                        body.resource(),
                        body.context() == null ? Map.of() : body.context());
        return toSimulationDto(body, explained);
    }

    // ---------- mapping ----------

    private static SimulatePolicyResponse toSimulationDto(
            SimulatePolicyRequest req, PolicyExplanation explained) {
        PolicyDecision decision = explained.decision();
        return new SimulatePolicyResponse(
                req.userId(),
                req.action(),
                req.resource(),
                decision.allowed(),
                decision.reason().name(),
                explained.policyId(),
                explained.policyName(),
                explained.statementIndex(),
                toStatementDto(decision.statement()),
                explained.statementsEvaluated());
    }

    private static SimulatePolicyResponse.Statement toStatementDto(PolicyStatement s) {
        if (s == null) {
            return null;
        }
        return new SimulatePolicyResponse.Statement(
                s.effect().name(), s.actions(), s.resources(), s.condition());
    }

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
