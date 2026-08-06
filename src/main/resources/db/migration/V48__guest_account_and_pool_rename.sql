-- The platform is no longer student-specific: the guest admin account moves
-- to the cloud image's own default user (ubuntu for the Ubuntu template) and
-- the seeded pool name drops the student-era wording. Row values are the
-- source of truth for gateway/terminal access, so every row (deleted rows
-- included) is updated to keep the fleet single-valued; there are no
-- pre-rename guests left to reach.
-- The pool is referenced by id only (nodes.ip_pool_id), so the rename is
-- display-level; the V5 seed carried the old name.

alter table vms alter column ssh_username set default 'ubuntu';

update vms set ssh_username = 'ubuntu';

-- The pool rename is gone with the seed it renamed: no migration creates an IP
-- pool any longer, so this matched nothing. The bootstrap script writes the
-- pool under its current name.
