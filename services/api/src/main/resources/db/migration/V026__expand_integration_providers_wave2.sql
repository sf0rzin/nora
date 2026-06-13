-- V026 — Libera os provedores da onda 2 (Microsoft, Telegram, Trello) no CHECK de
-- integration_connections.provider (V025 fixava a lista da onda 1).
--
-- Microsoft é OAuth2 code-flow COM refresh; Telegram conecta por pareamento de código
-- (access_token = chat_id do bot); Trello por token colado pelo usuário. Todos usam a MESMA
-- tabela/cifra (AES-GCM via TokenCipher) — sem mudança de RLS/índices, só a lista de valores.

ALTER TABLE integration_connections
    DROP CONSTRAINT integration_connections_provider_check;

ALTER TABLE integration_connections
    ADD CONSTRAINT integration_connections_provider_check
    CHECK (provider IN ('google', 'slack', 'github', 'notion', 'todoist', 'linear',
                        'microsoft', 'telegram', 'trello'));
