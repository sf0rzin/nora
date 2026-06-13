package br.com.nora.api.application.ports;

/**
 * Porta da REST API do Trello usada pela conexão por token colado (onda 2). O Trello 1.0a
 * server-side não vale o custo: o usuário gera o token na página de authorize do Trello (key do
 * nosso app + {@code response_type=token}) e cola no hub — o backend valida o token aqui antes de
 * persistir cifrado. A implementação infrastructure guarda a API key do app (env {@code
 * TRELLO_API_KEY}); nos testes é stubada.
 */
public interface TrelloApi {

    /**
     * Valida o token colado chamando {@code GET /1/members/me} e devolve o nome de exibição do
     * membro (conta externa no hub). Token inválido = {@code ProviderError} com orientação clara.
     */
    String validateToken(String token);
}
