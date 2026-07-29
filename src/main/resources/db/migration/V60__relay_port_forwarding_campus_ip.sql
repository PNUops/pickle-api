-- Relay port forwarding + campus-IP requests (operator decision 2026-07-28,
-- schema 2026-07-29).
--   * relays: one row per forwarding relay. The relay-side agent pulls its
--     desired mapping set (POST /internal/relays/{id}/sync), authenticated by
--     a per-relay token (sha256 hex; null = not issued yet, every sync fails
--     closed) and pinned to source_ip (the relay's tunnel address).
--     mapping_generation increments in the same transaction as any mapping
--     write for that relay (per-relay counter, never a global sequence);
--     applied_generation is the last generation the agent confirmed applied.
--     public_host is what users connect to; the operator sets the live value
--     at deploy time, so it is seeded null here.
--   * port_mappings: desired DNAT state, one public port per row. The target
--     ADDRESS is deliberately not stored: it is resolved live from the VM's
--     own ip_allocations row at snapshot-read time, so a released or
--     re-assigned IP can never linger inside a mapping. The guard columns are
--     per-mapping overrides for the agent's connection guards; null = agent
--     default, 0 = guard disabled, >0 = explicit limit.
--   * port_mapping_counters: cumulative per-mapping traffic totals accumulated
--     from agent-reported raw readings. Raw values are cumulative since agent
--     start; the last_* columns keep the previous raw reading so a decrease
--     (agent restart) is detected as a reset, never a negative delta.
--   * campus_ip_requests: 교내 IP allocation requests (admin-processed;
--     the only feature that involves the campus network operator). At most
--     one live request per VM (partial unique index).

create table relays (
    id                 bigint generated always as identity primary key,
    name               text not null unique,
    public_host        text,
    source_ip          text not null unique,
    token_hash         char(64),
    port_band_start    int not null check (port_band_start >= 1024),
    port_band_end      int not null check (port_band_end >= 1024 and port_band_end <= 65535),
    mapping_generation bigint not null default 0,
    applied_generation bigint not null default 0,
    last_contact_at    timestamptz,
    contact_lost_since timestamptz,
    agent_version      text,
    last_error         text,
    enabled            boolean not null default true,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint relays_port_band_check check (port_band_end >= port_band_start)
);

comment on table relays is
    'Forwarding relay hosts. token_hash = sha256 hex of the per-relay sync token (null -> sync fails closed); source_ip = the only peer address accepted for this relay''s sync calls; mapping_generation bumps in the same tx as any mapping write (per-relay, no global sequence).';

create table port_mappings (
    id                     bigint generated always as identity primary key,
    relay_id               bigint not null references relays (id),
    vm_id                  bigint not null references vms (id),
    proto                  text not null check (proto in ('TCP', 'UDP')),
    public_port            int not null check (public_port between 1024 and 65535),
    target_port            int not null check (target_port between 1 and 65535),
    status                 text not null default 'ACTIVE'
                           check (status in ('ACTIVE', 'SUSPENDED')),
    suspended_reason       text,
    suspended_by           bigint references users (id),
    last_change_generation bigint not null,
    ct_max                 int check (ct_max >= 0),
    new_conn_rate          int check (new_conn_rate >= 0),
    new_conn_burst         int check (new_conn_burst >= 0),
    per_source_rate        int check (per_source_rate >= 0),
    per_source_burst       int check (per_source_burst >= 0),
    created_by             bigint not null references users (id),
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    constraint port_mappings_relay_proto_port_key unique (relay_id, proto, public_port)
);

create index port_mappings_vm_id_idx on port_mappings (vm_id);
create index port_mappings_relay_status_idx on port_mappings (relay_id, status);

comment on table port_mappings is
    'Desired relay DNAT state (relay public_port -> VM target_port). No target address column: resolved live from the VM''s ALLOCATED ip_allocations row at snapshot read. The unique key includes proto for a future dual-proto option, but allocation treats a port number used by either proto as taken. Guard columns: null = agent default, 0 = disabled, >0 = explicit limit. last_change_generation = the relay generation of this row''s last change (applyState: applied_generation >= it means active).';

create table port_mapping_counters (
    mapping_id              bigint primary key references port_mappings (id) on delete cascade,
    conn_total              bigint not null default 0,
    bytes_total             bigint not null default 0,
    drop_total              bigint not null default 0,
    last_new_conns          bigint not null default 0,
    last_in_packets         bigint not null default 0,
    last_in_bytes           bigint not null default 0,
    last_out_packets        bigint not null default 0,
    last_out_bytes          bigint not null default 0,
    last_rate_dropped       bigint not null default 0,
    last_conn_dropped       bigint not null default 0,
    last_per_source_dropped bigint not null default 0,
    last_delta_at           timestamptz,
    updated_at              timestamptz not null default now()
);

comment on table port_mapping_counters is
    'Cumulative per-mapping traffic accounting from agent heartbeats. last_* keep the previous raw (since-agent-start) readings; any raw value below its last_* means the agent restarted, so the whole row re-baselines (delta = raw) instead of producing a negative delta. last_delta_at anchors the per-minute rate used by the auto-suspend thresholds.';

create table campus_ip_requests (
    id              bigint generated always as identity primary key,
    vm_id           bigint not null references vms (id),
    requested_by    bigint not null references users (id),
    purpose         text not null,
    ports           jsonb not null default '[]'::jsonb,
    status          text not null default 'REQUESTED'
                    check (status in ('REQUESTED', 'APPROVED', 'GRANTED', 'REJECTED', 'REVOKED')),
    granted_address text,
    admin_note      text,
    processed_by    bigint references users (id),
    processed_at    timestamptz,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create unique index campus_ip_requests_vm_live_idx on campus_ip_requests (vm_id)
    where status in ('REQUESTED', 'APPROVED', 'GRANTED');
create index campus_ip_requests_status_idx on campus_ip_requests (status);

comment on table campus_ip_requests is
    '교내 IP 할당 신청. ports = 공개할 포트 번호의 정수 배열(jsonb). Transitions: REQUESTED -> APPROVED|REJECTED, APPROVED -> GRANTED|REJECTED, GRANTED -> REVOKED. The partial unique index allows one live (REQUESTED/APPROVED/GRANTED) request per VM.';

-- Port-forwarding lifecycle entries in the permanent per-VM history.
alter type vm_event_type add value if not exists 'PORT_FORWARD_CREATE';
alter type vm_event_type add value if not exists 'PORT_FORWARD_DELETE';

-- The single relay of the initial deployment. public_host and the sync token
-- are operator-set at deploy time (the real public address never ships in a
-- repo); source_ip is the relay's tunnel-side address, a config default that
-- the operator can re-point per environment.
insert into relays (name, source_ip, port_band_start, port_band_end)
values ('lightsail-1', '10.100.100.1', 10000, 19999);

insert into settings (key, value, description) values
    ('port_forwarding_enabled', 'false',
     '포트 포워딩(릴레이 공개 포트) 기능 스위치. false면 신규 생성이 차단됩니다 (기존 매핑은 유지).'),
    ('port_forward_alloc_limit_per_hour', '20',
     '사용자별 포트 포워딩 생성 허용 횟수(시간당).'),
    ('port_forward_band_alert_percent', '80',
     '릴레이 공개 포트 대역 사용률 경고 임계값(%). 도달 시 시스템 관리자에게 알림을 보냅니다.'),
    ('port_forward_suspend_conns_per_min', '6000',
     '매핑별 분당 신규 연결 수 자동 정지 임계값. 초과 시 해당 매핑을 자동 SUSPENDED 처리합니다.'),
    ('port_forward_suspend_mbytes_per_min', '1000',
     '매핑별 분당 전송량(MB) 자동 정지 임계값. 초과 시 해당 매핑을 자동 SUSPENDED 처리합니다.');
