package br.com.nora.api.domain.iam;

import java.util.List;
import java.util.Objects;

/** AWS-style IAM policy document. */
public record PolicyDocument(String version, List<PolicyStatement> statements) {

    public PolicyDocument {
        Objects.requireNonNull(version, "version required");
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("statements must not be empty");
        }
        statements = List.copyOf(statements);
    }
}
