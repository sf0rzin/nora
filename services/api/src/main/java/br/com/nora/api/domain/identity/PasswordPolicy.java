package br.com.nora.api.domain.identity;

/**
 * MVP password policy. Kept in the domain so that infra (controller/JPA) does not have to repeat
 * rules. Post-MVP it can evolve to zxcvbn or a check against leaked lists.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {}

    /**
     * Validates a plain-text password. Throws {@link IllegalArgumentException} if it does not meet
     * the MVP minimum: length >= MIN_LENGTH, with at least one letter and one digit.
     */
    public static void validate(String raw) {
        if (raw == null || raw.length() < MIN_LENGTH || raw.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "password must be between "
                            + MIN_LENGTH
                            + " and "
                            + MAX_LENGTH
                            + " characters");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
            if (hasLetter && hasDigit) {
                break;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("password must contain letters and digits");
        }
    }
}
