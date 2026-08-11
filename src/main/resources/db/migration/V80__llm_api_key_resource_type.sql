-- The LLM API key joins the resource types.
--
-- Alone in its own file on purpose: PostgreSQL refuses to use an enum value in
-- the same transaction that added it, and Flyway runs one file per
-- transaction. Everything that references this value therefore starts at V81.
alter type resource_type add value if not exists 'LLM_API_KEY';
