-- Two identifiers a user should never have been shown, and one name a request
-- should always have carried.
--
-- V78 gave 23 tables a public_id and left audit_logs out, on the reading that
-- its row id is a rendering key nobody queries by. The consequence was missed:
-- that key is rendered into the `id` field of both audit responses, and one of
-- them is an ordinary user's own history. A sequential number there discloses
-- the platform's total audit volume and its growth rate to anyone who reads
-- their own activity page twice -- the same leak V78 closed everywhere else.
-- 4,121 rows carried it at the time this was written.
--
-- The application role has UPDATE, DELETE and TRUNCATE revoked on this table
-- (V7), and adding a column whose default is volatile rewrites it. The rewrite
-- is DDL performed by the table owner and needs no UPDATE privilege: verified
-- on a pg_dump copy of the development database, running as the `pickle` role,
-- where UPDATE on audit_logs is refused ("permission denied for table
-- audit_logs") and this statement succeeds, filling every existing row with a
-- distinct value. V78 used the same pattern on 23 tables.

alter table audit_logs add column public_id uuid not null default gen_random_uuid();
create unique index audit_logs_public_id_uidx on audit_logs (public_id);

comment on column audit_logs.public_id is
    '감사 로그 행의 공개 식별자. 응답의 id 필드가 담는 값이며, 내부 순번(id)은 서버 밖으로 나가지 않는다. 목록 렌더링용 키일 뿐 조회 파라미터가 아닌 성격은 그대로다.';

-- A request's display name becomes mandatory. It is the name of the resource
-- being asked for -- for every resource type, not just a VM -- and every
-- response that carries a request reference now carries it beside the id,
-- because a UUID cannot be read, remembered or spoken. Optional since it was
-- introduced, it was set on 6 of 84 rows.
--
-- Each backfill source is a fact already on record rather than a stand-in:
--   1. the name already stored, where there is one (6 rows);
--   2. the name of the VM created from the request -- the resource the request
--      produced is the thing the name names (67 rows);
--   3. the requester's own `purpose`, cut to the 100 characters the API accepts,
--      for a request that never produced a resource (11 rows: 10 canceled, 1
--      still submitted). `purpose` is NOT NULL and blank on no row, and it is
--      what the requester themselves wrote about what they were asking for, so
--      it names the request more truthfully than anything generated would.
-- The trailing literal is unreachable while (3) holds and exists only so that
-- the NOT NULL below cannot fail a deployment on some row no source covers.
-- The subquery is ordered because nothing stops two VMs sharing a request_id;
-- none do today, and a backfill should not depend on that staying true.

update requests r
   set display_name = coalesce(
           nullif(btrim(r.display_name), ''),
           nullif(btrim((select v.name from vms v
                          where v.request_id = r.id
                          order by v.id limit 1)), ''),
           nullif(left(btrim(r.purpose), 100), ''),
           '이름 없는 신청')
 where r.display_name is null or btrim(r.display_name) = '';

alter table requests alter column display_name set not null;

comment on column requests.display_name is
    '신청한 리소스의 표시명. 신청 시 필수 입력이며, 이 신청을 가리키는 모든 응답이 공개 식별자 옆에 함께 싣는다. 이 마이그레이션 이전 행은 만들어진 VM의 이름, 그것도 없으면 신청자가 적은 사용 목적의 앞 100자로 채웠다.';
