-- Terminology standardization (2026-07-16, docs/glossary.md): align the
-- admin-delete notice key with the vm_* prefix used by every other VM setting
-- (and with the contract wording), and standardize the Korean descriptions
-- seeded in V6/V16 (물리 파기/셀프 삭제 → glossary terms). Key rename runs
-- BEFORE the description update that targets the new key.

update settings set key = 'vm_admin_delete_min_notice_days'
 where key = 'admin_delete_min_notice_days';
update settings set description = '본인 삭제 접수 후 파기까지의 유예 시간(시간). 유예는 관리자 복구용 안전망.'
 where key = 'vm_delete_grace_hours';
update settings set description = '관리자 일반 삭제가 보장해야 하는 최소 사전 통보 기간(일).'
 where key = 'vm_admin_delete_min_notice_days';
