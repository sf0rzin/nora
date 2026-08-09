package br.com.nora.api.domain.iam;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Conditions suportadas: {@code StringEquals}, {@code StringIn}, {@code StringLike}, {@code
 * DateGreaterThan}, {@code DateLessThan}. Formato esperado dentro do statement:
 *
 * <pre>{@code
 * "condition": {"StringEquals":    {"chave": "valor"}}
 * "condition": {"StringIn":        {"chave": ["v1", "v2"]}}
 * "condition": {"StringLike":      {"chave": "prefixo-*"}}
 * "condition": {"DateGreaterThan": {"chave": "2026-01-01T00:00:00Z"}}
 * "condition": {"DateLessThan":    {"chave": "2026-12-31"}}
 * }</pre>
 *
 * <p>A {@code chave} eh resolvida no {@code requestContext} passado pelo chamador (atributos do
 * recurso, do usuario, do request, etc.). Statements sem condition sao avaliados como sempre
 * satisfeitos. Operadores nao suportados (ex.: StringNotEquals) e atributos ausentes no contexto
 * fazem o statement NAO casar (fail-closed) — qualquer Allow com operador/atributo desconhecido eh
 * ignorado em vez de ser tratado como satisfeito.
 */
public final class PolicyEvaluator {

    private static final Set<String> SUPPORTED_CONDITION_OPERATORS =
            Set.of("StringEquals", "StringIn", "StringLike", "DateGreaterThan", "DateLessThan");

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

    /**
     * Diz se a decisao para {@code action} e forcosamente a MESMA para todo recurso do conjunto
     * descrito por {@code wildcardResource} (ex.: {@code nora:tenant/{t}:meeting/*} = todas as
     * reunioes do tenant).
     *
     * <p>Existe para o caminho de listagem. Filtrar item a item obriga a carregar o conjunto
     * inteiro do banco antes de paginar, porque so depois de avaliar cada item se sabe quantos
     * sobram. Quando nenhuma statement consegue DISTINGUIR dois recursos do conjunto, esse trabalho
     * todo produz sempre a mesma resposta -- e a decisao pode ser tomada uma vez, antes da query,
     * deixando a paginacao para o SQL.
     *
     * <p>Uma statement distingue dois recursos do conjunto de duas maneiras:
     *
     * <ul>
     *   <li><b>condition</b> -- le atributos do recurso, que variam item a item;
     *   <li><b>resource mais especifico que o conjunto</b> -- {@code meeting/abc*} casa uns e
     *       outros nao.
     * </ul>
     *
     * <p>Devolve {@code empty} em qualquer duvida: o caller entao avalia item a item, exatamente
     * como antes. E uma otimizacao que so dispara quando e demonstravelmente equivalente -- nunca
     * amplia nem restringe o que o usuario ve.
     */
    public static Optional<Boolean> uniformDecision(
            List<PolicyStatement> statements, String action, String wildcardResource) {
        if (wildcardResource == null || !wildcardResource.endsWith("*")) {
            return Optional.empty();
        }
        String prefix = wildcardResource.substring(0, wildcardResource.length() - 1);

        if (statements != null) {
            for (PolicyStatement s : statements) {
                if (!matchesAction(s, action)) {
                    continue;
                }
                if (s.condition() != null && !s.condition().isEmpty()) {
                    return Optional.empty();
                }
                for (String pattern : s.resources()) {
                    if (!nonDiscriminating(pattern, prefix)) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.of(isAllowed(statements, action, wildcardResource));
    }

    /**
     * Um pattern e nao-discriminante sobre o conjunto {@code prefix + *} quando casa TODO o
     * conjunto ou NENHUM elemento dele -- nos dois casos ele nao separa um item do outro.
     */
    private static boolean nonDiscriminating(String pattern, String prefix) {
        int wild = firstWildcard(pattern);
        String literal = wild < 0 ? pattern : pattern.substring(0, wild);

        // Casa tudo: o trecho literal antes do primeiro wildcard nao chega a ser mais especifico
        // que o prefixo comum do conjunto ("*", "nora:tenant/T:*", "nora:tenant/T:meeting/*").
        if (wild >= 0 && prefix.startsWith(literal)) {
            return true;
        }
        // Nao casa nada: os literais ja divergem antes de qualquer wildcard (outro tipo de
        // recurso, outro tenant), entao o pattern e irrelevante para este conjunto.
        int common = Math.min(literal.length(), prefix.length());
        return !literal.regionMatches(0, prefix, 0, common);
    }

    private static int firstWildcard(String pattern) {
        int star = pattern.indexOf('*');
        int any = pattern.indexOf('?');
        if (star < 0) {
            return any;
        }
        if (any < 0) {
            return star;
        }
        return Math.min(star, any);
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
     * Operadores nao suportados, valores mal-formados ou atributos ausentes no contexto causam o
     * statement a NAO casar (return false) — fail-closed para evitar privilege escalation.
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
                if (!matchesOperator(operator, ctx.get(key), entry.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Avalia um unico requisito (valor do contexto vs valor esperado) para o operador dado.
     * Atributo ausente ({@code actual == null}) nunca casa. Datas nao-parseaveis tambem nao casam.
     */
    private static boolean matchesOperator(String operator, String actual, Object expected) {
        if (actual == null) {
            return false;
        }
        return switch (operator) {
            case "StringEquals" -> actual.equals(String.valueOf(expected));
            case "StringIn" -> matchesAnyOf(actual, expected);
            case "StringLike" -> matches(String.valueOf(expected), actual);
            case "DateGreaterThan" ->
                    compareAsInstant(actual, expected).map(c -> c > 0).orElse(false);
            case "DateLessThan" -> compareAsInstant(actual, expected).map(c -> c < 0).orElse(false);
            default -> false;
        };
    }

    /** {@code StringIn}: casa se {@code actual} for igual a qualquer elemento da lista esperada. */
    private static boolean matchesAnyOf(String actual, Object expected) {
        if (expected instanceof Iterable<?> values) {
            for (Object v : values) {
                if (actual.equals(String.valueOf(v))) {
                    return true;
                }
            }
            return false;
        }
        // Valor unico (nao-lista) eh tolerado como igualdade simples.
        return actual.equals(String.valueOf(expected));
    }

    /**
     * Compara {@code actual} e {@code expected} como instantes ISO-8601. Aceita offset (ex.: {@code
     * 2026-01-01T00:00:00Z}) ou data simples ({@code 2026-01-01}, meia-noite UTC). Retorna vazio se
     * qualquer lado nao parsear — o caller trata como nao-casa (fail-closed).
     */
    private static Optional<Integer> compareAsInstant(String actual, Object expected) {
        Instant a = parseInstant(actual);
        Instant b = parseInstant(String.valueOf(expected));
        if (a == null || b == null) {
            return Optional.empty();
        }
        return Optional.of(a.compareTo(b));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
            // nao eh offset-datetime; tenta data simples (yyyy-MM-dd) abaixo
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
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
