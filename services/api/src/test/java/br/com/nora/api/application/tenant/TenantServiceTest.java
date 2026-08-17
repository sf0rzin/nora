package br.com.nora.api.application.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.domain.iam.AttachedPolicy;
import br.com.nora.api.domain.iam.IamAuditEvent;
import br.com.nora.api.domain.iam.IamGroup;
import br.com.nora.api.domain.iam.IamPolicy;
import br.com.nora.api.domain.iam.PermissionBoundary;
import br.com.nora.api.domain.iam.PolicyStatement;
import br.com.nora.api.domain.tenant.Tenant;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for TenantService: validation, normalization, audit (US32). */
class TenantServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final Instant fixedNow = Instant.parse("2026-05-11T13:42:11Z");

    private InMemoryTenantRepo tenants;
    private RecordingIamRepo iam;
    private TenantService service;

    @BeforeEach
    void setUp() {
        tenants = new InMemoryTenantRepo();
        iam = new RecordingIamRepo();
        Clock clock = () -> fixedNow;
        service = new TenantService(tenants, iam, clock);

        Instant past = Instant.parse("2026-04-01T00:00:00Z");
        tenants.save(
                new Tenant(
                        tenantId,
                        "Acme",
                        "acme",
                        Tenant.Status.ACTIVE,
                        Tenant.Plan.ENTERPRISE,
                        null,
                        past,
                        past));
    }

    @Test
    void getReturnsCurrentDomain_nullByDefault() {
        assertThat(service.getAllowedEmailDomain(tenantId)).isNull();
    }

    @Test
    void updateSetsValidDomain_normalizedLowercaseAndTrimmed_andEmitsAudit() {
        Tenant saved = service.updateAllowedEmailDomain(tenantId, "  ACME.com  ", actor);

        assertThat(saved.allowedEmailDomain()).isEqualTo("acme.com");
        assertThat(saved.updatedAt()).isEqualTo(fixedNow);
        assertThat(service.getAllowedEmailDomain(tenantId)).isEqualTo("acme.com");

        assertThat(iam.audits).hasSize(1);
        RecordedAudit rec = iam.audits.get(0);
        assertThat(rec.action).isEqualTo("tenant.domain.updated");
        assertThat(rec.tenantId).isEqualTo(tenantId);
        assertThat(rec.actor).isEqualTo(actor);
        assertThat(rec.targetType).isEqualTo("TENANT");
        assertThat(rec.targetId).isEqualTo(tenantId);
        assertThat(rec.payload).containsEntry("oldDomain", null);
        assertThat(rec.payload).containsEntry("newDomain", "acme.com");
    }

    @Test
    void updateAcceptsSubdomainsAndHyphens() {
        Tenant saved = service.updateAllowedEmailDomain(tenantId, "sub.acme-corp.co.uk", actor);
        assertThat(saved.allowedEmailDomain()).isEqualTo("sub.acme-corp.co.uk");
    }

    @Test
    void updateWithNull_clearsRestriction_andEmitsAuditWithBothNulls() {
        // pre-condition: set a value.
        service.updateAllowedEmailDomain(tenantId, "acme.com", actor);
        iam.audits.clear();

        Tenant saved = service.updateAllowedEmailDomain(tenantId, null, actor);

        assertThat(saved.allowedEmailDomain()).isNull();
        assertThat(iam.audits).hasSize(1);
        assertThat(iam.audits.get(0).payload).containsEntry("oldDomain", "acme.com");
        assertThat(iam.audits.get(0).payload).containsEntry("newDomain", null);
    }

    @Test
    void updateWithBlankString_clearsRestriction() {
        service.updateAllowedEmailDomain(tenantId, "acme.com", actor);
        Tenant saved = service.updateAllowedEmailDomain(tenantId, "   ", actor);
        assertThat(saved.allowedEmailDomain()).isNull();
    }

    @Test
    void updateRejectsLeadingAtSign() {
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(tenantId, "@acme.com", actor))
                .isInstanceOf(TenantException.InvalidDomain.class);
        assertThat(iam.audits).isEmpty();
    }

    @Test
    void updateRejectsNoTld() {
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(tenantId, "acme", actor))
                .isInstanceOf(TenantException.InvalidDomain.class);
    }

    @Test
    void updateRejectsTrailingDot() {
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(tenantId, "acme.com.", actor))
                .isInstanceOf(TenantException.InvalidDomain.class);
    }

    @Test
    void updateRejectsLeadingDot() {
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(tenantId, ".acme.com", actor))
                .isInstanceOf(TenantException.InvalidDomain.class);
    }

    @Test
    void updateRejectsLeadingHyphen() {
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(tenantId, "-acme.com", actor))
                .isInstanceOf(TenantException.InvalidDomain.class);
    }

    @Test
    void updateRejectsTrailingHyphen() {
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(tenantId, "acme-.com", actor))
                .isInstanceOf(TenantException.InvalidDomain.class);
    }

    @Test
    void updateRejectsInvalidCharacters() {
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(tenantId, "acme!.com", actor))
                .isInstanceOf(TenantException.InvalidDomain.class);
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(tenantId, "acme com.br", actor))
                .isInstanceOf(TenantException.InvalidDomain.class);
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(tenantId, "acme_corp.com", actor))
                .isInstanceOf(TenantException.InvalidDomain.class);
    }

    @Test
    void updateFailsWhenTenantNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateAllowedEmailDomain(unknown, "acme.com", actor))
                .isInstanceOf(TenantException.NotFound.class);
    }

    @Test
    void domainPatternHelpersAreConsistent() {
        // Sanity check of the aggregate domain boundary (direct helper call).
        assertThat(Tenant.isValidEmailDomain(null)).isTrue();
        assertThat(Tenant.isValidEmailDomain("acme.com")).isTrue();
        assertThat(Tenant.isValidEmailDomain("a.io")).isTrue();
        assertThat(Tenant.isValidEmailDomain("acme")).isFalse();
        assertThat(Tenant.normalizeEmailDomain("  Acme.COM  ")).isEqualTo("acme.com");
        assertThat(Tenant.normalizeEmailDomain("   ")).isNull();
        assertThat(Tenant.normalizeEmailDomain(null)).isNull();
    }

    // ============================================================
    // Fakes
    // ============================================================

    private static class InMemoryTenantRepo implements TenantRepository {
        private final Map<UUID, Tenant> store = new HashMap<>();

        @Override
        public java.util.List<UUID> allActiveTenantIds() {
            return java.util.List.copyOf(store.keySet());
        }

        @Override
        public Optional<Tenant> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsBySlug(String slug) {
            return store.values().stream().anyMatch(t -> t.slug().equals(slug));
        }

        @Override
        public Tenant save(Tenant tenant) {
            store.put(tenant.id(), tenant);
            return tenant;
        }

        @Override
        public void hardDelete(UUID tenantId) {
            store.remove(tenantId);
        }
    }

    private record RecordedAudit(
            UUID tenantId,
            UUID actor,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> payload) {}

    private static class RecordingIamRepo implements IamRepository {
        final List<RecordedAudit> audits = new ArrayList<>();

        @Override
        public void recordAudit(
                UUID tenantId,
                UUID actorUserId,
                String action,
                String targetType,
                UUID targetId,
                Map<String, Object> payload) {
            Map<String, Object> copy = new HashMap<>();
            if (payload != null) {
                copy.putAll(payload);
            }
            audits.add(
                    new RecordedAudit(tenantId, actorUserId, action, targetType, targetId, copy));
        }

        // ----- unused; they throw to flag improper use. -----

        @Override
        public IamGroup createGroup(
                UUID tenantId, String name, String description, UUID createdBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IamGroup> findGroup(UUID groupId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IamGroup> listGroups(UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteGroup(UUID groupId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addUserToGroup(UUID userId, UUID groupId, UUID tenantId, UUID attachedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeUserFromGroup(UUID userId, UUID groupId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> listGroupMembers(UUID groupId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> listUserGroups(UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IamPolicy createPolicy(
                UUID tenantId,
                String name,
                String description,
                String documentJson,
                UUID createdBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IamPolicy updatePolicyDocument(
                UUID policyId, UUID tenantId, String documentJson, UUID updatedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IamPolicy> findPolicy(UUID policyId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IamPolicy> listPolicies(UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deletePolicy(UUID policyId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void attachPolicyToGroup(
                UUID policyId, UUID groupId, UUID tenantId, UUID attachedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void detachPolicyFromGroup(UUID policyId, UUID groupId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void attachPolicyToUser(UUID policyId, UUID userId, UUID tenantId, UUID attachedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void detachPolicyFromUser(UUID policyId, UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PolicyStatement> collectStatementsForUser(UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AttachedPolicy> collectAttachedPoliciesForUser(UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PermissionBoundary> findBoundaryForUser(UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setBoundaryForUser(UUID userId, UUID policyId, UUID tenantId, UUID by) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeBoundaryForUser(UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IamAuditEvent> listAudit(UUID tenantId, OffsetDateTime since, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
