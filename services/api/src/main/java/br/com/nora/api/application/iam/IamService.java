package br.com.nora.api.application.iam;

import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.iam.IamAuditEvent;
import br.com.nora.api.domain.iam.IamGroup;
import br.com.nora.api.domain.iam.IamPolicy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caso de uso do subdominio IAM. Toda operacao mutante registra um evento de auditoria.
 *
 * <p>A autorizacao em si (verificar se o caller pode invocar cada metodo) e responsabilidade do
 * IamController via {@link AuthorizationService}.
 */
@Service
public class IamService {

    private final IamRepository iam;
    private final UserRepository users;

    public IamService(IamRepository iam, UserRepository users) {
        this.iam = iam;
        this.users = users;
    }

    // ========== users (directory) ==========

    /** Lista os usuarios do tenant para gestao IAM (pickers de membro / anexo de policy). */
    @Transactional(readOnly = true)
    public List<UserRepository.TenantUserSummary> listTenantUsers(UUID tenantId) {
        return users.listByTenant(tenantId);
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
        iam.addUserToGroup(userId, groupId, tenantId, actor);
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
        iam.attachPolicyToUser(policyId, userId, tenantId, actor);
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

    // ========== audit ==========

    @Transactional(readOnly = true)
    public List<IamAuditEvent> listAudit(UUID tenantId, OffsetDateTime since, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return iam.listAudit(tenantId, since, safeLimit);
    }
}
