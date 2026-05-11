package br.com.nora.api.domain.iam;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Avaliador de policies estilo AWS IAM.
 *
 * <p>Regras (ADR 0007):
 *
 * <ol>
 *   <li>Caller Root do tenant => ALLOW (decidido fora deste avaliador).
 *   <li>Coletar TODOS os statements aplicaveis ao usuario (diretos + via grupos).
 *   <li>Qualquer statement DENY que case Action+Resource+Condition => DENY.
 *   <li>Senao, exigir pelo menos um ALLOW que case Action+Resource+Condition => ALLOW.
 *   <li>Default: DENY.
 * </ol>
 *
 * <p>Wildcards: {@code *} (zero ou mais caracteres) e {@code ?} (um caractere) sao suportados em
 * {@code action} e {@code resource}.
 *
 * <p>Conditions suportadas no MVP: {@code StringEquals}. Formato esperado dentro do statement:
 *
 * <pre>{@code "condition": {"StringEquals": {"chave": "valor_esperado"}}}</pre>
 *
 * <p>A {@code chave} eh resolvida no {@code requestContext} passado pelo chamador (atributos do
 * recurso, do usuario, do request, etc.). Statements sem condition sao avaliados como sempre
 * satisfeitos.
 */
public final class PolicyEvaluator {

    private PolicyEvaluator() {}

    /** Overload sem context — equivalente a context vazio. Mantem compatibilidade. */
    public static boolean isAllowed(
            List<PolicyStatement> statements, String action, String resource) {
        return isAllowed(statements, action, resource, Collections.emptyMap());
    }

    /** Avaliacao completa com request context (usado para conditions). */
    public static boolean isAllowed(
            List<PolicyStatement> statements,
            String action,
            String resource,
            Map<String, String> requestContext) {
        if (statements == null || statements.isEmpty()) {
            return false;
        }
        Map<String, String> ctx = requestContext == null ? Collections.emptyMap() : requestContext;
        boolean anyAllow = false;
        for (PolicyStatement s : statements) {
            if (!matchesAction(s, action) || !matchesResource(s, resource)) {
                continue;
            }
            if (!matchesCondition(s, ctx)) {
                continue;
            }
            if (s.effect() == Effect.DENY) {
                return false;
            }
            anyAllow = true;
        }
        return anyAllow;
    }

    private static boolean matchesAction(PolicyStatement s, String action) {
        for (String pattern : s.actions()) {
            if (matches(pattern, action)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesResource(PolicyStatement s, String resource) {
        for (String pattern : s.resources()) {
            if (matches(pattern, resource)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Avalia as conditions do statement. Retorna true se todas as conditions forem satisfeitas (ou
     * se nao houver conditions). Suporta apenas {@code StringEquals} no MVP. Operadores
     * desconhecidos sao ignorados (statements com operadores nao suportados sao tratados como sem
     * restricao adicional alem do ja avaliado).
     */
    private static boolean matchesCondition(PolicyStatement s, Map<String, String> ctx) {
        Map<String, Object> condition = s.condition();
        if (condition == null || condition.isEmpty()) {
            return true;
        }
        Object stringEqualsBlock = condition.get("StringEquals");
        if (stringEqualsBlock instanceof Map<?, ?> requirements) {
            for (Map.Entry<?, ?> entry : requirements.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String expected = String.valueOf(entry.getValue());
                String actual = ctx.get(key);
                if (actual == null || !expected.equals(actual)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if (pattern.equals("*")) {
            return true;
        }
        StringBuilder rx = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> rx.append(".*");
                case '?' -> rx.append('.');
                default -> rx.append(Pattern.quote(String.valueOf(c)));
            }
        }
        rx.append('$');
        return value.matches(rx.toString());
    }
}
