-- M4A HTTP service publishing (docs/plan/06-domains-tls.md, docs/plan/02-data-model.md
-- "Publishing"). Three tables — domains, routes, certificates — plus the DB-owned
-- monotonic generation the proxy-agent contract relies on (docs/api/internal.md
-- Link 2). Also extends the approval review so the admin finalizes the platform
-- subdomain name at approval time (docs/product-spec §12).

create type domain_kind as enum ('AUTO', 'REQUESTED', 'CUSTOM');
create type domain_status as enum ('PENDING', 'VERIFYING', 'ACTIVE', 'FAILED', 'REMOVED');
create type route_status as enum ('PENDING', 'APPLIED', 'FAILED', 'REMOVED');
create type certificate_kind as enum ('ORIGIN_CA_WILDCARD', 'LETS_ENCRYPT');
create type certificate_status as enum ('ACTIVE', 'RENEWING', 'FAILED', 'REVOKED');

-- One domain per FQDN attached to a VM. Platform subdomains (AUTO/REQUESTED) are
-- ACTIVE on creation; custom domains flow PENDING→VERIFYING→ACTIVE via DNS polling.
create table domains (
    id                 bigint generated always as identity primary key,
    vm_id              bigint not null references vms (id),
    kind               domain_kind not null,
    fqdn               text not null,
    -- Platform subdomains carry their root; custom domains are null.
    root_domain        text,
    -- Custom-domain ownership token (TXT value); null for platform subdomains.
    verification_token text,
    a_verified         boolean not null default false,
    txt_verified       boolean not null default false,
    last_checked_at    timestamptz,
    last_error         text,
    verified_at        timestamptz,
    status             domain_status not null,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

-- FQDN is unique among LIVE domains only: a REMOVED row is kept for history/listing
-- (listDomains has a REMOVED filter) yet must not block re-publishing the same
-- requested subdomain later. This partial unique index is the final enforcement
-- of DOMAIN_FQDN_TAKEN (the approval-time check is best-effort).
create unique index domains_fqdn_live_idx on domains (fqdn) where status <> 'REMOVED';
create index domains_vm_id_idx on domains (vm_id);
create index domains_status_idx on domains (status);

-- Global monotonic generation source (docs/api/internal.md Link 2). A single
-- sequence is monotonic per-FQDN too (each FQDN's generations are a strictly
-- increasing subsequence), and — unlike a per-route counter reset to 1 on
-- re-publish — it can never let a re-created route for a reused FQDN start below
-- the generation the agent last applied, so a stale apply can never resurrect an
-- old vhost onto a reused IP.
create sequence route_generation_seq;

-- One HTTP route per domain in v1. generation is bumped on every desired-state
-- change (publish / port change / custom attach-detach / unpublish); applied_*
-- reflect what the proxy-agent last confirmed.
create table routes (
    id                 bigint generated always as identity primary key,
    domain_id          bigint not null references domains (id),
    target_port        int not null,
    protocol           text not null default 'HTTP',
    status             route_status not null,
    generation         bigint not null,
    applied_generation bigint,
    applied_at         timestamptz,
    -- Last response from proxy-agent (docs/plan/02): audit/debug aid.
    agent_state        jsonb,
    last_error         text,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint routes_protocol_chk check (protocol in ('HTTP')),
    constraint routes_target_port_chk check (target_port between 1 and 65535)
);

create unique index routes_domain_live_idx on routes (domain_id) where status <> 'REMOVED';
create index routes_domain_id_idx on routes (domain_id);
create index routes_status_idx on routes (status);

-- TLS certificates. The shared Cloudflare Origin CA wildcard has domain_id null
-- and a root-domain scope; per-custom-domain Let's Encrypt certs point at the
-- domain and scope the FQDN.
create table certificates (
    id         bigint generated always as identity primary key,
    domain_id  bigint references domains (id),
    kind       certificate_kind not null,
    scope      text not null,
    not_after  timestamptz,
    status     certificate_status not null,
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index certificates_domain_id_idx on certificates (domain_id);

-- Shared platform wildcard (docs/plan/06: Cloudflare Origin CA, 15-year validity,
-- operator-installed once). Tracked as a row for expiry monitoring in the admin
-- certificate list. Default dev root is pickle.pnuops.com (settings
-- allowed_root_domains); additional roots get their own row when introduced.
insert into certificates (domain_id, kind, scope, not_after, status) values
    (null, 'ORIGIN_CA_WILDCARD', '*.pickle.pnuops.com', '2040-01-01T00:00:00+09:00', 'ACTIVE');

-- Admin-finalized platform subdomain (docs/product-spec §12 "승인 시 관리자 최종
-- 부여"). Null granted_subdomain ⇒ AUTO subdomain generated at first publish.
alter table vm_request_reviews
    add column granted_subdomain text,
    add column granted_root_domain text;

-- Profanity/impersonation denylist for subdomain labels (docs/plan/06). Small
-- admin-extendable wordlist; validated alongside reserved_subdomains at approval.
insert into settings (key, value, description) values
    ('profanity_subdomains', '["fuck","shit","porn","sex","admin-official","pnu-official"]'::jsonb,
     'Profanity/impersonation denylist for platform subdomain labels (docs/plan/06). Admin-extendable.');
