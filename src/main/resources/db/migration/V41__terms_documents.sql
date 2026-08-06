-- Terms of service + privacy policy documents. One row per
-- (doc_type, version); the "current" version per doc_type is the highest
-- version whose effective_at has passed, so a document becomes enforceable the
-- moment a row is published with an effective_at already in the past.

create type terms_doc_type as enum ('TERMS_OF_SERVICE', 'PRIVACY_POLICY');

create table terms_versions (
    id           bigint generated always as identity primary key,
    doc_type     terms_doc_type not null,
    version      int not null,
    title        text not null,
    body         text not null,
    effective_at timestamptz not null,
    created_at   timestamptz not null default now(),
    unique (doc_type, version)
);

create index terms_versions_current_idx on terms_versions (doc_type, version desc);

comment on table terms_versions is
    'Versioned terms/privacy documents. Current version per doc_type = max(version) with effective_at <= now().';

-- No document is seeded. v1 of the terms of service and of the privacy policy
-- once lived here, and legal text is the clearest case of deployment-specific
-- content rather than schema: the wording binds one operator to one set of users,
-- it is reviewed and revised on its own schedule, and a revision is a new row
-- rather than a new migration. An operator bootstrap script publishes the
-- documents; the dev/test seeder supplies short placeholders so the consent gate
-- has something to enforce. Signup fails closed with a stated reason while the
-- table is empty, so an unpublished deployment cannot let a consent-free account
-- through.
