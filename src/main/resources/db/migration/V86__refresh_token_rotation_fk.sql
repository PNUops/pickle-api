-- The weekly retention sweeper deletes refresh tokens whose expiry has passed.
-- `rotated_from` pointed at the parent with no delete action, so deleting an
-- expired parent whose child is still live raised a foreign-key violation and
-- the whole sweep aborted -- including the three tables cleaned after this one,
-- since sweep() runs them in one method with no exception handling.
--
-- The condition is not hypothetical: rotation produces such pairs whenever a
-- session outlives its parent token, and the first sweep to meet one was due
-- 2026-08-24. Nothing is lost when the link breaks. A deleted row cannot be the
-- starting point of revokeChainFrom(), which is the only reader, so the pointer
-- has no meaning once its target is gone.
alter table refresh_tokens
    drop constraint refresh_tokens_rotated_from_fkey;

alter table refresh_tokens
    add constraint refresh_tokens_rotated_from_fkey
        foreign key (rotated_from) references refresh_tokens (id)
        on delete set null;
