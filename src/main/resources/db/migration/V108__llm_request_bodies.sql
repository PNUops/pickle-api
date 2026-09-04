-- Opted-in prompt and response text, stored at last. The gateway has captured
-- and shipped this since 2026-08-11; this side counted the records and threw
-- them away, because the storage, its key and its retention were one decision
-- nobody had made. They are made now: the readers are the key's access-list
-- holders, the retention is 30 days, and the text is encrypted under a keyring
-- of its own.
--
-- Schema only.

create table llm_request_bodies (
    id                 bigint generated always as identity primary key,
    public_id          uuid not null,
    -- The gateway's idempotency key, shared with llm_usage_events.event_id so
    -- the two join. TEXT, not uuid, for the reason recorded on that column: a
    -- gateway whose random source fails falls back to a timestamp-derived id,
    -- and a uuid column would reject that row. A rejected row here is worse
    -- than there -- the bodies channel never retries, so it is text lost.
    event_id           text not null,
    -- NOT NULL, unlike llm_usage_events.key_id. An event that resolved to no
    -- key is still evidence of a client looping on a bad token, but a body
    -- that resolved to no key is unreadable forever: every read path is scoped
    -- to a key's access list, so nothing would ever open it. Storing it would
    -- keep personal text that answers to nobody.
    key_id             bigint not null references llm_api_keys (id),
    -- Ciphertext, framed as llmb-v1:<keyId>:<base64 iv>:<base64 ct>. TEXT and
    -- not jsonb: encryption already removed everything jsonb offers (no GIN,
    -- no ->, no containment), so a jsonb wrapper would buy nothing. The
    -- contract's "expect either shape" promise moves to the plaintext instead
    -- -- what is encrypted is canonical JSON, and the read path parses it back
    -- and hands out whichever shape it finds. Never infer the shape from
    -- request_truncated: the flag says the tail was cut, not what the value is.
    request_enc        text,
    response_enc       text,
    -- Separate flags because a cut prompt and a cut answer mean different
    -- things to whoever reads the record. A truncated request is not the
    -- messages array -- cutting JSON mid-way produces nothing a parser takes --
    -- so it arrives as a JSON string holding the prefix.
    request_truncated  boolean not null default false,
    response_truncated boolean not null default false,
    -- Plaintext sizes, so a list can say how big a record is without decrypting
    -- a page of them, and so capacity questions are answerable in SQL. Length
    -- already leaks through the ciphertext, so this discloses nothing new.
    request_bytes      int not null default 0,
    response_bytes     int not null default 0,
    -- Which keyring entry opens this row. It is inside the frame too, but a
    -- column answers "may this key be retired yet" without parsing strings --
    -- the operational half of deciding to carry a keyring at all.
    cipher_key_id      text not null,
    requested_at       timestamptz not null,
    received_at        timestamptz not null default now()
);

create unique index llm_request_bodies_public_id_key on llm_request_bodies (public_id);
-- One record per event. The ingest inserts on conflict do nothing: a duplicate
-- carries the same text, and re-encrypting it would churn the row for nothing.
create unique index llm_request_bodies_event_id_key on llm_request_bodies (event_id);
-- The read path: one key's records, newest first.
create index llm_request_bodies_key_time_idx on llm_request_bodies (key_id, requested_at desc, id desc);
-- The sweep deletes on requested_at OR received_at, and needs both. requested_at
-- alone cannot keep the 30-day promise: a gateway with a skewed clock reports a
-- future time and that row would never expire. received_at alone would delete on
-- a column the promise is not written in.
create index llm_request_bodies_requested_at_idx on llm_request_bodies (requested_at);
create index llm_request_bodies_received_at_idx on llm_request_bodies (received_at);
