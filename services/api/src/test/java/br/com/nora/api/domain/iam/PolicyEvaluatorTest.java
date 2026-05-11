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
        // Allow com operador nao suportado (DateGreaterThan) NAO deve conceder acesso —
        // fail-closed evita privilege escalation com policies que usem operadores futuros.
        var stmt =
                new PolicyStatement(
                        Effect.ALLOW,
                        List.of("meeting:read"),
                        List.of("nora:tenant/t1:meeting/*"),
                        Map.of("DateGreaterThan", Map.of("aws:CurrentTime", "2020-01-01")));
        assertThat(
                        PolicyEvaluator.isAllowed(
                                List.of(stmt),
                                "meeting:read",
                                "nora:tenant/t1:meeting/abc",
                                Map.of()))
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
