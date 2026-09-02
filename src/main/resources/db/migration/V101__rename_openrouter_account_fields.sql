-- Renames the two free-text columns on an OpenRouter business account.
--
-- V97 named them `funding_reference` and `evidence_reference` while it was
-- still open what an operator would put there. It has since settled: one
-- holds the funded programme the account bills to, the other the person to
-- ask about it. `reference` was the honest name for "points at something we
-- have not defined yet" and is now false -- a reader takes it for a pointer
-- to a document that does not exist.
--
-- `programName`/`contactName` were considered and rejected: a programme is as
-- often a code or a grant number as a name, so `Name` would be the next false
-- suffix. The console labels stay Korean (사업, 담당자); this is the wire and
-- storage name only.
--
-- A rename preserves the values, and the two rows that hold one keep it.
alter table openrouter_accounts rename column funding_reference to program;
alter table openrouter_accounts rename column evidence_reference to contact;

comment on column openrouter_accounts.program is
    '이 account가 청구되는 사업. 자유 텍스트이며 사업명일 수도 과제번호일 수도 있다.';
comment on column openrouter_accounts.contact is
    '이 account를 물어볼 담당자. 자유 텍스트이고 개인정보를 담지 않는다.';
