package br.com.nora.api.application.ports;

import br.com.nora.api.domain.iam.IamAuditEvent;
import br.com.nora.api.domain.iam.IamGroup;
import br.com.nora.api.domain.iam.IamPolicy;
import br.com.nora.api.domain.iam.PolicyStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistencia para o subdominio IAM. Tudo escopado por tenant_id.
 *
 * <p>Optei por uma porta unica para nao multiplicar arquivos de adapter no MVP — o adapter
 * implementa todas as operacoes contra o JdbcTemplate / EntityManager.
 */
public interface IamRepository {

    // ----- groups -----
    IamGroup createGroup(UUID tenantId, String name, String description, UUID createdBy);

    Optional<IamGroup> findGroup(UUID groupId, UUID tenantId);

    List<IamGroup> listGroups(UUID tenantId);

    void deleteGroup(UUID groupId, UUID tenantId);

    // ----- group membership -----
    void addUserToGroup(UUID userId, UUID groupId, UUID tenantId, UUID attachedBy);

    void removeUserFromGroup(UUID userId, UUID groupId, UUID tenantId);

    List<UUID> listGroupMembers(UUID groupId, UUID tenantId);

    List<UUID> listUserGroups(UUID userId, UUID tenantId);

    // ----- policies -----
    IamPolicy createPolicy(
            UUID tenantId, String name, String description, String documentJson, UUID createdBy);

    IamPolicy updatePolicyDocument(
            UUID policyId, UUID tenantId, String documentJson, UUID updatedBy);

    Optional<IamPolicy> findPolicy(UUID policyId, UUID tenantId);

    List<IamPolicy> listPolicies(UUID tenantId);

    void deletePolicy(UUID policyId, UUID tenantId);

    // ----- attachments -----
    void attachPolicyToGroup(UUID policyId, UUID groupId, UUID tenantId, UUID attachedBy);

    void detachPolicyFromGroup(UUID policyId, UUID groupId, UUID tenantId);

    void attachPolicyToUser(UUID policyId, UUID userId, UUID tenantId, UUID attachedBy);

    void detachPolicyFromUser(UUID policyId, UUID userId, UUID tenantId);

    /** Retorna todos os statements aplicaveis ao usuario (diretos + via grupos), ja parseados. */
    List<PolicyStatement> collectStatementsForUser(UUID userId, UUID tenantId);

    // ----- audit -----
    void recordAudit(
            UUID tenantId,
            UUID actorUserId,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> payload);

    List<IamAuditEvent> listAudit(UUID tenantId, OffsetDateTime since, int limit);
}
