package br.com.nora.api.application.ports;

/**
 * Port for Trello's REST API used by the pasted-token connection (wave 2). Server-side Trello 1.0a
 * is not worth the cost: the user generates the token on Trello's authorize page (our app's key +
 * {@code response_type=token}) and pastes it into the hub — the backend validates the token here
 * before persisting it encrypted. The infrastructure implementation holds the app's API key (env
 * {@code TRELLO_API_KEY}); in tests it is stubbed.
 */
public interface TrelloApi {

    /**
     * Validates the pasted token by calling {@code GET /1/members/me} and returns the member's
     * display name (external account in the hub). Invalid token = {@code ProviderError} with clear
     * guidance.
     */
    String validateToken(String token);
}
