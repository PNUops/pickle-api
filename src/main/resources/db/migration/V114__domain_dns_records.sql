-- Platform subdomains used to resolve through one hand-made wildcard record in
-- the zone, so the platform never had to know whether a name existed in DNS.
-- With the zone moving to a provider the platform can drive, each platform
-- subdomain gets its own A record, written when the domain goes up and removed
-- when it comes down. The row has to remember where that record stands,
-- because the write happens outside the transaction that changes the domain
-- and can fail on its own: the vhost can be rendered while the name does not
-- resolve, or the name can be gone while the row still believes it exists.
--
-- Three columns rather than one. The status is what the console shows and the
-- reconciler reads; the error says why FAILED is FAILED (a provider message,
-- or "not configured"); the applied time is when the record was last confirmed
-- to exist, which is the one fact a person debugging "the name does not
-- resolve" wants first.
--
-- No backfill. Every existing platform row keeps NONE, which is literally
-- true: the platform has written no record for it. The admin resync adds what
-- is missing for the live ones and moves them to APPLIED; there is nothing a
-- migration could measure here.

create type domain_dns_status as enum ('NONE', 'PENDING', 'APPLIED', 'FAILED');

alter table domains
    add column dns_status domain_dns_status not null default 'NONE',
    add column dns_last_error text,
    add column dns_applied_at timestamptz;

-- One rule per constraint. A custom domain is the user's own zone and the
-- platform must never hold a record for it, so its status can only ever be
-- NONE; the other two pin the companion columns to the status they describe,
-- so a row cannot claim FAILED without saying why or APPLIED without saying
-- when. Compared as text so the labels are never materialised as enum values
-- in the transaction that created the type.
alter table domains
    add constraint domains_dns_status_custom_check
        check (kind::text <> 'CUSTOM' or dns_status::text = 'NONE'),
    add constraint domains_dns_last_error_check
        check ((dns_status::text = 'FAILED') = (dns_last_error is not null)),
    add constraint domains_dns_applied_at_check
        check ((dns_status::text = 'NONE' and dns_applied_at is null)
            or (dns_status::text = 'APPLIED' and dns_applied_at is not null)
            or dns_status::text in ('PENDING', 'FAILED'));

comment on column domains.dns_status is
    '플랫폼이 이 이름의 A 레코드를 DNS 제공자에 어떻게 두고 있는지. NONE은 레코드를 쓴 적이 없거나 지웠다는 뜻이고, PENDING은 원하는 상태(생성 또는 삭제)가 아직 반영되지 않았다는 뜻, APPLIED는 레코드가 있다고 확인했다는 뜻, FAILED는 마지막 시도가 실패해 dns_last_error에 이유가 있다는 뜻. 커스텀 도메인은 사용자 소유 존이라 항상 NONE.';
comment on column domains.dns_last_error is
    'DNS 레코드 생성·삭제의 마지막 실패 이유. FAILED일 때만 값이 있고 다음 성공에 지워진다.';
comment on column domains.dns_applied_at is
    'A 레코드가 있다고 마지막으로 확인한 시각. NONE이면 null, APPLIED면 반드시 있으며, 삭제가 실패해 FAILED가 된 행은 레코드가 아직 있으므로 값을 유지한다.';
