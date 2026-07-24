-- Baseline schema: runtime-tunable settings store.
-- Domain tables are added by subsequent migrations as features land.

create table settings (
    key         text primary key,
    value       jsonb not null,
    description text,
    updated_at  timestamptz not null default now()
);
