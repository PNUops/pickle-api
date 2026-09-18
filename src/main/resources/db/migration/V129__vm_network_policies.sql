create type vm_network_policy_apply_state as enum (
    'INACTIVE', 'PENDING', 'APPLIED', 'FAILED', 'FAILED_CLOSED'
);

create table vm_network_policies (
    vm_id bigint primary key references vms(id) on delete cascade,
    revision bigint not null default 0,
    desired_generation bigint not null default 1,
    applied_generation bigint,
    desired_hash varchar(64) not null,
    applied_hash varchar(64),
    apply_state vm_network_policy_apply_state not null default 'PENDING',
    last_error text,
    updated_by bigint references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint vm_network_policies_revision_check check (revision >= 0),
    constraint vm_network_policies_desired_generation_check check (desired_generation > 0),
    constraint vm_network_policies_applied_generation_check check (
        applied_generation is null
        or (applied_generation > 0 and applied_generation <= desired_generation)
    ),
    constraint vm_network_policies_desired_hash_check check (desired_hash ~ '^[0-9a-f]{64}$'),
    constraint vm_network_policies_applied_hash_check check (
        applied_hash is null or applied_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint vm_network_policies_applied_pair_check check (
        (applied_generation is null) = (applied_hash is null)
    ),
    constraint vm_network_policies_applied_state_check check (
        apply_state <> 'APPLIED'
        or (applied_generation is not null and applied_hash is not null
            and applied_generation = desired_generation and applied_hash = desired_hash)
    )
);

create table vm_network_policy_rules (
    vm_id bigint not null references vm_network_policies(vm_id) on delete cascade,
    position smallint not null,
    direction varchar(3) not null,
    action varchar(6) not null,
    protocol varchar(4) not null,
    peer cidr not null,
    port_start integer,
    port_end integer,
    primary key (vm_id, position),
    constraint vm_network_policy_rules_position_check check (position between 0 and 127),
    constraint vm_network_policy_rules_direction_check check (direction in ('IN', 'OUT')),
    constraint vm_network_policy_rules_action_check check (action in ('ACCEPT', 'DROP')),
    constraint vm_network_policy_rules_protocol_check check (protocol in ('ANY', 'TCP', 'UDP', 'ICMP')),
    constraint vm_network_policy_rules_ipv4_check check (family(peer) = 4),
    constraint vm_network_policy_rules_ports_check check (
        (protocol in ('TCP', 'UDP')
            and ((port_start is null and port_end is null)
                or (port_start is not null and port_end is not null
                    and port_start between 1 and 65535
                    and port_end between 1 and 65535
                    and port_start <= port_end)))
        or (protocol in ('ANY', 'ICMP') and port_start is null and port_end is null)
    )
);

comment on table vm_network_policies is
    'VM별 통신 정책 desired/applied 상태. 행이 있는 VM만 opt-in이며 feature flag나 node label 소실로 legacy bypass하지 않는다.';
comment on column vm_network_policies.desired_hash is
    '사용자 규칙과 환경별 immutable/derived system rule을 정규화한 SHA-256.';
comment on column vm_network_policies.apply_state is
    'FAILED_CLOSED는 immutable barrier가 실제로 enable되고 지원 NIC 전체를 덮는 것을 확인한 경우에만 사용한다.';
comment on table vm_network_policy_rules is
    '순서가 의미인 IPv4 전용 사용자 규칙. system rule과 PVE 내부 주소/그룹 이름은 저장하거나 공개하지 않는다.';

create type vm_network_path_owner_kind as enum ('HTTP_ROUTE', 'PORT_MAPPING');
create type vm_network_path_source_kind as enum ('PROXY', 'RELAY');
create type vm_network_path_action as enum (
    'OPEN', 'REPLACE', 'CLOSE', 'SUSPEND', 'RESUME', 'DELETE'
);
create type vm_network_path_phase as enum (
    'POLICY_ADD', 'CONSUMER_APPLY', 'CONSUMER_RETIRE', 'POLICY_REMOVE', 'DONE'
);

create table vm_network_derived_paths (
    id bigint generated always as identity primary key,
    vm_id bigint not null references vms(id) on delete cascade,
    owner_kind vm_network_path_owner_kind not null,
    owner_id bigint not null,
    source_kind vm_network_path_source_kind not null,
    protocol varchar(3) not null,
    target_port integer not null,
    created_at timestamptz not null default now(),
    constraint vm_network_derived_paths_protocol_check check (protocol in ('TCP', 'UDP')),
    constraint vm_network_derived_paths_target_port_check check (target_port between 1 and 65535),
    constraint vm_network_derived_paths_http_check check (
        owner_kind <> 'HTTP_ROUTE' or (source_kind = 'PROXY' and protocol = 'TCP')
    ),
    constraint vm_network_derived_paths_owner_tuple_key
        unique (owner_kind, owner_id, source_kind, protocol, target_port)
);

create index vm_network_derived_paths_vm_id_idx on vm_network_derived_paths(vm_id);

create table vm_network_path_operations (
    id uuid primary key default gen_random_uuid(),
    vm_id bigint not null references vms(id) on delete cascade,
    owner_kind vm_network_path_owner_kind not null,
    owner_id bigint not null,
    action vm_network_path_action not null,
    phase vm_network_path_phase not null,
    new_path_id bigint references vm_network_derived_paths(id) on delete set null,
    old_path_id bigint references vm_network_derived_paths(id) on delete set null,
    policy_generation bigint,
    consumer_generation bigint,
    retirement_id uuid,
    revision bigint not null default 0,
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint vm_network_path_operations_attempts_check check (attempts >= 0 and revision >= 0),
    constraint vm_network_path_operations_paths_check check (
        phase = 'DONE'
        or (action in ('OPEN', 'RESUME') and new_path_id is not null and old_path_id is null)
        or (action = 'REPLACE' and new_path_id is not null
            and (old_path_id is not null or phase in ('POLICY_REMOVE', 'DONE')))
        or (action in ('CLOSE', 'SUSPEND', 'DELETE') and new_path_id is null
            and (old_path_id is not null or phase in ('POLICY_REMOVE', 'DONE')))
    )
);

create unique index vm_network_path_operations_live_owner_uq
    on vm_network_path_operations(owner_kind, owner_id) where phase <> 'DONE';
create index vm_network_path_operations_due_idx
    on vm_network_path_operations(next_attempt_at, id) where phase <> 'DONE';

alter table relays
    add column retirement_armed boolean not null default false,
    add column retirement_ledger_id uuid,
    add column mapping_id_high_water bigint not null default 0,
    add column flow_mark_high_water bigint not null default 0,
    add column reported_mapping_id_high_water bigint not null default 0,
    add column reported_flow_mark_high_water bigint not null default 0,
    add column reported_managed_generation_high_water bigint not null default 0,
    add column reported_retirement_high_water bigint not null default 0,
    add column acknowledged_retirement_high_water bigint not null default 0,
    add column retirement_observed_at timestamptz,
    add column mark_namespace_ready boolean not null default false,
    add constraint relays_flow_mark_high_water_check
        check (flow_mark_high_water between 0 and 4294967295),
    add constraint relays_mapping_id_high_water_check
        check (mapping_id_high_water >= 0),
    add constraint relays_reported_mapping_id_high_water_check
        check (reported_mapping_id_high_water >= 0),
    add constraint relays_reported_flow_mark_high_water_check
        check (reported_flow_mark_high_water between 0 and 4294967295),
    add constraint relays_reported_managed_generation_high_water_check
        check (reported_managed_generation_high_water >= 0),
    add constraint relays_retirement_high_water_check
        check (reported_retirement_high_water >= 0
            and acknowledged_retirement_high_water >= 0
            and acknowledged_retirement_high_water <= reported_retirement_high_water),
    add constraint relays_retirement_identity_check
        check (not retirement_armed or retirement_ledger_id is not null);

alter table port_mappings drop constraint port_mappings_status_check;
alter table port_mappings drop constraint port_mappings_relay_proto_port_key;
alter table port_mappings
    add constraint port_mappings_status_check
        check (status in ('PENDING', 'ACTIVE', 'SUSPENDED', 'REMOVING')),
    add column delivery_state varchar(20) not null default 'LEGACY',
    add column flow_mark bigint,
    add column consumer_mapping_id bigint,
    add constraint port_mappings_delivery_state_check check (
        delivery_state in ('LEGACY', 'PENDING', 'ACTIVE', 'RETIRING', 'SUSPENDED')
    ),
    add constraint port_mappings_flow_mark_check check (
        (delivery_state = 'LEGACY' and flow_mark is null and consumer_mapping_id is null)
        or (delivery_state <> 'LEGACY' and flow_mark between 1 and 4294967295
            and consumer_mapping_id > 0)
    ),
    add constraint port_mappings_consumer_mapping_id_key
        unique (relay_id, consumer_mapping_id);

alter table port_mappings
    add constraint port_mappings_relay_proto_port_key
        unique (relay_id, proto, public_port);

create table relay_mapping_retirements (
    id uuid primary key default gen_random_uuid(),
    relay_id bigint not null references relays(id),
    mapping_row_id bigint not null references port_mappings(id),
    mapping_id bigint not null,
    vm_id bigint not null references vms(id),
    retirement_sequence bigint not null,
    generation bigint not null,
    protocol varchar(3) not null,
    public_port integer not null,
    target_addr inet not null,
    target_port integer not null,
    flow_mark bigint not null,
    tuple_hash varchar(64) not null,
    cleared_at timestamptz,
    receipt_generation bigint,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint relay_mapping_retirements_protocol_check check (protocol in ('TCP', 'UDP')),
    constraint relay_mapping_retirements_public_port_check check (public_port between 1024 and 65535),
    constraint relay_mapping_retirements_target_port_check check (target_port between 1 and 65535),
    constraint relay_mapping_retirements_flow_mark_check check (flow_mark between 1 and 4294967295),
    constraint relay_mapping_retirements_generation_check check (
        retirement_sequence > 0 and generation > 0 and mapping_id > 0),
    constraint relay_mapping_retirements_tuple_hash_check check (tuple_hash ~ '^[0-9a-f]{64}$'),
    constraint relay_mapping_retirements_sequence_key unique (relay_id, retirement_sequence),
    constraint relay_mapping_retirements_flow_mark_key unique (relay_id, flow_mark),
    constraint relay_mapping_retirements_mapping_key unique (mapping_row_id, mapping_id)
);

create index relay_mapping_retirements_pending_idx
    on relay_mapping_retirements(relay_id, generation) where cleared_at is null;

comment on table vm_network_derived_paths is
    'VM 방화벽 derived allow의 정본. 소비자 전환 중 old/new path를 함께 보존하며 사용자 CAS revision과 독립적으로 desired generation/hash만 바꾼다.';
comment on table vm_network_path_operations is
    'after-commit enqueue 유실과 재시작을 견디는 HTTP/relay path 상태 머신. phase가 DONE이 될 때까지 recurring worker가 재시도한다.';
comment on table relay_mapping_retirements is
    'DNAT 제거와 exact DROP fence 뒤 native conntrack tuple 삭제 및 post-delete zero readback을 마친 CLEARED receipt 정본. API ACK와 경로 작업 완료 뒤 tuple은 정리하되 relay high-watermark는 영구 보존한다.';
