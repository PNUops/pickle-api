-- An approval nobody made.
--
-- Until now every request_reviews row named a person, because every decision
-- was one. A resource kind whose policy issues on request is approved by the
-- platform, and there are three ways to write that down: name the requester,
-- invent a system account, or say nobody. The first makes the record a lie —
-- it reads as self-approval — and the second puts a person in the users table
-- who is not one. So: null, and null means the platform decided.
--
-- The foreign key stays. A reviewer_id that is present must still be a real
-- user; what is dropped is the claim that one is always present.
alter table request_reviews
    alter column reviewer_id drop not null;

comment on column request_reviews.reviewer_id is
    '결재한 사람. null이면 사람이 아니라 플랫폼이 정책에 따라 자동으로 승인한 것이다.';
