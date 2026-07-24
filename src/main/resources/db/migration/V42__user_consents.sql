-- Per-user consent to a specific terms version. A user is "pending
-- consent" for a document when the current version has no matching row here;
-- signup records the initial rows and re-consent adds rows for new versions.

create table user_consents (
    id               bigint generated always as identity primary key,
    user_id          bigint not null references users (id),
    terms_version_id bigint not null references terms_versions (id),
    consented_at     timestamptz not null default now(),
    unique (user_id, terms_version_id)
);

create index user_consents_user_id_idx on user_consents (user_id);

comment on table user_consents is
    'User consent to a specific terms_versions row. Missing current-version row = pending consent.';

-- Backfill: pre-production users (seeded admins + dev accounts) predate consent
-- enforcement, so grant them consent to every existing (v1) document as of now.
-- Without this every existing account would be blocked on the consent gate.
insert into user_consents (user_id, terms_version_id, consented_at)
select u.id, tv.id, now()
  from users u
 cross join terms_versions tv
on conflict (user_id, terms_version_id) do nothing;
