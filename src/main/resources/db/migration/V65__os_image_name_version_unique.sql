-- The OS catalog is about to hold more than one revision of the same OS: a
-- template gets rebuilt, the new one enters the catalog beside the old, and the
-- pair (name, version) is what tells them apart. Code already looks rows up that
-- way -- V58 selects the surviving Ubuntu row by name and version -- but nothing
-- enforced that the pair identifies a single row, which was harmless only while
-- the catalog held one row per name.
alter table os_images
    add constraint os_images_name_version_key unique (name, version);

-- Left over from before the OS and spec axes were split: '(기본형)' described a
-- size preset, and sizes have been their own catalog since then. The label is
-- what the request form shows, so it should name the OS and nothing else. This
-- corrects a value seeded by an earlier migration, which is why it belongs here
-- rather than with the operational catalog data.
update os_images
   set display_name = 'Ubuntu 24.04 LTS'
 where name = 'ubuntu-24.04' and version = 1 and display_name <> 'Ubuntu 24.04 LTS';
