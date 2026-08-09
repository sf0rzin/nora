package br.com.nora.api.application.identity;

/** Exceptions of the identity domain that turn into standardized HTTP responses. */
public sealed class AuthException extends RuntimeException
        permits AuthException.EmailAlreadyTaken,
                AuthException.InvalidCredentials,
                AuthException.EmailNotVerified,
                AuthException.TokenInvalid,
                AuthException.RefreshTokenInvalid,
                AuthException.UserDisabled,
                AuthException.RateLimited,
                AuthException.AccountNotPersonal {

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

    /**
     * Refresh token not found, expired or revoked. Distinct from {@link TokenInvalid} (used for
     * one-time e-mail/reset tokens) because it triggers a logout flow in the client, not a 400 with
     * a human retry.
     */
    public static final class RefreshTokenInvalid extends AuthException {
        public RefreshTokenInvalid() {
            super("REFRESH_TOKEN_INVALID", "Refresh token is invalid, expired or revoked.");
        }
    }

    public static final class UserDisabled extends AuthException {
        public UserDisabled() {
            super("USER_DISABLED", "User account is disabled.");
        }
    }

    public static final class RateLimited extends AuthException {
        public RateLimited() {
            super("RATE_LIMITED", "Too many requests. Try again in a few minutes.");
        }
    }

    /**
     * Account deletion blocked: the tenant has other users (Enterprise). Deletion via settings only
     * covers the Core personal tenant (1 user); shared workspaces require their own administrative
     * flow.
     */
    public static final class AccountNotPersonal extends AuthException {
        public AccountNotPersonal() {
            super(
                    "ACCOUNT_TENANT_SHARED",
                    "Workspace has other users; account deletion only applies to personal"
                            + " workspaces.");
        }
    }

    public static AuthException rateLimited() {
        return new RateLimited();
    }
}
