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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS IAM-style policy evaluator.
 *
 * <p>Rules (ADR 0007):
 *
 * <ol>
 *   <li>Tenant Root caller => ALLOW (decided outside this evaluator).
 *   <li>Collect ALL statements applicable to the user (direct + via groups).
 *   <li>Any DENY statement matching Action+Resource+Condition => DENY.
 *   <li>Otherwise, require at least one ALLOW matching Action+Resource+Condition => ALLOW.
 *   <li>Default: DENY.
 * </ol>
 *
 * <p>Wildcards: {@code *} (zero or more characters) and {@code ?} (one character) are supported in
 * {@code action} and {@code resource}.
 *
 * <p>Supported conditions: {@code StringEquals}, {@code StringIn}, {@code StringLike}, {@code
 * DateGreaterThan}, {@code DateLessThan}. Expected format inside the statement:
 *
 * <pre>{@code
 * "condition": {"StringEquals":    {"chave": "valor"}}
 * "condition": {"StringIn":        {"chave": ["v1", "v2"]}}
 * "condition": {"StringLike":      {"chave": "prefixo-*"}}
 * "condition": {"DateGreaterThan": {"chave": "2026-01-01T00:00:00Z"}}
 * "condition": {"DateLessThan":    {"chave": "2026-12-31"}}
 * }</pre>
 *
 * <p>The {@code chave} is resolved in the {@code requestContext} passed by the caller (attributes
 * of the resource, of the user, of the request, etc.). Statements without a condition are evaluated
 * as always satisfied. Unsupported operators (e.g. StringNotEquals) and attributes missing from the
 * context make the statement NOT match (fail-closed) — any Allow with an unknown operator/attribute
 * is ignored instead of being treated as satisfied.
 */
public final class PolicyEvaluator {

    private static final Set<String> SUPPORTED_CONDITION_OPERATORS =
            Set.of("StringEquals", "StringIn", "StringLike", "DateGreaterThan", "DateLessThan");

    private PolicyEvaluator() {}

    /** Overload without context — equivalent to an empty context. Keeps compatibility. */
    public static boolean isAllowed(
            List<PolicyStatement> statements, String action, String resource) {
        return isAllowed(statements, action, resource, Collections.emptyMap());
    }

    /**
     * Pre-check for list endpoints: returns true if some Allow could apply to SOME resource in the
     * set described by {@code resourceSet} (without evaluating conditions) and no unconditional
     * Deny covers the WHOLE set. An unconditional Deny over the whole set blocks every instance and
     * cannot be overridden by context, so it short-circuits the pre-check.
     *
     * <p><strong>Both sides are sets.</strong> {@code resourceSet} is a pattern too — the caller
     * passes {@code nora:tenant/T:meeting/*}, meaning "any meeting of this tenant" — while {@link
     * #matchesResource} only expands wildcards on the STATEMENT side and compares the other as a
     * literal. So a grant written on one meeting, {@code nora:tenant/T:meeting/9c8f-...}, produced
     * the quoted regex of that ARN and was asked whether it matched the eight characters {@code
     * meeting/*}; it did not, the pre-check denied, and {@code GET /meetings/search} answered 403
     * for a user whose per-row check twenty lines later would have returned that very meeting.
     *
     * <p>A pre-check must never reject what the per-row check would serve. It asks a weaker
     * question than {@link #isAllowed}: not "does this statement cover this resource" but "can
     * these two sets overlap at all" for Allow, and "does this statement cover every last member of
     * the set" for the unconditional Deny short-circuit.
     */
    public static boolean hasAnyAllow(
            List<PolicyStatement> statements, String action, String resourceSet) {
        if (statements == null || statements.isEmpty()) {
            return false;
        }
        boolean anyAllow = false;
        for (PolicyStatement s : statements) {
            if (!matchesAction(s, action)) {
                continue;
            }
            boolean unconditional = s.condition() == null || s.condition().isEmpty();
            if (s.effect() == Effect.DENY && unconditional && coversWholeSet(s, resourceSet)) {
                return false;
            }
            if (s.effect() == Effect.ALLOW && intersectsSet(s, resourceSet)) {
                anyAllow = true;
            }
        }
        return anyAllow;
    }

