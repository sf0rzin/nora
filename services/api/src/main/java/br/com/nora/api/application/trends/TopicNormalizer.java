package br.com.nora.api.application.trends;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Folds a {@code meeting_analyses.topics} entry into the key the trends panel counts on.
 *
 * <p>{@code topics} is a {@code TEXT[]} written by the model with no vocabulary and no
 * normalisation, so the same subject arrives spelled several ways across analyses. Without folding,
 * "Precificacao", "precificacao " and "PRECIFICACAO" are three separate rows in a ranking that
 * claims to show what the tenant keeps talking about.
 *
 * <p>The rule mirrors the one the baseline package already applies to Portuguese text, in
 * nlp-baseline's normalize module: lowercase, strip accents through an NFD decomposition that drops
 * the combining marks, replace anything that is neither a letter, a digit nor whitespace with a
 * space, and collapse whitespace runs. Digits are kept — a topic like "Q4" loses its meaning
 * without them.
 *
 * <p><b>What this deliberately does NOT do:</b> it does not merge synonyms. The folded key of
 * "preco" and of "precificacao" are still two different keys, because deciding they are the same
 * subject is a semantic judgement and this is a string function. The API says so in the response
 * ({@code matching: LEXICAL}) and the panel says so on screen, so nobody reads the ranking as a
 * clustering it is not.
 */
public final class TopicNormalizer {

    private TopicNormalizer() {}

    /** Folded counting key, or an empty string when nothing countable is left. */
    public static String fold(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(raw.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean pendingSpace = false;
        for (int i = 0; i < decomposed.length(); i++) {
            char ch = decomposed.charAt(i);
            if (isCombiningMark(ch)) {
                continue;
            }
            if (Character.isLetterOrDigit(ch)) {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(ch);
            } else {
                pendingSpace = true;
            }
        }
        return out.toString();
    }

    /** The accents the NFD decomposition left behind as standalone characters. */
    private static boolean isCombiningMark(char ch) {
        int type = Character.getType(ch);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}
