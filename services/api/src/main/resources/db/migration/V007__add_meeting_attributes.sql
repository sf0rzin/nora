-- V007: adiciona campo attributes (JSONB) a tabela meetings.
--
-- attributes armazena pares chave/valor arbitrarios anexados a cada reuniao
-- (ex.: department=Vendas, region=BR-SP). Esses atributos sao lidos pelo
-- PolicyEvaluator via requestContext quando uma policy declara conditions
-- StringEquals (ADR 0007), permitindo escopo fino de visibilidade Enterprise
-- (US19).
--
-- Default vazio para rows existentes preserva o comportamento atual: meetings
-- sem attributes simplesmente nao satisfazem conditions baseadas neles.
ALTER TABLE meetings
    ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}'::jsonb;
