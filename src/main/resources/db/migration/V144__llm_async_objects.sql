-- Ownership ledger for the LLM gateway's asynchronous vendor objects (files,
-- batches, videos). The vendor scopes these objects to a workspace, and every
-- key of a business account shares that workspace, so the vendor does not
-- keep one key from reading another key's objects. This table does: the
-- gateway records an intent before it submits, binds the vendor id after the
-- vendor accepts, and asks which ids a key owns before it forwards a read,
-- download, delete, list or reference.
--
-- Two-phase on purpose. An intent that cannot be written means the vendor is
-- never called, so an unreachable api costs no money and leaves no object
-- nobody owns. A PENDING row without a vendor id is either a submit whose
-- outcome the gateway never learned (vendor timeout) or an intent the gateway
-- gave up waiting for; reconciliation tells them apart through event_id.
--
-- Schema only.

create table llm_async_objects (
    id                    bigint generated always as identity primary key,
    -- The intentId on the wire. Only the gateway sees it.
    public_id             uuid not null default gen_random_uuid(),
    -- The owner. No on-delete action, like the other children of
    -- llm_api_keys: a migration that ever deletes key rows must delete these
    -- first.
    key_id                bigint not null references llm_api_keys (id),
    -- The vendor workspace the object lives in, copied from the key when the
    -- intent is written. The key's account binding is immutable once set, so
    -- the copy cannot drift, and vendor-id uniqueness below is scoped to the
    -- workspace without a join. A plain foreign key rather than the
    -- (id, org_id) pair: the key row already pins account and organisation.
    openrouter_account_id bigint not null references openrouter_accounts (id),
    -- Text with a check rather than an enum type: adding a kind is one
    -- constraint swap either way.
    kind                  text not null,
    state                 text not null default 'PENDING',
    vendor_object_id      text,
    -- The gateway's X-Request-Id for the submit, which is the usage event's
    -- event id, so the two join. TEXT for the reason llm_usage_events.event_id
    -- is text.
    event_id              text not null,
    -- Bound over the usage channel after the synchronous bind failed.
    bound_late            boolean not null default false,
    -- Stored size and vendor-stated expiry, where the kind has them. Recorded
    -- now; quota and retention decisions read them later.
    size_bytes            bigint,
    expires_at            timestamptz,
    release_reason        text,
    created_at            timestamptz not null default now(),
    bound_at              timestamptz,
    released_at           timestamptz,

    constraint llm_async_objects_kind_check
        check (kind in ('FILE', 'BATCH', 'VIDEO')),
    constraint llm_async_objects_state_check
        check (state in ('PENDING', 'OWNED', 'RELEASED')),
    constraint llm_async_objects_release_reason_check
        check (release_reason in ('DELETED', 'VENDOR_GONE', 'COMPENSATED', 'NOT_CREATED', 'DUPLICATE')),
    constraint llm_async_objects_vendor_object_id_length
        check (vendor_object_id is null or length(vendor_object_id) between 1 and 255),
    constraint llm_async_objects_size_nonnegative
        check (size_bytes is null or size_bytes >= 0),
    -- Bound means the vendor id is attached; the two never disagree.
    constraint llm_async_objects_bound_has_vendor_id
        check ((vendor_object_id is null) = (bound_at is null)),
    constraint llm_async_objects_pending_unbound
        check (state <> 'PENDING' or bound_at is null),
    constraint llm_async_objects_owned_bound
        check (state <> 'OWNED' or bound_at is not null),
    -- RELEASED, released_at and release_reason come and go together.
    constraint llm_async_objects_released_at
        check ((state = 'RELEASED') = (released_at is not null)),
    constraint llm_async_objects_released_reason
        check ((state = 'RELEASED') = (release_reason is not null)),
    -- A release that names a vendor object was bound; one that undoes an
    -- intent was not. A duplicate intent's vendor id belongs to the row that
    -- already owns it.
    constraint llm_async_objects_release_reason_bound
        check (release_reason is null
            or (release_reason in ('DELETED', 'VENDOR_GONE', 'COMPENSATED')) = (bound_at is not null)),
    constraint llm_async_objects_bound_late_bound
        check (not bound_late or bound_at is not null)
);

create unique index llm_async_objects_public_id_key on llm_async_objects (public_id);
-- A live vendor id has one owner per workspace. Released rows are outside it,
-- so a vendor that reused an id after deletion cannot wedge a bind.
create unique index llm_async_objects_live_vendor_id_key
    on llm_async_objects (openrouter_account_id, kind, vendor_object_id)
    where vendor_object_id is not null and state <> 'RELEASED';
-- The lookup: which of these ids does this key own.
create index llm_async_objects_owned_lookup_idx
    on llm_async_objects (key_id, kind, vendor_object_id)
    where state = 'OWNED';
-- A key's objects, newest first, for lists and cleanup.
create index llm_async_objects_key_time_idx
    on llm_async_objects (key_id, kind, created_at desc);
-- Unbound intents, for reconciliation and the sweep.
create index llm_async_objects_pending_idx
    on llm_async_objects (created_at)
    where state = 'PENDING';

comment on table llm_async_objects is 'LLM 비동기 객체(파일, 배치, 영상)의 소유 원장. 게이트웨이가 벤더 제출 전에 의도를 기록하고 수락 뒤 벤더 id를 붙이며, 조회와 삭제 전에 이 키의 것인지 묻습니다.';
