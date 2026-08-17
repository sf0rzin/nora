package br.com.nora.api.application.iam;

import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.iam.IamAuditEvent;
import br.com.nora.api.domain.iam.IamGroup;
import br.com.nora.api.domain.iam.IamPolicy;
import br.com.nora.api.domain.iam.PolicyTemplate;
import br.com.nora.api.domain.iam.PolicyTemplateCatalog;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case of the IAM subdomain. Every mutating operation records an audit event.
 *
 * <p>The authorization itself (checking whether the caller may invoke each method) is the
 * IamController's responsibility via {@link AuthorizationService}. {@link #simulate} is the one
 * method that reaches for that service with a different purpose: to ask what it WOULD decide for
 * another user, without acting on the answer.
 */
@Service
public class IamService {

    private final IamRepository iam;
    private final UserRepository users;
    private final AuthorizationService authz;

    public IamService(IamRepository iam, UserRepository users, AuthorizationService authz) {
        this.iam = iam;
        this.users = users;
        this.authz = authz;
    }

    // ========== groups ==========

    @Transactional
    public IamGroup createGroup(UUID tenantId, UUID actor, String name, String description) {
        try {
            IamGroup g = iam.createGroup(tenantId, name, description, actor);
            iam.recordAudit(
                    tenantId, actor, "iam:group:create", "GROUP", g.id(), Map.of("name", name));
            return g;
        } catch (DataIntegrityViolationException ex) {
            throw IamException.nameTaken(name);
        }
    }

    @Transactional(readOnly = true)
    public List<IamGroup> listGroups(UUID tenantId) {
        return iam.listGroups(tenantId);
    }

    @Transactional
    public void deleteGroup(UUID tenantId, UUID actor, UUID groupId) {
        IamGroup g = iam.findGroup(groupId, tenantId).orElseThrow(IamException::groupNotFound);
        iam.deleteGroup(groupId, tenantId);
        iam.recordAudit(
                tenantId, actor, "iam:group:delete", "GROUP", groupId, Map.of("name", g.name()));
    }

    @Transactional
    public void addUserToGroup(UUID tenantId, UUID actor, UUID groupId, UUID userId) {
        iam.findGroup(groupId, tenantId).orElseThrow(IamException::groupNotFound);
        // The group is validated against the tenant; the userId was not validated against
        // anything. What blocks a user from another tenant is the composite FK of V027 -- here
        // we only translate the violation into a domain error, in the same format createGroup
        // already uses for a duplicate name.
        try {
            iam.addUserToGroup(userId, groupId, tenantId, actor);
        } catch (DataIntegrityViolationException ex) {
            throw IamException.userNotInTenant();
        }
        iam.recordAudit(
                tenantId,
                actor,
                "iam:group:add-member",
                "MEMBERSHIP",
                groupId,
                Map.of("userId", userId.toString()));
    }

    @Transactional
    public void removeUserFromGroup(UUID tenantId, UUID actor, UUID groupId, UUID userId) {
        iam.removeUserFromGroup(userId, groupId, tenantId);
        iam.recordAudit(
                tenantId,
                actor,
                "iam:group:remove-member",
                "MEMBERSHIP",
                groupId,
                Map.of("userId", userId.toString()));
    }

    @Transactional(readOnly = true)
    public List<UUID> listGroupMembers(UUID tenantId, UUID groupId) {
        iam.findGroup(groupId, tenantId).orElseThrow(IamException::groupNotFound);
        return iam.listGroupMembers(groupId, tenantId);
    }

    // ========== policies ==========

    @Transactional
    public IamPolicy createPolicy(
            UUID tenantId, UUID actor, String name, String description, String documentJson) {
        try {
            IamPolicy p = iam.createPolicy(tenantId, name, description, documentJson, actor);
            iam.recordAudit(
                    tenantId,
                    actor,
                    "iam:policy:create",
                    "POLICY",
                    p.id(),
                    Map.of("name", name, "version", p.currentVersion()));
            return p;
        } catch (DataIntegrityViolationException ex) {
            throw IamException.nameTaken(name);
        } catch (IllegalArgumentException ex) {
            throw IamException.invalidDocument(ex.getMessage());
        }
    }

