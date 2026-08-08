-- The ACTIVE/DISABLED enum is shared by two catalogs, os_images and vm_flavors,
-- and neither of them is a template: V58 moved the spec presets out into
-- vm_flavors and V62 renamed vm_templates to os_images, but the type kept the
-- name it was born with in V3 and so described only one of its two users, under
-- a word that no longer names even that one. catalog_status says what it is,
-- the ACTIVE/DISABLED state of a catalog row, for both tables.
--
-- ALTER TYPE ... RENAME is a catalog-only change: rows store the type OID, so
-- no data is rewritten and every existing value stays valid.

alter type template_status rename to catalog_status;
