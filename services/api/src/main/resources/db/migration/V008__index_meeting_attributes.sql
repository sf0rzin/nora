-- V008: GIN index on meetings.attributes to speed up conditions filtered in SQL.
--
-- When Phase 1 moves the attributes predicate (US19) into the query
-- (e.g.: attributes @> '{"department":"Vendas"}'), this index is a requirement
-- to avoid a full scan. jsonb_path_ops is more compact and faster for
-- containment queries (`@>`), which are the common case for IAM conditions.
CREATE INDEX IF NOT EXISTS idx_meetings_attributes_gin
    ON meetings USING GIN (attributes jsonb_path_ops);
