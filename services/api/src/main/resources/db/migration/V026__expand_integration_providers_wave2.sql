-- V026 — Allows the wave 2 providers (Microsoft, Telegram, Trello) in the CHECK on
-- integration_connections.provider (V025 pinned the wave 1 list).
--
-- Microsoft is OAuth2 code-flow WITH refresh; Telegram connects by code pairing
-- (access_token = the bot's chat_id); Trello by a token pasted by the user. All use the SAME
-- table/cipher (AES-GCM via TokenCipher) — no RLS/index change, only the list of values.

ALTER TABLE integration_connections
    DROP CONSTRAINT integration_connections_provider_check;

ALTER TABLE integration_connections
    ADD CONSTRAINT integration_connections_provider_check
    CHECK (provider IN ('google', 'slack', 'github', 'notion', 'todoist', 'linear',
                        'microsoft', 'telegram', 'trello'));
