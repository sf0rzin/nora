package br.com.nora.api.domain.iam;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * satisfeitos. Operadores nao suportados (ex.: StringNotEquals, DateGreaterThan) fazem o statement
 * NAO casar (fail-closed) — qualquer Allow com operador desconhecido eh ignorado em vez de ser
 * tratado como satisfeito.
 */
public final class PolicyEvaluator {

    private static final Set<String> SUPPORTED_CONDITION_OPERATORS = Set.of("StringEquals");

    private PolicyEvaluator() {}

    /** Overload sem context — equivalente a context vazio. Mantem compatibilidade. */
    public static boolean isAllowed(
            List<PolicyStatement> statements, String action, String resource) {
        return isAllowed(statements, action, resource, Collections.emptyMap());
    }

    /**
     * Pre-check para list-endpoints: retorna true se existir algum Allow para action+resource (sem
     * avaliar conditions), e nao houver Deny incondicional cobrindo o mesmo action+resource. Um
     * Deny incondicional bloqueia todas as instancias e nao pode ser superado por contexto, entao
     * curto-circuita o pre-check.
     */
    public static boolean hasAnyAllow(
            List<PolicyStatement> statements, String action, String resource) {
        if (statements == null || statements.isEmpty()) {
            return false;
        }
        boolean anyAllow = false;
        for (PolicyStatement s : statements) {
            if (!matchesAction(s, action) || !matchesResource(s, resource)) {
                continue;
            }
            if (s.effect() == Effect.DENY && (s.condition() == null || s.condition().isEmpty())) {
                return false;
            }
            if (s.effect() == Effect.ALLOW) {
                anyAllow = true;
            }
        }
        return anyAllow;
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
     * Avalia as conditions do statement. Retorna true sse TODOS os blocos forem satisfeitos.
     * Suporta apenas {@code StringEquals} no MVP. Operadores nao suportados causam o statement a
     * NAO casar (return false) — fail-closed para evitar privilege escalation com policies que usem
     * operadores ainda nao implementados.
     */
    private static boolean matchesCondition(PolicyStatement s, Map<String, String> ctx) {
        Map<String, Object> condition = s.condition();
        if (condition == null || condition.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> block : condition.entrySet()) {
            String operator = block.getKey();
            if (!SUPPORTED_CONDITION_OPERATORS.contains(operator)) {
                return false;
            }
            if (!(block.getValue() instanceof Map<?, ?> requirements)) {
                return false;
            }
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
