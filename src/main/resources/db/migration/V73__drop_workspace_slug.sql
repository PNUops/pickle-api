-- The workspace slug goes.
--
-- A VM's slug is load-bearing: it is the name a user types to reach the
-- machine over SSH, so it has to be unique, stable and typeable. A
-- workspace's slug was never any of that. Nothing addressed a workspace by
-- it: it seeded a generated VM hostname and it was printed next to the
-- workspace name in the console, and that was all. What it did do was ask
-- every person creating a workspace to invent a second name, and it added a
-- uniqueness rule the platform then had to defend.
--
-- Generated hostnames now take their seed from the requester's display name,
-- so the column has no readers left.

alter table workspaces drop column slug;