    /**
     * Whether any resource pattern of the statement can share a member with {@code resourceSet}.
     */
    private static boolean intersectsSet(PolicyStatement s, String resourceSet) {
        for (String pattern : s.resources()) {
            if (globsIntersect(pattern, resourceSet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether some resource pattern of the statement covers EVERY member of {@code resourceSet}.
     */
    private static boolean coversWholeSet(PolicyStatement s, String resourceSet) {
        for (String pattern : s.resources()) {
            if (coversEveryMemberOf(pattern, resourceSet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code pattern} matches EVERY string the {@code resourceSet} glob describes.
     *
     * <p>This was {@code matches(pattern, resourceSet)}, which compares the pattern against the
     * set's own text as if it were a resource. The {@code ?} wildcard then consumed the literal
     * {@code *}: an unconditional Deny on {@code nora:tenant/T:meeting/?} — a one-character id no
     * meeting has — short-circuited the pre-check for the whole tenant, and {@code GET
     * /meetings/search} answered 403 while the per-row check allowed every real meeting.
     *
     * <p>The rule is stated in terms of the sets. A pattern with no trailing {@code *} matches
     * finitely many strings and cannot cover the infinite set {@code prefix*}; one that has a
     * trailing {@code *} covers it exactly when its own fixed part is satisfied by a prefix of the
     * set's fixed part, leaving its star to absorb whatever the set's star produces.
     *
     * <p>Deliberately conservative in one direction: a Deny on {@code meeting/????????-????-...}
     * does deny every real UUID, but not every string in the set, so it does not short-circuit. The
     * consequence is a query that runs and returns nothing — never an unauthorized read.
     */
    private static boolean coversEveryMemberOf(String pattern, String resourceSet) {
        if (pattern.equals("*")) {
            return true;
        }
        if (!resourceSet.endsWith("*")) {
            return matches(pattern, resourceSet);
        }
        if (!pattern.endsWith("*")) {
            return false;
        }
        String patternPrefix = pattern.substring(0, pattern.length() - 1);
        String setPrefix = resourceSet.substring(0, resourceSet.length() - 1);
        return Pattern.compile(globRegexPrefix(patternPrefix)).matcher(setPrefix).lookingAt();
    }

    /**
     * Whether two glob patterns describe overlapping sets of strings.
     *
     * <p>Exact for the shapes the ARNs use: one side is a literal or a literal followed by a single
     * trailing {@code *}. The trailing star is dropped and the remaining prefix matched against the
     * other pattern with {@link Matcher#hitEnd()} — true when the input ran out mid-pattern, i.e.
     * when more characters could still complete a match, which is precisely what the star supplies.
     */
    private static boolean globsIntersect(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return coversPrefixOf(a, b) || coversPrefixOf(b, a);
    }

    private static boolean coversPrefixOf(String pattern, String candidate) {
        if (pattern.equals("*")) {
            return true;
        }
        String prefix =
                candidate.endsWith("*")
                        ? candidate.substring(0, candidate.length() - 1)
                        : candidate;
        Matcher m = Pattern.compile(globRegex(pattern)).matcher(prefix);
        // matches() covers the case where the pattern is satisfied by the prefix alone;
        // hitEnd() the case where it would be satisfied by something the star can stand for.
        return m.matches() || (candidate.endsWith("*") && m.hitEnd());
    }

    /**
     * Says whether the decision for {@code action} is necessarily the SAME for every resource in
     * the set described by {@code wildcardResource} (e.g. {@code nora:tenant/{t}:meeting/*} = all
     * of the tenant's meetings).
     *
     * <p>It exists for the listing path. Filtering item by item forces loading the whole set from
     * the database before paginating, because only after evaluating each item do you know how many
     * are left. When no statement can DISTINGUISH two resources of the set, all that work always
     * produces the same answer -- and the decision can be taken once, before the query, leaving
     * pagination to SQL.
     *
     * <p>A statement distinguishes two resources of the set in two ways:
     *
     * <ul>
     *   <li><b>condition</b> -- reads resource attributes, which vary item by item;
     *   <li><b>resource more specific than the set</b> -- {@code meeting/abc*} matches some and not
     *       others.
     * </ul>
     *
     * <p>Returns {@code empty} on any doubt: the caller then evaluates item by item, exactly as
     * before. It is an optimization that only fires when it is demonstrably equivalent -- it never
     * widens nor restricts what the user sees.
     */
    public static Optional<Boolean> uniformDecision(
            List<PolicyStatement> statements, String action, String wildcardResource) {
        if (wildcardResource == null || !wildcardResource.endsWith("*")) {
            return Optional.empty();
        }
        String prefix = wildcardResource.substring(0, wildcardResource.length() - 1);
        if (statements == null || statements.isEmpty()) {
            return Optional.of(false);
        }

        // The decision is derived from the STRUCTURE of the statements, not from evaluating a
        // synthetic ARN. Evaluating `isAllowed(..., prefix + "*")` would be wrong: in that call
        // the `*` goes in as a value, a literal character, so matching that text is neither
        // necessary nor sufficient to match the real members of the set -- a Deny on
        // `meeting/????...` does not match the sentinel but denies every real meeting.
        boolean anyAllow = false;
        for (PolicyStatement s : statements) {
            if (!matchesAction(s, action)) {
                continue;
            }
            if (s.condition() != null && !s.condition().isEmpty()) {
                return Optional.empty();
            }
            boolean coversAll = false;
            for (String pattern : s.resources()) {
                Coverage coverage = classify(pattern, prefix);
                if (coverage == Coverage.PARTIAL) {
                    return Optional.empty();
                }
                if (coverage == Coverage.ALL) {
                    coversAll = true;
                }
            }
            if (!coversAll) {
                continue; // only has patterns that reach no member at all: irrelevant
            }
            if (s.effect() == Effect.DENY) {
                return Optional.of(false); // Deny over the whole set always wins
            }
            anyAllow = true;
        }
        return Optional.of(anyAllow);
    }

    /** How a resource pattern relates to the set {@code prefix + <qualquer id>}. */
    private enum Coverage {
        /** Matches EVERY member of the set. */
        ALL,
        /** Matches NO member at all. */
        NONE,
        /** Matches some and not others — forces item-by-item evaluation. */
        PARTIAL
    }

    private static Coverage classify(String pattern, String prefix) {
        int wild = firstWildcard(pattern);
        String literal = wild < 0 ? pattern : pattern.substring(0, wild);

        // None: the literal text before the first wildcard already diverges from the common
        // prefix, and every member of the set starts with that prefix. Another resource type,
        // another tenant.
        int common = Math.min(literal.length(), prefix.length());
        if (!literal.regionMatches(0, prefix, 0, common)) {
            return Coverage.NONE;
        }

        // All: the pattern is EXACTLY `<literal>*`, with the literal not going past the common
        // prefix. Requiring the `*` to be the last character is what was missing: without it,
        // `nora:tenant/*:meeting/<id>` passed as "matches everything" just because the literal
        // `nora:tenant/` is a prefix of the prefix — and the tail, which is exactly what
        // discriminates, was never looked at. A Deny like that vanished without a trace.
        if (wild >= 0
                && wild == pattern.length() - 1
                && pattern.charAt(wild) == '*'
                && literal.length() <= prefix.length()) {
            return Coverage.ALL;
        }

        return Coverage.PARTIAL;
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

    /** Full evaluation with request context (used for conditions). */
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
     * Evaluates the statement's conditions. Returns true iff ALL blocks are satisfied. Unsupported
     * operators, malformed values or attributes missing from the context make the statement NOT
     * match (return false) — fail-closed to avoid privilege escalation.
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
     * Evaluates a single requirement (context value vs expected value) for the given operator. A
     * missing attribute ({@code actual == null}) never matches. Unparseable dates do not match
     * either.
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

    /** {@code StringIn}: matches if {@code actual} equals any element of the expected list. */
    private static boolean matchesAnyOf(String actual, Object expected) {
        if (expected instanceof Iterable<?> values) {
            for (Object v : values) {
                if (actual.equals(String.valueOf(v))) {
                    return true;
                }
            }
            return false;
        }
        // A single value (non-list) is tolerated as plain equality.
        return actual.equals(String.valueOf(expected));
    }

    /**
     * Compares {@code actual} and {@code expected} as ISO-8601 instants. Accepts an offset (e.g.
     * {@code 2026-01-01T00:00:00Z}) or a plain date ({@code 2026-01-01}, midnight UTC). Returns
     * empty if either side fails to parse — the caller treats it as no-match (fail-closed).
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
            // not an offset-datetime; tries a plain date (yyyy-MM-dd) below
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
        return value.matches(globRegex(pattern));
    }

    /** The same regex without the closing anchor, for prefix matching. */
    private static String globRegexPrefix(String pattern) {
        String anchored = globRegex(pattern);
        return anchored.substring(0, anchored.length() - 1);
    }

    /** The anchored regex for a glob pattern: {@code *} is any run, {@code ?} is one character. */
    private static String globRegex(String pattern) {
        StringBuilder rx = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> rx.append(".*");
                case '?' -> rx.append('.');
                default -> rx.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return rx.append('$').toString();
    }
}
