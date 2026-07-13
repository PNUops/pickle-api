-- VM usage-period expiry (M5): markers for the hourly expiry job. endDate is
-- inclusive (usable through the end date, KST); auto-stop runs after midnight
-- KST of the following day. Extending the period clears both markers so the
-- VM can start again and notices re-arm.

alter table vms
    add column expiry_stopped_at timestamptz,
    add column last_expiry_notice_stage int;

comment on column vms.expiry_stopped_at is
    'Set when the expiry sweeper auto-stopped this VM (docs/plan/03 M5 expiry). Cleared by PATCH /admin/vms/{vmId}/period so an extended VM may start again.';
comment on column vms.last_expiry_notice_stage is
    'Smallest D-day stage (days before end_date) already notified for the current end_date; the CAS "stage < current" guard makes hourly re-runs send nothing.';

-- vm_expiry_notice_days ([14, 7, 1]) is seeded by V16 with the notification
-- core (api-A) — only the autostop switch belongs to this migration.
insert into settings (key, value, description) values
    ('vm_expiry_autostop_enabled', 'true'::jsonb,
     '사용 기간(end_date, 포함)이 지난 VM을 매시간 자동 정지할지 여부. 끄면 예고 알림만 발송됩니다.');
