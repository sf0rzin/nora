package br.com.nora.api.domain.iam;

import java.util.List;
import java.util.Objects;

/**
 * AWS-style IAM policy document.
 *
 * <p><b>The cardinality caps are an availability control, not tidiness.</b> {@link PolicyEvaluator}
 * walks every statement of every policy attached to the principal on EVERY authorization decision,
 * and US44 added the permission boundary as a second walk on top. A document is written by whoever
 * holds {@code iam:policy:create}, so without a ceiling one delegated admin can make every request
 * of every user in the tenant slower, permanently, with a single valid document. AWS bounds the
 * same thing by policy size (6144 characters for a managed policy); this bounds the two numbers
 * that actually drive the evaluator's cost.
 *
 * <p>The limits are deliberately far above any hand-written policy — the whole built-in catalogue
 * fits in one statement each — so they cannot be hit by legitimate use and exist to stop a document
 * that is pathological by construction.
 */
public record PolicyDocument(String version, List<PolicyStatement> statements) {

    /** Ceiling on statements in one document. See the class note. */
    public static final int MAX_STATEMENTS = 100;

    /** Ceiling on the length of the {@code version} string, which is a date-like label. */
    private static final int MAX_VERSION_LENGTH = 64;

    public PolicyDocument {
        Objects.requireNonNull(version, "version required");
        if (version.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException(
                    "version must be at most " + MAX_VERSION_LENGTH + " characters");
        }
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("statements must not be empty");
        }
        if (statements.size() > MAX_STATEMENTS) {
            throw new IllegalArgumentException(
                    "policy document must have at most " + MAX_STATEMENTS + " statements");
        }
        statements = List.copyOf(statements);
    }
}
