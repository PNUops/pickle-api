-- A missing policy row remains distinct from an explicitly empty allowlist.
-- Do not populate environment policy or campus ranges in this migration.
create table domain_source_policies (
    domain_id bigint primary key references domains(id) on delete cascade,
    revision bigint not null check (revision > 0),
    allowed_cidrs jsonb not null,
    updated_by bigint not null references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint domain_source_policies_cidrs_check check (
        jsonb_typeof(allowed_cidrs) = 'array'
        and jsonb_array_length(allowed_cidrs) <= 128
        and not jsonb_path_exists(allowed_cidrs, '$[*] ? (@.type() != "string")')
    )
);

create table port_mapping_source_policies (
    port_mapping_id bigint primary key references port_mappings(id) on delete cascade,
    revision bigint not null check (revision > 0),
    allowed_cidrs jsonb not null,
    updated_by bigint not null references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint port_mapping_source_policies_cidrs_check check (
        jsonb_typeof(allowed_cidrs) = 'array'
        and jsonb_array_length(allowed_cidrs) <= 128
        and not jsonb_path_exists(allowed_cidrs, '$[*] ? (@.type() != "string")')
    )
);

alter table relays
    add column capabilities jsonb not null default '[]'::jsonb,
    add column capabilities_observed_at timestamptz,
    add constraint relays_capabilities_check check (
        jsonb_typeof(capabilities) = 'array'
        and jsonb_array_length(capabilities) <= 32
        and not jsonb_path_exists(capabilities, '$[*] ? (@.type() != "string")')
    );

-- Legacy acknowledgements must not confirm a policy introduced at activation.
alter table routes
    add column source_policy_generation bigint,
    add constraint routes_source_policy_generation_check check (
        source_policy_generation is null
        or (source_policy_generation > 0 and source_policy_generation <= generation)
    );

alter table port_mappings
    add column source_policy_generation bigint,
    add constraint port_mappings_source_policy_generation_check check (
        source_policy_generation is null
        or (source_policy_generation > 0
            and source_policy_generation <= last_change_generation)
    );

comment on table domain_source_policies is
    '프록시를 경유하는 도메인별 출발지 정책. 같은 도메인의 재공개에서 유지하며 DNS 전용 도메인에는 사용하지 않는다.';
comment on table port_mapping_source_policies is
    '포트 매핑별 출발지 정책. 빈 CIDR 목록은 신규 접속 거부를 뜻한다.';
comment on column relays.capabilities is
    '최근 relay sync에서 받은 지원 기능 목록. 누락된 보고는 빈 목록으로 처리한다.';
comment on column relays.capabilities_observed_at is
    '지원 기능 목록을 실제 relay sync 요청에서 확인한 시각.';
comment on column routes.source_policy_generation is
    '출발지 정책을 포함한 desired state를 처음 전송하는 generation. 기존 정책 없는 ACK와 구분한다.';
comment on column port_mappings.source_policy_generation is
    '출발지 정책을 포함한 mapping snapshot의 최초 generation. 기존 정책 없는 ACK와 구분한다.';
