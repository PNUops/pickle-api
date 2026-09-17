-- Move the existing rows onto the classification V125 opened.
--
-- The order of these two statements is the whole of their correctness. PROJECT
-- is where courses ended up, for want of a kind that described them, and it is
-- also where the retired TEAM rows are headed. Remapping TEAM first would carry
-- those rows through PROJECT and into COURSE along with the courses.
--
-- The first statement moves every PROJECT row, not a chosen subset: which of
-- them is really a course is knowledge about one database, and a migration that
-- selects on a workspace name would be encoding an environment into schema. A
-- row this lands in the wrong kind is corrected from the console afterwards,
-- which is what the kind is editable for.
update workspaces set kind = 'COURSE' where kind = 'PROJECT';

-- TEAM is retired. Everything that carried it was a group building something,
-- which is what PROJECT now means.
update workspaces set kind = 'PROJECT' where kind = 'TEAM';
