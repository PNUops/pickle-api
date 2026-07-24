-- TOTP replay hardening (security review).
--
-- A TOTP code is valid for a ~90s window (±1 step around the current 30s step),
-- so a code observed on the wire can be replayed until it rolls over. Recording
-- the highest step already consumed on the verify path lets us reject any code
-- at a step <= the last one used, closing that replay window without widening
-- the accepted skew.
alter table user_mfa add column last_totp_step bigint;

comment on column user_mfa.last_totp_step is
    'Highest TOTP step counter already consumed on login/verify; a code at step <= this is rejected as a replay.';
