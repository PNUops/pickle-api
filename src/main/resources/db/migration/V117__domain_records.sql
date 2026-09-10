-- The records a domain's owner writes into the platform's zone.
--
-- One row per record SET, not per value. That is the unit the provider takes
-- (name, type, values, ttl) and therefore the unit that succeeds or fails, so
-- the status, the error and the applied time describe exactly one thing. Split
-- per value, two rows of the same set could disagree about whether the set is
-- in the zone, which is a state DNS has no way to be in.

create type domain_record_type as enum ('A', 'AAAA', 'CNAME', 'TXT');
create type domain_record_status as enum ('PENDING', 'APPLIED', 'FAILED', 'REMOVED');

create table domain_records (
    id         bigint generated always as identity primary key,
    domain_id  bigint not null references domains (id),
    -- Relative to the domain's own name: '' is the name itself, 'www' is one
    -- label under it. Stored relative so the row says what its owner typed and
    -- the absolute name is composed where it is needed.
    name       text not null,
    type       domain_record_type not null,
    -- Every value of the set, in order. Order is data for some types and
    -- cosmetic for others, so it is preserved rather than sorted. Named as the
    -- zone API names it, and not "values", which is a reserved word here.
    --
    -- The only array column in this schema; every other multi-value column is
    -- jsonb. Chosen anyway because this one maps one-to-one onto the provider
    -- call it exists to feed, and jsonb would make cardinality and element
    -- checks read as JSON functions over what is a list of strings. Recorded
    -- rather than left as an accident, since it is the first of its kind here.
    rrdatas    text[] not null,
    ttl        integer not null,
    status     domain_record_status not null default 'PENDING',
    last_error text,
    applied_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- One live set per name and type, which is what DNS itself allows. REMOVED
-- rows are excluded so a set can be taken down and put back, the same shape as
-- domains_fqdn_live_idx.
create unique index domain_records_live_idx
    on domain_records (domain_id, name, type) where status <> 'REMOVED';
create index domain_records_domain_id_idx on domain_records (domain_id);

-- Bounds on one set, which is not the same as bounds on one owner. A set
-- carries at most ten values and a sane TTL; how many SETS a domain may hold
-- is what actually decides whether an owner can exhaust the zone's quota, and
-- a CHECK sees one row and cannot count the others. That cap belongs where the
-- set is accepted, and saying so here keeps this from reading as the quota
-- defence it is not.
--
-- A NULL element is refused too. The column being NOT NULL says the array
-- exists, not that its elements do, and cardinality counts a NULL element like
-- any other -- so '{NULL}' would satisfy both of the other rules and reach the
-- provider as a value that is not one.
alter table domain_records
    add constraint domain_records_values_check
        check (cardinality(rrdatas) between 1 and 10
               and array_position(rrdatas, null) is null),
    add constraint domain_records_ttl_check
        check (ttl between 60 and 86400),
    -- The owner name, folded and bounded the way DnsNames.relative folds it
    -- before the provider sees it. Without this the unique index below leaks:
    -- 'www' and 'WWW' are two live rows to a case-sensitive index and one
    -- record set to the zone. The trailing dot is refused for the same reason
    -- an absolute name here would be ambiguous about whose zone it names.
    add constraint domain_records_name_check
        check (name = '' or name ~ '^[a-z0-9_]([a-z0-9_-]*[a-z0-9])?(\.[a-z0-9_]([a-z0-9_-]*[a-z0-9])?)*$');

-- CNAME exclusivity is NOT here, and the absence is deliberate. RFC 1034 says
-- a name carrying a CNAME may carry nothing else, which is a rule about the
-- other rows at the same name; a check constraint sees one row and an
-- exclusion constraint cannot express "of a different type". It is enforced
-- where the set is accepted. Recorded so the next reader does not conclude the
-- rule was forgotten.
comment on table domain_records is
    '도메인 소유자가 플랫폼 존에 쓰는 레코드. 한 행이 rrset 하나이고 적용 단위와 같다.';
comment on column domain_records.name is
    '도메인 이름 기준 상대 이름. 빈 문자열은 도메인 이름 자신이다. 절대 이름은 쓰지 않는다.';

-- A third child of domains. V64 hard-deletes a retired root's rows as
-- routes -> certificates -> domains; that order now leaves this table behind
-- and the delete fails on the foreign key. Written here because the next root
-- retirement will reach for V64 as a template.

-- The revision the apply job guards on, the same pattern as routes.generation.
--
-- The counter is per domain while the rows above are per record set, and the
-- two granularities are deliberate: an edit changes a domain's record set as a
-- whole, so one push carries every set the domain has and one counter orders
-- those pushes. The per-row status is the outcome of that push for each set,
-- which can differ between sets inside a single push. Read the other way
-- round -- a counter per set -- two edits to one domain would race each other
-- rather than the later one superseding the earlier.
alter table domains add column records_generation bigint not null default 0;
