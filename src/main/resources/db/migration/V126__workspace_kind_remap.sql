-- Move the existing rows onto the classification V125 opened.
--
-- The order of these two statements is the whole of their correctness. The
-- courses an operator classified by hand are sitting in PROJECT, and PROJECT is
-- also where the retired TEAM rows are headed. Remapping TEAM first would sweep
-- roughly a hundred smoke and demo workspaces into COURSE along with them.
--
-- PROJECT today holds the four course workspaces plus one row named 테스트, so
-- this lands that one row in COURSE as well; it is corrected from the console
-- afterwards rather than by naming it here, because a migration that reads a
-- workspace name is encoding this environment into schema.
update workspaces set kind = 'COURSE' where kind = 'PROJECT';

-- TEAM is retired. Everything that carried it was a group building something,
-- which is what PROJECT now means.
update workspaces set kind = 'PROJECT' where kind = 'TEAM';
