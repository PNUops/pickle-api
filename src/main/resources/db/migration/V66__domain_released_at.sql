-- Record when a domain stopped serving, so a released platform subdomain can
-- hold its name for a grace period before anyone else may take it.
--
-- Until now the two domain kinds behaved the opposite way round. A platform
-- subdomain was marked REMOVED the moment it was taken down, which frees the
-- name immediately (domains_fqdn_live_idx excludes REMOVED) — so a name someone
-- released by mistake, or whose links are still circulating, could be claimed by
-- another user at once. A custom domain's row was kept alive indefinitely to
-- preserve its verification state, which means it held its FQDN forever unless
-- the owner deleted it by hand.
--
-- The platform's shared name space is the one that needs the delay: it is ours,
-- the names are short and contested, and a handover carries the risk that
-- traffic meant for the previous holder lands on someone else's service. A
-- custom domain is the user's own DNS — if another account can prove control of
-- it, control genuinely moved, and holding the name here would only block the
-- new owner. So the grace now applies to platform subdomains and custom domains
-- are released on deletion.
--
-- This column, not updated_at: the row is dirty-updated for unrelated reasons
-- (an admin-triggered verification, a certificate status change), which would
-- silently push the release date out.
alter table domains add column released_at timestamptz;

comment on column domains.released_at is
    '서빙을 멈춘 시각. 플랫폼 서브도메인은 이 시각부터 유예 기간 동안 이름이 예약되고, 그 뒤 스위퍼가 REMOVED로 회수한다. 서빙 중이면 null.';

-- Existing rows that already stopped serving (no live route) but were never
-- marked REMOVED: date them from their last write so the sweeper can reason
-- about them instead of treating them as freshly released. These are the custom
-- tombstones the old behaviour left behind; under the new rule they are due for
-- release and the sweeper will take them on its next pass.
update domains d
   set released_at = d.updated_at
 where d.status <> 'REMOVED'
   and not exists (select 1
                     from routes r
                    where r.domain_id = d.id
                      and r.status <> 'REMOVED');

-- The sweeper scans by release time across every VM, so the index carries the
-- predicate that makes the scan small rather than the whole table.
create index domains_released_at_idx on domains (released_at)
    where released_at is not null and status <> 'REMOVED';
