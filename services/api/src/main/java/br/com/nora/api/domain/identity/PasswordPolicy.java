package br.com.nora.api.domain.identity;

/**
 * Politica de senha do MVP. Mantida no dominio para que infra (controller/JPA) nao precise repetir
 * regras. Pos-MVP pode evoluir para zxcvbn ou checagem em listas vazadas.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {}

    /**
     * Valida uma senha em texto puro. Lanca {@link IllegalArgumentException} se nao atender ao
     * minimo do MVP: tamanho >= MIN_LENGTH, com pelo menos uma letra e um digito.
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
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
            if (hasLetter && hasDigit) break;
        }
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("password must contain letters and digits");
        }
    }
}