    @Transactional
    public IamPolicy updatePolicyDocument(
            UUID tenantId, UUID actor, UUID policyId, String documentJson) {
        iam.findPolicy(policyId, tenantId).orElseThrow(IamException::policyNotFound);
        try {
            IamPolicy p = iam.updatePolicyDocument(policyId, tenantId, documentJson, actor);
            iam.recordAudit(
                    tenantId,
                    actor,
                    "iam:policy:update",
                    "POLICY",
                    policyId,
                    Map.of("version", p.currentVersion()));
            return p;
        } catch (IllegalArgumentException ex) {
            throw IamException.invalidDocument(ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<IamPolicy> listPolicies(UUID tenantId) {
        return iam.listPolicies(tenantId);
    }

    @Transactional(readOnly = true)
    public IamPolicy getPolicy(UUID tenantId, UUID policyId) {
        return iam.findPolicy(policyId, tenantId).orElseThrow(IamException::policyNotFound);
    }

    @Transactional
    public void deletePolicy(UUID tenantId, UUID actor, UUID policyId) {
        IamPolicy p = iam.findPolicy(policyId, tenantId).orElseThrow(IamException::policyNotFound);
        iam.deletePolicy(policyId, tenantId);
        iam.recordAudit(
                tenantId, actor, "iam:policy:delete", "POLICY", policyId, Map.of("name", p.name()));
    }

    // ========== templates ==========

    /**
     * US41 — the built-in templates, with their ARNs bound to {@code tenantId}. No query and no
     * audit event: the catalogue is a constant of the build, reading it changes nothing, and it
     * says nothing about this tenant that the caller's own token does not already say.
     *
     * <p>The result is a starting document, not a policy. It becomes one only when the caller posts
     * it to {@code POST /iam/policies}, through {@link #createPolicy} — the same method a
     * hand-written document goes through, so nothing downstream can tell the two apart.
     */
    public List<PolicyTemplate> listPolicyTemplates(UUID tenantId) {
        return PolicyTemplateCatalog.forTenant(tenantId);
    }

    // ========== attachments ==========

    @Transactional
    public void attachPolicyToGroup(UUID tenantId, UUID actor, UUID policyId, UUID groupId) {
        iam.findGroup(groupId, tenantId).orElseThrow(IamException::groupNotFound);
        iam.findPolicy(policyId, tenantId).orElseThrow(IamException::policyNotFound);
        iam.attachPolicyToGroup(policyId, groupId, tenantId, actor);
        iam.recordAudit(
                tenantId,
                actor,
                "iam:attachment:group",
                "ATTACHMENT",
                groupId,
                Map.of("policyId", policyId.toString()));
    }

    @Transactional
    public void detachPolicyFromGroup(UUID tenantId, UUID actor, UUID policyId, UUID groupId) {
        iam.detachPolicyFromGroup(policyId, groupId, tenantId);
        iam.recordAudit(
                tenantId,
                actor,
                "iam:detachment:group",
                "ATTACHMENT",
                groupId,
                Map.of("policyId", policyId.toString()));
    }

    @Transactional
    public void attachPolicyToUser(UUID tenantId, UUID actor, UUID policyId, UUID userId) {
        iam.findPolicy(policyId, tenantId).orElseThrow(IamException::policyNotFound);
        // See note in addUserToGroup: userId is only bound to the tenant by V027's composite FK.
        try {
            iam.attachPolicyToUser(policyId, userId, tenantId, actor);
        } catch (DataIntegrityViolationException ex) {
            throw IamException.userNotInTenant();
        }
        iam.recordAudit(
                tenantId,
                actor,
                "iam:attachment:user",
                "ATTACHMENT",
                userId,
                Map.of("policyId", policyId.toString()));
    }

    @Transactional
    public void detachPolicyFromUser(UUID tenantId, UUID actor, UUID policyId, UUID userId) {
        iam.detachPolicyFromUser(policyId, userId, tenantId);
        iam.recordAudit(
                tenantId,
                actor,
                "iam:detachment:user",
                "ATTACHMENT",
                userId,
                Map.of("policyId", policyId.toString()));
    }

    // ========== simulation ==========

    /**
     * US43 — "can this user do this action on this resource?", answered with the reason and without
     * performing the action. Debugging a policy used to mean attempting the real operation and
     * reading the 403 back.
     *
     * <p>The subject is resolved inside the CALLER'S tenant before anything is evaluated, and a
     * user from elsewhere is a 404. Skipping that check would not throw an error: it would answer a
     * perfectly formed "deny, no statements", which is a true sentence about a user the caller must
     * not learn exists.
     *
     * <p>Read-only, and deliberately not audited: the IAM trail records mutations, and a simulation
     * changes nothing.
     */
    @Transactional(readOnly = true)
    public PolicyExplanation simulate(
            UUID tenantId,
            UUID userId,
            String action,
            String resource,
            Map<String, String> context) {
        users.findById(userId)
                .filter(u -> u.tenantId().equals(tenantId))
                .orElseThrow(IamException::userNotFound);
        return authz.explain(userId, tenantId, action, resource, context);
    }

    // ========== audit ==========

    @Transactional(readOnly = true)
    public List<IamAuditEvent> listAudit(UUID tenantId, OffsetDateTime since, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return iam.listAudit(tenantId, since, safeLimit);
    }
}
