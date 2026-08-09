-- V007: adds the attributes field (JSONB) to the meetings table.
--
-- attributes stores arbitrary key/value pairs attached to each meeting
-- (e.g.: department=Vendas, region=BR-SP). These attributes are read by the
-- PolicyEvaluator via requestContext when a policy declares StringEquals
-- conditions (ADR 0007), allowing fine-grained Enterprise visibility scoping
-- (US19).
--
-- An empty default for existing rows preserves current behaviour: meetings
-- without attributes simply do not satisfy conditions based on them.
ALTER TABLE meetings
    ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}'::jsonb;
