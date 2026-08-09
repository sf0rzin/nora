package br.com.nora.api.application.ports;

import java.util.List;

/**
 * Port for Telegram's Bot API used by the pairing (wave 2). Telegram has NO OAuth: the app has ONE
 * global bot ({@code NORA_TELEGRAM_BOT_TOKEN}) and each tenant connects by proving possession of a
 * short code — the user opens the deep link {@code t.me/<bot>?start=<code>} and the backend finds
 * the {@code /start <code>} message via {@code getUpdates}, storing the {@code chat_id} as the
 * connection. The infrastructure implementation handles the HTTP (and the in-memory offset); in
 * tests it is stubbed.
 */
public interface TelegramBotApi {

    /** Bot username (without @), via {@code getMe} — cached by the implementation. */
    String botUsername();

    /**
     * One-shot fetch of the {@code /start <payload>} messages received by the bot since the last
     * call ({@code getUpdates} with an in-memory offset). Messages without a payload are ignored.
     */
    List<StartCommand> pollStartCommands();

    /**
     * @param chatId chat the /start came from (becomes the connection's access token)
     * @param payload code the deep link embedded in the /start
     * @param fromDisplay display name of the sender (external account in the hub); may be null
     */
    record StartCommand(String chatId, String payload, String fromDisplay) {}
}
