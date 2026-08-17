package br.com.nora.api.domain.meeting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic participant matching: decides which declared roster entries denote the same person
 * (US13, ADR 0048).
 *
 * <p><b>Deterministic on purpose.</b> No model is asked whether two names are one person. A rule
 * that normalises and compares is reproducible between runs — the same roster always yields the
 * same partition — reviewable by whoever it merged, and it sends no participant name to a provider.
 * Reproducibility is also what lets the result be a read-time projection instead of a stored table,
 * which is what makes ADR 0029 erasure carry participant identity for free.
 *
 * <p><b>This class only ever sees the roster the user typed.</b> Transcript names are redacted by
 * the PII Shield before any component that could group them exists, and every occurrence gets its
 * own placeholder number, so on that side of the shield matching is impossible rather than merely
 * hard. Nothing here reads a transcript.
 *
 * <p><b>The default is to split, not to merge</b>, which is the mirror image of the shield's own
 * default. Over-splitting shows one person twice, which is visible and harmless; over-merging
 * attributes one person's meetings to another, which is a privacy failure that looks like the
 * feature working. An ambiguous case is left as two identities.
 *
 * <p>The rules, in the order they apply:
 *
 * <ul>
 *   <li>An e-mail is an identifier and outranks every name rule: same e-mail, same person, even
 *       when the declared names differ.
 *   <li>Equal normalised full names are one person. Normalisation folds case and accents, collapses
 *       whitespace, drops a leading honorific, drops a trailing parenthesised annotation and drops
 *       the pt-BR genitive particles.
 *   <li>A shared first token AND last token are one person, when both names carry at least two
 *       tokens. "Ana Paula Silva" and "Ana Silva" — the case the story is named after.
 *   <li>A differing e-mail vetoes a name-based merge, and a key under which two different e-mails
 *       appear does not absorb the rows that carry none: which of the two people those are is not
 *       knowable, so they become an identity of their own.
 *   <li>A lone first name never absorbs a full name. "Ana" does not join "Ana Silva" — a bare given
 *       name identifies nobody, and merging on it would collapse every Ana in the tenant.
 * </ul>
 *
 * <p>Pure and stateless: no cache, no static mutable state, no second source of rows. Tenant
 * isolation is therefore structural — the function groups exactly the list it is handed, and the
 * caller hands it one tenant's rows.
 */
public final class ParticipantMatcher {

    private ParticipantMatcher() {}

    /**
     * One declared roster row, flattened. {@code displayName} is what the user typed, {@code email}
     * may be null, and {@code meetingId} may be null when the caller is grouping a single roster.
     */
    public record Occurrence(UUID meetingId, String displayName, String email, boolean internal) {}

    /**
     * Honorifics and job titles that open a declared name. Dropped before comparison so that "Dra.
     * Ana Silva" and "Ana Silva" meet. Compared already folded, so the accented forms are covered.
     */
    private static final Set<String> HONORIFICS =
            Set.of(
                    "sr",
                    "sra",
                    "srta",
                    "dr",
                    "dra",
                    "prof",
                    "profa",
                    "eng",
                    "engenheiro",
                    "engenheira",
                    "pe",
                    "padre",
                    "diretor",
                    "diretora",
                    "coordenador",
                    "coordenadora",
                    "gerente",
                    "pres",
                    "presidente");

    /**
     * The pt-BR genitive particles, dropped between name tokens so that "Jose da Silva" and "Jose
     * Silva" meet.
     *
     * <p>{@code e} is on this list, which is the opposite of the call {@code pii_shield} makes for
     * the same word. There it separates two people in running prose ("Osvaldo Pinheiro e Marina
     * Alves"); here the whole string is one roster field describing one person, so inside it the
     * word can only be a particle ("Maria Ines e Souza"). The two decisions agree because the
     * inputs are not the same kind of text — which is exactly the vocabulary trap that file
     * documents having paid for once.
     */
    private static final Set<String> PARTICLES = Set.of("da", "das", "de", "do", "dos", "e");

