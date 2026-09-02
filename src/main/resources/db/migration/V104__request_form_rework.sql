-- The request form asked for a usage period as two optional dates and validated
-- only that one came after the other, so a request could end in the past and be
-- accepted. Making the end date mandatory would have handed every student a bare
-- date picker and this server every date to validate, which is the wrong trade:
-- almost every real period is a term or a vacation the operator already knows.
--
-- So the period becomes a small catalogue. An operator publishes the periods on
-- offer ("2026 1학기", "여름방학"), the requester picks one, and typing a date
-- stays available for the case the catalogue does not cover. A row whose
-- end_date is null is the indefinite period, which is how a campus service that
-- must not expire gets requested: it exists only if an operator publishes it,
-- so "who may ask for indefinite" stays an operator decision rather than a
-- checkbox on the form.
--
-- The start date goes away entirely. Nothing scheduled on it: no query filtered
-- by it, no provisioning path read it, and approval enqueues the build
-- immediately regardless of what it said. It was a date that was recorded and
-- then ignored. If deferred creation is ever built, it comes back with the
-- logic that gives it meaning.
--
-- The spec presets gain an explicit order. Sorting them by size was right while
-- they were a ladder from small to large; they are becoming shapes (compute
-- heavy, memory heavy) where no arithmetic tells you which comes first.
--
-- Two dead columns leave with this. The request form's domain axis was retired
-- in 2026-08 and new requests have always written null since, and there is no
-- production data to preserve.

alter table vm_flavors
    add column display_order int not null default 0;

comment on column vm_flavors.display_order is
    '신청 화면에서의 표시 순서. 값이 같으면 id 순.';

-- Periods on offer. Not seeded here: which terms and vacations a deployment
-- offers is operator policy against a real calendar, the dates are absolute and
-- so need updating every term, and the admin console owns the write path. Same
-- reasoning as vm_flavors (V58).
create table request_period_presets (
    id            bigint generated always as identity primary key,
    public_id     uuid not null default gen_random_uuid(),
    name          text not null,
    display_name  text not null,
    -- null = 무기한. The column is the only place that distinguishes an
    -- indefinite period from a dated one.
    end_date      date,
    status        catalog_status not null default 'ACTIVE',
    display_order int not null default 0,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint request_period_presets_name_nonblank check (btrim(name) <> ''),
    constraint request_period_presets_display_name_nonblank check (btrim(display_name) <> '')
);

create unique index request_period_presets_public_id_uq
    on request_period_presets (public_id);
create unique index request_period_presets_name_uq
    on request_period_presets (lower(name));

-- Which catalogue row the requester picked, when they picked one. The end date
-- itself stays on requests.req_end_date: an operator correcting a preset's date
-- next term must not move the period of a request already submitted, so the
-- date is copied at submission and this column records only where it came from.
alter table requests
    add column req_period_preset_id bigint references request_period_presets (id);

comment on column requests.req_period_preset_id is
    '신청 시점에 고른 기간 항목. 직접 입력한 경우 null이고, 무기한이면 req_end_date가 null이다.';

-- The start date and the constraint that was its only consumer.
alter table requests
    drop constraint chk_requests_date_order,
    drop column req_start_date;

alter table vm_request_details
    drop column desired_subdomain,
    drop column root_domain;
