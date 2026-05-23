package br.com.nora.api.domain.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyEvaluatorTest {

    private static PolicyStatement allow(String action, String resource) {
        return new PolicyStatement(Effect.ALLOW, List.of(action), List.of(resource), null);
    }

    private static PolicyStatement deny(String action, String resource) {
        return new PolicyStatement(Effect.DENY, List.of(action), List.of(resource), null);
    }

    private static PolicyStatement allowIfStringEquals(
            String action, String resource, String key, String expected) {
        return new PolicyStatement(
                Effect.ALLOW,
                List.of(action),
                List.of(resource),
                Map.of("StringEquals", Map.of(key, expected)));
    }

    /** Allow meeting:read no tenant t1 com uma unica condition {operator: {key: expected}}. */
    private static PolicyStatement allowWithCondition(
            String operator, String key, Object expected) {
        return new PolicyStatement(
                Effect.ALLOW,
                List.of("meeting:read"),
                List.of("nora:tenant/t1:meeting/*"),
                Map.of(operator, Map.of(key, expected)));
    }

    @Test
    void allowsExactMatch() {
        var stmts = List.of(allow("meeting:read", "nora:tenant/t1:meeting/*"));
        assertThat(PolicyEvaluator.isAllowed(stmts, "meeting:read", "nora:tenant/t1:meeting/abc"))
                .isTrue();
    }

    @Test
    void deniesByDefault() {
        assertThat(PolicyEvaluator.isAllowed(List.of(), "meeting:read", "x")).isFalse();
        var stmts = List.of(allow("meeting:write", "nora:tenant/t1:meeting/*"));
        assertThat(PolicyEvaluator.isAllowed(stmts, "meeting:read", "nora:tenant/t1:meeting/x"))
                .isFalse();
    }

    @Test
    void denyOverridesAllow() {
        var stmts =
                List.of(
                        allow("meeting:*", "nora:tenant/t1:meeting/*"),
                        deny("meeting:delete", "nora:tenant/t1:meeting/*"));
        assertThat(PolicyEvaluator.isAllowed(stmts, "meeting:delete", "nora:tenant/t1:meeting/abc"))
                .isFalse();
        assertThat(PolicyEvaluator.isAllowed(stmts, "meeting:read", "nora:tenant/t1:meeting/abc"))
                .isTrue();
    }

    @Test
    void wildcardActionAndResource() {
        var stmts = List.of(allow("*", "*"));
        assertThat(PolicyEvaluator.isAllowed(stmts, "iam:group:create", "nora:tenant/t1:iam/*"))
                .isTrue();
    }

    @Test
    void wildcardSegmentMatchesPrefix() {
        var stmts = List.of(allow("iam:*", "nora:tenant/t1:iam/*"));
        assertThat(PolicyEvaluator.isAllowed(stmts, "iam:policy:read", "nora:tenant/t1:iam/x"))
                .isTrue();
        assertThat(PolicyEvaluator.isAllowed(stmts, "meeting:read", "nora:tenant/t1:iam/x"))
                .isFalse();
    }

    @Test
    void resourceMustMatchTenantPrefix() {
        var stmts = List.of(allow("meeting:read", "nora:tenant/t1:meeting/*"));
        assertThat(PolicyEvaluator.isAllowed(stmts, "meeting:read", "nora:tenant/t2:meeting/abc"))
                .isFalse();
    }

    @Test
    void stringEqualsConditionAllowsWhenContextMatches() {
        var stmts =
                List.of(
                        allowIfStringEquals(
                                "meeting:read",
                                "nora:tenant/t1:meeting/*",
                                "department",
                                "Vendas"));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("department", "Vendas")))
                .isTrue();
    }

    @Test
    void stringEqualsConditionDeniesWhenContextDiffers() {
        var stmts =
                List.of(
                        allowIfStringEquals(
                                "meeting:read",
                                "nora:tenant/t1:meeting/*",
                                "department",
                                "Vendas"));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("department", "Suporte")))
                .isFalse();
    }

    @Test
    void stringEqualsConditionDeniesWhenContextMissing() {
        var stmts =
                List.of(
                        allowIfStringEquals(
                                "meeting:read",
                                "nora:tenant/t1:meeting/*",
                                "department",
                                "Vendas"));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts, "meeting:read", "nora:tenant/t1:meeting/abc", Map.of()))
                .isFalse();
    }

    @Test
    void emptyConditionAlwaysSatisfied() {
        var stmts = List.of(allow("meeting:read", "nora:tenant/t1:meeting/*"));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts, "meeting:read", "nora:tenant/t1:meeting/abc", Map.of()))
                .isTrue();
    }

    @Test
    void unknownConditionOperatorIsFailClosed() {
        // Allow com operador nao suportado (StringNotEquals) NAO deve conceder acesso —
        // fail-closed evita privilege escalation com policies que usem operadores futuros.
        // (O contexto satisfaria a intencao do operador, isolando que o false vem do operador
        // desconhecido e nao de atributo ausente.)
        var stmt =
                new PolicyStatement(
                        Effect.ALLOW,
                        List.of("meeting:read"),
                        List.of("nora:tenant/t1:meeting/*"),
                        Map.of("StringNotEquals", Map.of("department", "Suporte")));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                List.of(stmt),
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("department", "Vendas")))
                .isFalse();
    }

    @Test
    void stringInConditionMatchesAnyValueInList() {
        var stmts =
                List.of(
                        allowWithCondition(
                                "StringIn", "department", List.of("Vendas", "Marketing")));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("department", "Marketing")))
                .isTrue();
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("department", "Suporte")))
                .isFalse();
    }

    @Test
    void stringLikeConditionSupportsWildcards() {
        var stmts = List.of(allowWithCondition("StringLike", "project", "acme-*"));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("project", "acme-manufatura")))
                .isTrue();
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("project", "northwind-payments")))
                .isFalse();
    }

    @Test
    void dateGreaterThanConditionComparesInstants() {
        var stmts =
                List.of(
                        allowWithCondition(
                                "DateGreaterThan", "meetingDate", "2026-01-01T00:00:00Z"));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("meetingDate", "2026-06-01T10:00:00Z")))
                .isTrue();
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("meetingDate", "2025-12-31T23:59:59Z")))
                .isFalse();
    }

    @Test
    void dateLessThanAcceptsPlainDateAndIsFailClosedOnUnparseable() {
        var stmts = List.of(allowWithCondition("DateLessThan", "meetingDate", "2026-01-01"));
        // Data simples (yyyy-MM-dd) interpretada como meia-noite UTC.
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("meetingDate", "2025-06-01")))
                .isTrue();
        // Valor nao-parseavel => fail-closed (nao casa).
        assertThat(
                        PolicyEvaluator.isAllowed(
                                stmts,
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("meetingDate", "ontem")))
                .isFalse();
    }

    @Test
    void mixedKnownAndUnknownOperatorRequiresAllSatisfied() {
        // Bloco StringEquals satisfeito mas StringNotEquals desconhecido => fail-closed.
        var stmt =
                new PolicyStatement(
                        Effect.ALLOW,
                        List.of("meeting:read"),
                        List.of("nora:tenant/t1:meeting/*"),
                        Map.of(
                                "StringEquals", Map.of("department", "Vendas"),
                                "StringNotEquals", Map.of("region", "BR-RJ")));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                List.of(stmt),
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of("department", "Vendas", "region", "BR-SP")))
                .isFalse();
    }

    @Test
    void hasAnyAllowReturnsTrueForUnconditionalAllow() {
        var stmts = List.of(allow("meeting:read", "nora:tenant/t1:meeting/*"));
        assertThat(PolicyEvaluator.hasAnyAllow(stmts, "meeting:read", "nora:tenant/t1:meeting/*"))
                .isTrue();
    }

    @Test
    void hasAnyAllowReturnsTrueForConditionalAllow() {
        // Pre-check ignora conditions: existe Allow potencial, basta para passar.
        var stmts =
                List.of(
                        allowIfStringEquals(
                                "meeting:read",
                                "nora:tenant/t1:meeting/*",
                                "department",
                                "Vendas"));
        assertThat(PolicyEvaluator.hasAnyAllow(stmts, "meeting:read", "nora:tenant/t1:meeting/*"))
                .isTrue();
    }

    @Test
    void hasAnyAllowReturnsFalseWhenUnconditionalDenyCoversAction() {
        // Allow + Deny incondicional na mesma action+resource => Deny vence sempre.
        // Pre-check deve fechar 403 em vez de devolver lista vazia silenciosa.
        var stmts =
                List.of(
                        allow("meeting:read", "nora:tenant/t1:meeting/*"),
                        deny("meeting:read", "nora:tenant/t1:meeting/*"));
        assertThat(PolicyEvaluator.hasAnyAllow(stmts, "meeting:read", "nora:tenant/t1:meeting/*"))
                .isFalse();
    }

    @Test
    void hasAnyAllowIgnoresConditionalDeny() {
        // Deny condicional NAO deve fechar o pre-check — pode haver instancias permitidas.
        var conditionalDeny =
                new PolicyStatement(
                        Effect.DENY,
                        List.of("meeting:read"),
                        List.of("nora:tenant/t1:meeting/*"),
                        Map.of("StringEquals", Map.of("department", "Suporte")));
        var stmts = List.of(allow("meeting:read", "nora:tenant/t1:meeting/*"), conditionalDeny);
        assertThat(PolicyEvaluator.hasAnyAllow(stmts, "meeting:read", "nora:tenant/t1:meeting/*"))
                .isTrue();
    }
}
