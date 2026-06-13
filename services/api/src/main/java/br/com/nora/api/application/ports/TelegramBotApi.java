package br.com.nora.api.application.ports;

import java.util.List;

/**
 * Porta da Bot API do Telegram usada pelo pareamento (onda 2). O Telegram NÃO tem OAuth: o app tem
 * UM bot global ({@code NORA_TELEGRAM_BOT_TOKEN}) e cada tenant conecta provando posse de um código
 * curto — o usuário abre o deep link {@code t.me/<bot>?start=<código>} e o backend encontra a
 * mensagem {@code /start <código>} via {@code getUpdates}, guardando o {@code chat_id} como
 * conexão. A implementação infrastructure cuida do HTTP (e do offset em memória); nos testes é
 * stubada.
 */
public interface TelegramBotApi {

    /** Username do bot (sem @), via {@code getMe} — cacheado pela implementação. */
    String botUsername();

    /**
     * Busca one-shot das mensagens {@code /start <payload>} recebidas pelo bot desde a última
     * chamada ({@code getUpdates} com offset em memória). Mensagens sem payload são ignoradas.
     */
    List<StartCommand> pollStartCommands();

    /**
     * @param chatId chat de onde veio o /start (vira o access token da conexão)
     * @param payload código que o deep link embutiu no /start
     * @param fromDisplay nome de exibição de quem mandou (conta externa no hub); pode ser nulo
     */
    record StartCommand(String chatId, String payload, String fromDisplay) {}
}
