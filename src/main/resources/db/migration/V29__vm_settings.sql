-- Per-VM settings (M5.5, product-spec §9). A sparse key/value store: a row
-- exists only once a setting is changed from its code-side registry default, so
-- the catalog can grow without a migration per key. Today's keys:
--   * ssh_password_enabled       (BOOLEAN, default false) — SSH gateway
--     password passthrough opt-in (launch gate G6 default-deny).
--   * password_reveal_min_role   (ENUM MEMBER|EDITOR|OWNER, default MEMBER) —
--     minimum group role to reveal the VM password (= the in-VM sudo gate).
-- The registry (VmSettingsService) owns type/default/required-role; this table
-- only records overrides and who set them.

create table vm_settings (
    id         bigint generated always as identity primary key,
    vm_id      bigint not null references vms (id) on delete cascade,
    key        text not null,
    value      jsonb not null,
    updated_by bigint references users (id),
    updated_at timestamptz not null default now(),
    unique (vm_id, key)
);

comment on column vm_settings.key is
    'Setting key from the VmSettingsService registry (e.g. ssh_password_enabled). Unknown keys are rejected at the API, never stored.';
comment on column vm_settings.value is
    'Override value as JSON, typed per the registry entry (boolean, enum string, …). Absence of a row means the registry default is in effect.';
comment on column vm_settings.updated_by is
    'User who last set this override (null if the setter was later removed). Surfaced as VmSettingView.updatedByName.';
