-- V008: indice GIN sobre meetings.attributes para acelerar conditions filtradas em SQL.
--
-- Quando a Fase 1 mover o predicado de attributes (US19) para a query
-- (ex.: attributes @> '{"department":"Vendas"}'), este indice eh requisito
-- para evitar full scan. jsonb_path_ops eh mais compacto e rapido para
-- consultas de containment (`@>`), que sao o caso comum de IAM conditions.
CREATE INDEX IF NOT EXISTS idx_meetings_attributes_gin
    ON meetings USING GIN (attributes jsonb_path_ops);
