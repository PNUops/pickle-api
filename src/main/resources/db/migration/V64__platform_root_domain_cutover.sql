-- Platform root-domain cutover: user subdomains move to a new root and the
-- retired root is removed outright. No dual-root period — this environment has
-- never served a real publication, and every row under the retired root is an
-- e2e leftover already in REMOVED.
--
-- certificates.scope ('*.' || domains.root_domain) is the join key the API uses to
-- find a platform subdomain's certificate, so it moves with the root. The agent
-- side moves independently: certRef became 'wildcard:<root>', which the proxy
-- resolves against its own configured material and refuses when unknown.

-- Guard first. The deletes below are the one irreversible step of the cutover;
-- they are scoped to rows that were never live, and this is what keeps them
-- scoped. If anything under the retired root is still live the whole migration
-- must fail rather than quietly take a published site down.
--
-- Routes are counted as well as domains: a route can still be APPLIED under a
-- domain already marked REMOVED, and that route is a vhost the proxy is serving
-- right now. Checking only the domain status would delete it and orphan the vhost
-- until someone happened to run a resync.
do $$
declare
    live_rows int;
begin
    select count(*) into live_rows
      from domains d
      left join routes r on r.domain_id = d.id and r.status <> 'REMOVED'
     where d.root_domain = 'pickle.pnuops.com'
       and (d.status <> 'REMOVED' or r.id is not null);
    if live_rows > 0 then
        raise exception
            'refusing to retire pickle.pnuops.com: % live domain/route row(s) remain', live_rows;
    end if;
end
$$;

-- Swap the retired root for the new one without disturbing anything else in the
-- list: this value is admin-editable at runtime, so replacing the whole array
-- would silently drop a root an operator had added. The description is left
-- alone — V16 relabelled these to Korean because the console renders them as-is.
update settings
   set value = (value - 'pickle.pnuops.com') || '["pusan.dev"]'::jsonb,
       updated_at = now()
 where key = 'allowed_root_domains';

update certificates
   set scope = '*.pusan.dev',
       updated_at = now()
 where kind = 'ORIGIN_CA_WILDCARD'
   and scope = '*.pickle.pnuops.com';

-- Submit-time root preference carried on requests that have not published yet.
-- Left alone these self-heal at publish (an unallowed stored root falls back to
-- the current default), but carrying a dead name in a user-visible field is worse
-- than moving it.
update vm_requests
   set root_domain = 'pusan.dev',
       updated_at = now()
 where root_domain = 'pickle.pnuops.com';

-- Children first: routes and per-domain certificates both reference domains.
delete from routes
 where domain_id in (select id from domains where root_domain = 'pickle.pnuops.com');

delete from certificates
 where domain_id in (select id from domains where root_domain = 'pickle.pnuops.com');

delete from domains
 where root_domain = 'pickle.pnuops.com';
