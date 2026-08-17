package br.com.nora.api.domain.iam;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.api.security.ResourceArns;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * US41 — the built-in templates, checked against the evaluator that will run them.
 *
 * <p>A template is a document somebody will attach to a person, so the properties that matter are
 * not about its text. They are: it grants what its name says and nothing wider, it is confined to
 * the tenant it was rendered for, and every condition operator in it is one {@link PolicyEvaluator}
 * implements — an operator outside that set makes the statement not match, so a template carrying
 * one would ship an Allow that can never allow.
 */
class PolicyTemplateCatalogTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** The operators {@code PolicyEvaluator.SUPPORTED_CONDITION_OPERATORS} lists. */
    private static final Set<String> SUPPORTED =
            Set.of("StringEquals", "StringIn", "StringLike", "DateGreaterThan", "DateLessThan");

    @Test
    void idsAreUniqueAndUsableAsAPolicyName() {
        List<String> ids =
                PolicyTemplateCatalog.forTenant(TENANT).stream().map(PolicyTemplate::id).toList();

        assertThat(ids).doesNotHaveDuplicates().isNotEmpty();
        assertThat(ids).allSatisfy(id -> assertThat(id).matches("[a-z0-9]+(-[a-z0-9]+)*"));
    }

    @Test
    void everyConditionOperatorIsOneTheEvaluatorImplements() {
        for (PolicyTemplate template : PolicyTemplateCatalog.forTenant(TENANT)) {
            for (PolicyStatement statement : template.document().statements()) {
                assertThat(statement.condition().keySet())
                        .as("operators of %s", template.id())
                        .isSubsetOf(SUPPORTED);
            }
        }
    }

    @Test
    void everyResourceIsConfinedToTheTenantItWasRenderedFor() {
        String prefix = "nora:tenant/" + TENANT;
        for (PolicyTemplate template : PolicyTemplateCatalog.forTenant(TENANT)) {
            for (PolicyStatement statement : template.document().statements()) {
                assertThat(statement.resources())
                        .as("resources of %s", template.id())
                        .allSatisfy(resource -> assertThat(resource).startsWith(prefix));
            }
        }
    }

    /**
     * The ARN formats are written out in the catalogue because {@code domain} may not import the
     * {@code api} layer. This is what keeps the copy honest: a format that drifts from {@code
     * ResourceArns} produces a policy that matches no resource, which no other test would notice.
     */
    @Test
    void theArnFormatsMatchResourceArns() {
        List<String> resources =
                PolicyTemplateCatalog.forTenant(TENANT).stream()
                        .flatMap(t -> t.document().statements().stream())
                        .flatMap(s -> s.resources().stream())
                        .distinct()
                        .toList();

        assertThat(resources)
                .contains(
                        ResourceArns.tenant(TENANT),
                        ResourceArns.meeting(TENANT, null),
                        ResourceArns.task(TENANT, null),
                        ResourceArns.iamWildcard(TENANT),
                        ResourceArns.invite(TENANT, null));
    }

    @Test
    void readOnlyAccessReadsEverythingAndWritesNothing() {
        List<PolicyStatement> stmts = statementsOf("read-only-access");

        assertThat(allowed(stmts, "meeting:read", ResourceArns.meeting(TENANT, null))).isTrue();
        assertThat(allowed(stmts, "task:read", ResourceArns.task(TENANT, null))).isTrue();
        assertThat(allowed(stmts, "tenant:read", ResourceArns.tenant(TENANT))).isTrue();
        assertThat(allowed(stmts, "meeting:update", ResourceArns.meeting(TENANT, null))).isFalse();
        assertThat(allowed(stmts, "task:write", ResourceArns.task(TENANT, null))).isFalse();
    }

    /** IAM is left out of the read-only template on purpose; this is the assertion that says so. */
    @Test
    void readOnlyAccessDoesNotReachIam() {
        List<PolicyStatement> stmts = statementsOf("read-only-access");

        assertThat(allowed(stmts, "iam:policy:read", ResourceArns.iamWildcard(TENANT))).isFalse();
        assertThat(allowed(stmts, "iam:audit:read", ResourceArns.iamWildcard(TENANT))).isFalse();
    }

    @Test
    void meetingAnalystWorksOnMeetingsAndTasksOnly() {
        List<PolicyStatement> stmts = statementsOf("meeting-analyst");

        assertThat(allowed(stmts, "meeting:upload", ResourceArns.meeting(TENANT, null))).isTrue();
        assertThat(allowed(stmts, "task:write", ResourceArns.task(TENANT, null))).isTrue();
        assertThat(allowed(stmts, "tenant:name:write", ResourceArns.tenant(TENANT))).isFalse();
        assertThat(allowed(stmts, "meeting:analyze:live", ResourceArns.meeting(TENANT, null)))
                .isFalse();
    }

    @Test
    void iamAdministratorCoversTheWholeIamVocabulary() {
        List<PolicyStatement> stmts = statementsOf("iam-administrator");
        String iam = ResourceArns.iamWildcard(TENANT);

        assertThat(allowed(stmts, "iam:policy:create", iam)).isTrue();
        assertThat(allowed(stmts, "iam:policy:simulate", iam)).isTrue();
        assertThat(allowed(stmts, "iam:audit:read", iam)).isTrue();
        assertThat(allowed(stmts, "iam:user:invite", ResourceArns.invite(TENANT, null))).isTrue();
        assertThat(allowed(stmts, "meeting:read", ResourceArns.meeting(TENANT, null))).isFalse();
    }

    /**
     * The placeholder is not a nicety: an unsatisfied condition denies, so the template grants
     * nothing until someone edits the value. Shipping a template that quietly allowed everything
     * until narrowed would be the opposite failure.
     */
    @Test
    void theConditionTemplateDeniesUntilItsPlaceholderIsReplaced() {
        List<PolicyStatement> stmts = statementsOf("department-scoped-meeting-reader");
        String meeting = "nora:tenant/" + TENANT + ":meeting/abc";

        assertThat(allowed(stmts, "meeting:read", meeting, Map.of("department", "Vendas")))
                .isFalse();
        assertThat(allowed(stmts, "meeting:read", meeting, Map.of())).isFalse();
        Map<String, String> asShipped =
                Map.of("department", PolicyTemplateCatalog.CONDITION_PLACEHOLDER);
        assertThat(allowed(stmts, "meeting:read", meeting, asShipped)).isTrue();
    }

    /** Rendering for one tenant must not produce a document that reaches another one. */
    @Test
    void aTemplateRenderedForOneTenantGrantsNothingInAnother() {
        for (PolicyTemplate template : PolicyTemplateCatalog.forTenant(TENANT)) {
            List<PolicyStatement> stmts = template.document().statements();
            assertThat(allowed(stmts, "meeting:read", ResourceArns.meeting(OTHER_TENANT, null)))
                    .as("template %s in another tenant", template.id())
                    .isFalse();
            assertThat(allowed(stmts, "iam:policy:create", ResourceArns.iamWildcard(OTHER_TENANT)))
                    .as("template %s in another tenant", template.id())
                    .isFalse();
        }
    }

    private static List<PolicyStatement> statementsOf(String id) {
        return PolicyTemplateCatalog.forTenant(TENANT).stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no template with id " + id))
                .document()
                .statements();
    }

    private static boolean allowed(List<PolicyStatement> stmts, String action, String resource) {
        return allowed(stmts, action, resource, Map.of());
    }

    private static boolean allowed(
            List<PolicyStatement> stmts,
            String action,
            String resource,
            Map<String, String> context) {
        return PolicyEvaluator.isAllowed(stmts, action, resource, context);
    }
}
