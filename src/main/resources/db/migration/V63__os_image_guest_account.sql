-- The catalog is about to hold more than Ubuntu (Debian, Rocky), so the guest
-- admin account stops being a platform constant and becomes a property of the
-- image row. Until now the vms row took its ssh_username from a hardcoded
-- 'ubuntu' in the entity and the provisioning step passed that to Proxmox as
-- the cloud-init ciuser: a Debian image (whose own account is 'debian') would
-- have been provisioned with a 'ubuntu' user that matches neither the image's
-- sudoers/group setup nor the login name we show the student.
-- The distribution identity (family + release) rides along because the console
-- has to group and label the wizard's OS axis, and deriving either by parsing
-- `name` would make the naming convention load-bearing.
--
-- os_family is plain text with a slug check rather than an enum type on
-- purpose: adding an OS must stay a data change (one row insert), not a
-- schema-plus-code change.
--
-- os_version is text because '24.04' is not a number, and it holds the release
-- string exactly as the distribution writes it. It is a label, never a sort
-- key -- the catalog keeps its explicit id order, which the operator sets at
-- insert time -- because text order would put Rocky's '10' before '9'. Any
-- query that ever does need semantic version order must sort by
-- string_to_array(os_version, '.')::int[]; the numeric-dotted check below is
-- what keeps that expression total.

alter table os_images
    add column os_family    text,
    add column os_version   text,
    add column ssh_username text;

-- Backfill before the NOT NULLs: the catalog holds only the seeded Ubuntu
-- 24.04 row (V3 seeded three preset rows, V58 folded them into one OS row),
-- and its guest account has been 'ubuntu' since V48. The version is read off
-- the name where it looks like a release so a manually added ubuntu-22.04 row
-- gets its own value rather than the seed's.
-- The backfill that stood here filled the three new columns on rows a
-- migration had seeded. Nothing seeds a catalog row now, so it matched
-- nothing, and the columns below can go straight to NOT NULL on the empty
-- table. The bootstrap script supplies all three per image when it registers
-- one — which is the point of the change: the guest account is a property of
-- the image, not a platform constant to be defaulted here.

alter table os_images
    alter column os_family set not null,
    alter column os_version set not null,
    alter column ssh_username set not null,
    add constraint chk_os_images_os_family
        check (os_family ~ '^[a-z][a-z0-9]*$'),
    add constraint chk_os_images_os_version
        check (os_version ~ '^[0-9]+(\.[0-9]+)*$'),
    -- ssh_username reaches Proxmox as the cloud-init ciuser, so it stays a
    -- plain POSIX-portable account name.
    add constraint chk_os_images_ssh_username
        check (ssh_username ~ '^[a-z_][a-z0-9_-]*$');

comment on column os_images.os_family is
    'Distribution short id (ubuntu, debian, rocky). Free text by design: a new OS is a row, not a migration.';
comment on column os_images.os_version is
    'Release string as the distribution writes it (24.04, 13, 10). Display label, not a sort key -- order by string_to_array(os_version, ''.'')::int[] if semantic order is ever needed.';
comment on column os_images.ssh_username is
    'Guest admin account the image ships; becomes the cloud-init ciuser and the login name shown to the user.';
