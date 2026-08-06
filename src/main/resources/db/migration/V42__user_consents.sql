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

-- No backfill. One lived here: it granted every existing account consent to every
-- v1 document so that accounts predating the consent gate were not locked out. It
-- selects from `users`, and no migration inserts a user, so on any database built
-- from these files it writes zero rows -- it only ever did work on the one
-- already-populated database it was written against. Accounts created since record
-- their consent at signup.
