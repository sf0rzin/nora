package br.com.nora.api.application.ports;

/**
 * Port for the OAuth 2.0 flow with Google (authorization code + refresh). The infrastructure
 * implementation talks to accounts.google.com/oauth2.googleapis.com; in tests it is stubbed.
 */
public interface GoogleOAuthClient {

    /**
     * Exchanges the authorization code for tokens.
     *
     * @param code authorization code received in the callback
     * @param redirectUri MUST be identical to the one used in the authorization URL
     */
    TokenResponse exchangeCode(String code, String redirectUri);

    /**
     * Renews the access token. Google normally does NOT return a new refresh_token (keeps the
     * current one).
     */
    TokenResponse refresh(String refreshToken);

    /** E-mail of the connected Google account (openid email scope). */
    String userEmail(String accessToken);

    /**
     * @param refreshToken may be null on refresh (Google does not rotate by default)
     * @param expiresInSeconds validity of the access token from now
     */
    record TokenResponse(
            String accessToken, String refreshToken, long expiresInSeconds, String scope) {}
}
