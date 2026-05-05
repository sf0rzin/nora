package br.com.nora.api.application.identity;

/** Excecoes do dominio de identidade que viram respostas HTTP padronizadas. */
public sealed class AuthException extends RuntimeException
        permits AuthException.EmailAlreadyTaken,
                AuthException.InvalidCredentials,
                AuthException.EmailNotVerified,
                AuthException.TokenInvalid,
                AuthException.UserDisabled {

    private final String code;

    protected AuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class EmailAlreadyTaken extends AuthException {
        public EmailAlreadyTaken() {
            super("EMAIL_ALREADY_TAKEN", "An account with this e-mail already exists.");
        }
    }

    public static final class InvalidCredentials extends AuthException {
        public InvalidCredentials() {
            super("INVALID_CREDENTIALS", "Invalid e-mail or password.");
        }
    }

    public static final class EmailNotVerified extends AuthException {
        public EmailNotVerified() {
            super("EMAIL_NOT_VERIFIED", "E-mail address has not been verified yet.");
        }
    }

    public static final class TokenInvalid extends AuthException {
        public TokenInvalid() {
            super("TOKEN_INVALID", "Token is invalid, expired or already used.");
        }
    }

    public static final class UserDisabled extends AuthException {
        public UserDisabled() {
            super("USER_DISABLED", "User account is disabled.");
        }
    }
}
