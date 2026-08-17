package br.com.nora.api.application.ports;

import br.com.nora.api.domain.iam.AttachedPolicy;
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
 * Persistence port for the IAM subdomain. Everything scoped by tenant_id.
 *
 * <p>I went with a single port so as not to multiply adapter files in the MVP — the adapter
 * implements all the operations against the JdbcTemplate / EntityManager.
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

    /** Returns all statements applicable to the user (direct + via groups), already parsed. */
    List<PolicyStatement> collectStatementsForUser(UUID userId, UUID tenantId);

    /**
     * The same statements as {@link #collectStatementsForUser}, still grouped by the policy each
     * one came from. The authorization path does not need the provenance and keeps using the flat
     * overload; the simulator (US43) needs it in order to name the policy that decided.
     */
    List<AttachedPolicy> collectAttachedPoliciesForUser(UUID userId, UUID tenantId);

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
