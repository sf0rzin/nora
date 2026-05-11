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
}
