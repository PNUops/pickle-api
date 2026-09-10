-- The domain joins the resource types, and a domain kind arrives for names the
-- platform issues but does not serve.
--
-- Alone in its own file on purpose: PostgreSQL refuses to use an enum value in
-- the same transaction that added it, and Flyway runs one file per
-- transaction. Everything that references either value therefore starts at
-- V116. The two do not reference each other, so they share this file.
--
-- EXTERNAL is a name under a platform root whose records point wherever its
-- owner says. Every kind before it resolved to this platform's reverse proxy,
-- which is why so much of the publishing code used to ask "is this CUSTOM"
-- when it meant "does the proxy serve this".
alter type resource_type add value if not exists 'DOMAIN';
alter type domain_kind add value if not exists 'EXTERNAL';
