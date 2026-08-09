package br.com.nora.api.domain.identity;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Normalized e-mail address (lowercase + trim). */
public final class Email {

    // Simplified RFC 5322, enough for field validation (the real check is by token).
    private static final Pattern PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final String value;

    private Email(String value) {
        this.value = value;
    }

    public static Email of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("email cannot be null");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid email format");
        }
        if (normalized.length() > 254) {
            throw new IllegalArgumentException("email too long");
        }
        return new Email(normalized);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Email e && value.equals(e.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
