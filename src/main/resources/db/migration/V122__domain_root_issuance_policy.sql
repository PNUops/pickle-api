-- Whether a name under this root is issued on request or waits for a reviewer.
--
-- The policy lives on the root rather than on the organisation because a root
-- is the name space the decision is about: an institution that owns two roots
-- can run a teaching one open and a research one reviewed. The organisation is
-- reachable from here (domain_roots.org_id), so nothing is lost by keeping it
-- one step away.
--
-- Default true, which is the behaviour every existing name was issued under.
-- A migration that flipped the answer for names already in the ground would be
-- changing a policy nobody set.
alter table domain_roots
    add column auto_approve boolean not null default true;

comment on column domain_roots.auto_approve is
    'true면 이 루트 아래 이름 신청이 접수와 동시에 승인된다. false면 승인 큐를 탄다. '
    '지금 이 값을 읽는 것은 외부 도메인 발급뿐이고, VM 서브도메인 공개는 아직 아니다.';
