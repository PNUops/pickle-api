-- Drops the sudo-mode reauthentication feature (introduced V59, 2026-07-28).
--
-- The gate demanded a fresh password proof on 37 operations, carried as an
-- X-Reauth-Token header. It is gone: the operator's judgement is that the
-- friction outran the protection, and for an account signed in through Google
-- the round trip was worse than friction — the console had to leave the page
-- to re-verify, which destroyed whatever the person had typed.
--
-- What did NOT rest on it is unaffected: the role gates, the resource-access
-- rungs, and the five operations that carry the password (or a TOTP code) in
-- the request body -- password change, withdrawal, 2FA enrol, 2FA disable and
-- recovery code regeneration -- all still ask.

drop table auth_reverifications;

-- oauth_flows.purpose loses REVERIFY. The rows are single-use round trips that
-- live ten minutes, so deleting the outstanding ones strands nobody: the
-- console no longer starts such a flow, and any in flight has no callback left
-- to answer it. The constraint has to be dropped and re-added rather than
-- edited, since V89 that created it is already applied and immutable.
alter table oauth_flows drop constraint chk_oauth_flows_purpose;
delete from oauth_flows where purpose = 'REVERIFY';
alter table oauth_flows add constraint chk_oauth_flows_purpose
    check (purpose in ('LOGIN', 'LINK'));

comment on column oauth_flows.initiating_user_id is
    'Set for a flow that acts on an existing session (LINK). Null for LOGIN.';
