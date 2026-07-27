-- Org visibility flag: a hidden org keeps working end to end (VM requests
-- may target it, its admins log in, smokes exercise it) but is filtered out
-- of GET /orgs for USER-role callers, so it never shows up in the request
-- form's org picker. Manager-tier callers keep seeing hidden orgs — the
-- admin console manages them. Distinct from DISABLED, which gates function.
alter table orgs add column hidden boolean not null default false;

-- Neutralize the seed org: the platform is no longer tied to one campus
-- center, and the seeded org's real purpose is being the test/smoke fixture
-- (the org admin seed cannot exist without an org, V11/V44 check). The
-- dev/test seeder matches by slug and never renames an existing row, so the
-- rename has to happen here.
update orgs
   set name = '테스트 기관',
       slug = 'test-org',
       description = '테스트 기관 (개발용 시드 기관)',
       hidden = true
 where slug = 'sw-edu';
