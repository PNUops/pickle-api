-- Terminology standardization (2026-07-16): the group role
-- between OWNER and MEMBER is 편집자/EDITOR — the old MANAGER naming is
-- retired to keep it distinct from the future org/system 운영자 roles
-- (ORG_MANAGER/SYS_MANAGER). RENAME VALUE is fully transactional (PG10+);
-- rows store enum OIDs, so existing data follows automatically.

alter type group_member_role rename value 'MANAGER' to 'EDITOR';