    /** Trailing annotation a roster field often carries: "Ana Silva (Financeiro)", "Bruno [TI]". */
    private static final Pattern TRAILING_ANNOTATION =
            Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]\\s*$");

    /** Everything that is not a letter, a digit, a space or an intra-name hyphen. */
    private static final Pattern NOISE = Pattern.compile("[^\\p{L}\\p{N} -]");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /**
     * Folds a declared display name to its comparison key: accents stripped, case folded, honorific
     * and particles dropped, whitespace collapsed. Returns an empty string when nothing survives,
     * which is how a name made only of punctuation stops being matchable at all.
     */
    public static String normaliseName(String raw) {
        return String.join(" ", nameTokens(raw));
    }

    /**
     * Lower-cases and trims an e-mail, or returns null when there is nothing usable. No further
     * normalisation: stripping dots or {@code +tag} suffixes is provider-specific folklore, and
     * guessing wrong about it here would merge two people.
     */
    public static String normaliseEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Groups occurrences into identities. Output order follows the first appearance of each
     * identity in {@code occurrences}, so a caller that orders its rows decides the result order.
     */
    public static List<ParticipantIdentity> group(List<Occurrence> occurrences) {
        int n = occurrences.size();
        if (n == 0) {
            return List.of();
        }

        String[] emails = new String[n];
        String[] nameKeys = new String[n];
        String[] shortKeys = new String[n];
        for (int i = 0; i < n; i++) {
            Occurrence o = occurrences.get(i);
            emails[i] = normaliseEmail(o.email());
            List<String> tokens = nameTokens(o.displayName());
            nameKeys[i] = String.join(" ", tokens);
            // Two tokens minimum: a lone given name is not a key, it is an ambiguity. Left null
            // otherwise, which `bucketsOf` skips.
            if (tokens.size() >= 2) {
                shortKeys[i] = tokens.get(0) + "|" + tokens.get(tokens.size() - 1);
            }
        }

        Groups groups = new Groups(n, emails);

        // Pass 1 — the identifier. After it every group carries at most one distinct e-mail, and
        // the two name passes below preserve that invariant by refusing to merge two that do not
        // agree.
        Map<String, Integer> firstByEmail = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (emails[i] == null) {
                continue;
            }
            Integer seen = firstByEmail.putIfAbsent(emails[i], i);
            if (seen != null) {
                groups.union(seen, i);
            }
        }

        // Passes 2 and 3 — the name rules, applied key by key rather than row by row so the outcome
        // cannot depend on the order the rows arrived in. A TreeMap fixes the key order too, which
        // costs nothing and removes the last place a hash iteration could decide anything.
        unionByKey(groups, bucketsOf(nameKeys));
        unionByKey(groups, bucketsOf(shortKeys));

        return materialise(occurrences, groups, emails, shortKeys, nameKeys);
    }

    /**
     * Convenience for one meeting's roster: collapses the declared participants of a single meeting
     * into the people they denote. The meeting id plays no part in the grouping and is left null.
     */
    public static List<ParticipantIdentity> dedupe(List<Participant> participants) {
        if (participants == null || participants.isEmpty()) {
            return List.of();
        }
        List<Occurrence> occurrences = new ArrayList<>(participants.size());
        for (Participant p : participants) {
            occurrences.add(new Occurrence(null, p.displayName(), p.email(), p.isInternal()));
        }
        return group(occurrences);
    }

    // ----------------------------------------------------------------------------------------
    // internals
    // ----------------------------------------------------------------------------------------

    private static Map<String, List<Integer>> bucketsOf(String[] keys) {
        Map<String, List<Integer>> byKey = new TreeMap<>();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null || keys[i].isEmpty()) {
                continue;
            }
            byKey.computeIfAbsent(keys[i], k -> new ArrayList<>()).add(i);
        }
        return byKey;
    }

    /**
     * Merges the rows under each key, under the e-mail veto. Three cases, and the third is the one
     * that matters: when a key covers two different e-mails it names two different people, so the
     * rows carrying no e-mail are joined to each other and to NEITHER of them — attaching them to
     * whichever sorted first would be a coin toss deciding who somebody is.
     */
    private static void unionByKey(Groups groups, Map<String, List<Integer>> byKey) {
        for (List<Integer> members : byKey.values()) {
            if (members.size() < 2) {
                continue;
            }
            Set<Integer> emailedRoots = new LinkedHashSet<>();
            List<Integer> unlabelled = new ArrayList<>();
            for (int i : members) {
                if (groups.emailOf(i) != null) {
                    emailedRoots.add(groups.find(i));
                } else {
                    unlabelled.add(i);
                }
            }
            for (int k = 1; k < unlabelled.size(); k++) {
                groups.union(unlabelled.get(0), unlabelled.get(k));
            }
            if (emailedRoots.size() == 1 && !unlabelled.isEmpty()) {
                groups.union(emailedRoots.iterator().next(), unlabelled.get(0));
            }
        }
    }

    private static List<ParticipantIdentity> materialise(
            List<Occurrence> occurrences,
            Groups groups,
            String[] emails,
            String[] shortKeys,
            String[] nameKeys) {
        Map<Integer, List<Integer>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < occurrences.size(); i++) {
            byRoot.computeIfAbsent(groups.find(i), r -> new ArrayList<>()).add(i);
        }

        List<ParticipantIdentity> out = new ArrayList<>(byRoot.size());
        for (List<Integer> members : byRoot.values()) {
            String email = null;
            boolean internal = false;
            Set<String> variants = new TreeSet<>();
            List<UUID> meetingIds = new ArrayList<>();
            int canonical = members.get(0);
            for (int i : members) {
                Occurrence o = occurrences.get(i);
                if (email == null) {
                    email = emails[i];
                }
                internal |= o.internal();
                String declared = safeName(o);
                if (!declared.isEmpty()) {
                    variants.add(declared);
                }
                if (o.meetingId() != null && !meetingIds.contains(o.meetingId())) {
                    meetingIds.add(o.meetingId());
                }
                if (moreCanonical(occurrences, nameKeys, i, canonical)) {
                    canonical = i;
                }
            }
            String canonicalName = safeName(occurrences.get(canonical));
            out.add(
                    new ParticipantIdentity(
                            hash(anchor(email, shortKeys, nameKeys, canonical, canonicalName)),
                            canonicalName,
                            email,
                            internal,
                            List.copyOf(variants),
                            meetingIds));
        }
        return out;
    }

    /**
     * The string the identity's id is derived from. The e-mail when there is one; otherwise the
     * first/last token pair, which every member of a name-formed identity shares, so adding a
     * middle name in a later meeting does not change the id.
     *
     * <p>The last fallback is the declared name itself. A roster entry made only of punctuation
     * normalises to nothing and can match nobody — which is correct — but two of them would then
     * anchor on the same empty string and be handed the same id while being separate identities.
     */
    private static String anchor(
            String email, String[] shortKeys, String[] nameKeys, int canonical, String declared) {
        if (email != null) {
            return "e:" + email;
        }
        if (shortKeys[canonical] != null) {
            return "n:" + shortKeys[canonical];
        }
        return "n:" + (nameKeys[canonical].isEmpty() ? declared : nameKeys[canonical]);
    }

    /**
     * Picks the spelling that represents the identity: the fullest NAME, carrying the least
     * decoration. Most tokens first, then the longer normalised key, then the SHORTER declared
     * string, then lexicographic order.
     *
     * <p>The last two rules are what make it read as a name rather than as a label. Preferring the
     * SHORTER declared string once the keys are equal drops what normalisation has already
     * discarded, so "Bruno Dias" wins over "Sr. Bruno Dias" instead of the honorific surviving into
     * the canonical name by being longer. The key length breaks the tie before that, and it is what
     * an identity formed by the e-mail rule needs: two rows sharing an address can carry unrelated
     * names of the same token count, and the fuller one is the better label.
     *
     * <p>Every tie is broken, so two runs over the same rows cannot present one person under two
     * different names.
     */
    private static boolean moreCanonical(
            List<Occurrence> occurrences, String[] nameKeys, int candidate, int current) {
        int candidateTokens = tokenCount(nameKeys[candidate]);
        int currentTokens = tokenCount(nameKeys[current]);
        if (candidateTokens != currentTokens) {
            return candidateTokens > currentTokens;
        }
        if (nameKeys[candidate].length() != nameKeys[current].length()) {
            return nameKeys[candidate].length() > nameKeys[current].length();
        }
        String a = safeName(occurrences.get(candidate));
        String b = safeName(occurrences.get(current));
        if (a.length() != b.length()) {
            return a.length() < b.length();
        }
        return a.compareTo(b) < 0;
    }

    private static int tokenCount(String nameKey) {
        return nameKey.isEmpty() ? 0 : nameKey.split(" ").length;
    }

    private static String safeName(Occurrence o) {
        return o.displayName() == null ? "" : o.displayName().trim();
    }

    private static List<String> nameTokens(String raw) {
        if (raw == null) {
            return List.of();
        }
        String cleaned = TRAILING_ANNOTATION.matcher(raw.trim()).replaceAll("");
        cleaned = NOISE.matcher(fold(cleaned)).replaceAll(" ");
        List<String> tokens = new ArrayList<>();
        for (String token : WHITESPACE.split(cleaned)) {
            if (token.isEmpty() || PARTICLES.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        // A leading honorific is dropped only WHILE something is left after it. A roster entry of
        // "Gerente" alone is a label rather than a title, and stripping it to nothing would leave
        // an empty key that every other such row matches.
        int start = 0;
        while (start < tokens.size() - 1 && HONORIFICS.contains(tokens.get(start))) {
            start++;
        }
        return List.copyOf(tokens.subList(start, tokens.size()));
    }

    /** NFD + strip combining marks + case fold, so an accented name matches its plain spelling. */
    private static String fold(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** {@code sha256(value)} cut to 16 hex characters — the {@code pii_shield._hash} idiom. */
    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required of every JDK", ex);
        }
    }

    /**
     * Disjoint set over the occurrence indices, carrying each group's e-mail so the veto is a
     * lookup rather than a scan. A group's e-mail never changes once set, because the only merges
     * that reach {@link #union} either agree on it or have none on one side.
     */
    private static final class Groups {

        private final int[] parent;
        private final String[] emailOfRoot;

        Groups(int size, String[] emails) {
            this.parent = new int[size];
            this.emailOfRoot = new String[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
                emailOfRoot[i] = emails[i];
            }
        }

        int find(int i) {
            int root = i;
            while (parent[root] != root) {
                root = parent[root];
            }
            while (parent[i] != root) {
                int next = parent[i];
                parent[i] = root;
                i = next;
            }
            return root;
        }

        String emailOf(int i) {
            return emailOfRoot[find(i)];
        }

        void union(int a, int b) {
            int rootA = find(a);
            int rootB = find(b);
            if (rootA == rootB) {
                return;
            }
            // Lowest index wins, so a group's representative does not depend on merge order.
            int keep = Math.min(rootA, rootB);
            int drop = Math.max(rootA, rootB);
            parent[drop] = keep;
            if (emailOfRoot[keep] == null) {
                emailOfRoot[keep] = emailOfRoot[drop];
            }
        }
    }
}
