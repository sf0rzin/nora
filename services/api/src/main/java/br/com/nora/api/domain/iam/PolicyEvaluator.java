package br.com.nora.api.domain.iam;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Avaliador de policies estilo AWS IAM.
 *
 * <p>Regras (ADR 0007):
 *
 * <ol>
 *   <li>Caller Root do tenant => ALLOW (decidido fora deste avaliador).
 *   <li>Coletar TODOS os statements aplicaveis ao usuario (diretos + via grupos).
 *   <li>Qualquer statement DENY que case Action+Resource => DENY.
 *   <li>Senao, exigir pelo menos um ALLOW que case Action+Resource => ALLOW.
 *   <li>Default: DENY.
 * </ol>
 *
 * <p>Wildcards: {@code *} (zero ou mais caracteres) e {@code ?} (um caractere) sao suportados em
 * {@code action} e {@code resource}.
 *
 * <p>Conditions sao IGNORADAS no MVP (ver ADR 0007 e PolicyStatement).
 */
public final class PolicyEvaluator {

    private PolicyEvaluator() {}

    public static boolean isAllowed(
            List<PolicyStatement> statements, String action, String resource) {
        if (statements == null || statements.isEmpty()) {
            return false;
        }
        boolean anyAllow = false;
        for (PolicyStatement s : statements) {
            if (!matchesAction(s, action) || !matchesResource(s, resource)) {
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

    private static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if (pattern.equals("*")) {
            return true;
        }
        // Converte glob simples (*, ?) em regex.
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
