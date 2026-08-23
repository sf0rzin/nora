package br.com.nora.api.domain.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The cardinality caps on a policy document.
 *
 * <p>They are an availability control, not tidiness: {@link PolicyEvaluator} walks every statement
 * of every attached policy on EVERY authorization decision, and US44 added the permission boundary
 * as a second walk on top. A document is written by whoever holds {@code iam:policy:create}, so
 * without a ceiling one valid document makes every request of every user in the tenant slower,
 * permanently. These tests pin the boundary in both directions — a cap nobody can reach is a cap
 * that will be raised by accident, and a cap that rejects ordinary policies is worse than none.
 */
class PolicyDocumentLimitsTest {

    private static PolicyStatement statement() {
        return new PolicyStatement(
                Effect.ALLOW,
                List.of("meeting:read"),
                List.of("nora:tenant/t:meeting/*"),
                Map.of());
    }

    private static List<PolicyStatement> statements(int count) {
        return IntStream.range(0, count).mapToObj(i -> statement()).toList();
    }

    private static List<String> entries(int count, String prefix) {
        return IntStream.range(0, count).mapToObj(i -> prefix + i).toList();
    }

    @Test
    void acceptsADocumentAtTheStatementCeiling() {
        assertThatCode(
                        () ->
                                new PolicyDocument(
                                        "2026-05-07", statements(PolicyDocument.MAX_STATEMENTS)))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesOneStatementBeyondTheCeiling() {
        assertThatThrownBy(
                        () ->
                                new PolicyDocument(
                                        "2026-05-07",
                                        statements(PolicyDocument.MAX_STATEMENTS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
    }

    /**
     * The real cost of a policy is statements times actions times resources, so bounding only the
     * outer number bounds nothing: one statement with a hundred thousand actions is the same
     * traversal as a hundred thousand statements.
     */
    @Test
    void refusesAStatementWithTooManyActionsOrResources() {
        assertThatThrownBy(
                        () ->
                                new PolicyStatement(
                                        Effect.ALLOW,
                                        entries(PolicyStatement.MAX_ENTRIES_PER_LIST + 1, "a:"),
                                        List.of("nora:tenant/t:meeting/*"),
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actions");

        assertThatThrownBy(
                        () ->
                                new PolicyStatement(
                                        Effect.ALLOW,
                                        List.of("meeting:read"),
                                        entries(PolicyStatement.MAX_ENTRIES_PER_LIST + 1, "r"),
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resources");
    }

    /**
     * The counter-proof that matters most: the caps must be far above anything a person writes. The
     * whole built-in catalogue is checked here, because a template that stopped instantiating would
     * be the first sign the ceiling was set for the wrong reason.
     */
    @Test
    void everyBuiltInTemplateIsComfortablyInsideTheCaps() {
        for (PolicyTemplate template :
                PolicyTemplateCatalog.forTenant(java.util.UUID.randomUUID())) {
            PolicyDocument document = template.document();
            assertThat(document.statements().size())
                    .as("template %s statements", template.id())
                    .isLessThan(PolicyDocument.MAX_STATEMENTS);
            for (PolicyStatement s : document.statements()) {
                assertThat(s.actions().size())
                        .as("template %s actions", template.id())
                        .isLessThan(PolicyStatement.MAX_ENTRIES_PER_LIST);
                assertThat(s.resources().size())
                        .as("template %s resources", template.id())
                        .isLessThan(PolicyStatement.MAX_ENTRIES_PER_LIST);
            }
        }
    }
}
