-- Chat becomes a capability, and the capability vocabulary grows to twelve
-- names: chat and every route the gateway is expected to open after it.
--
-- Until now every key reached chat completions without being granted anything:
-- V111 kept chat outside the passthrough list on purpose and said so in its
-- header ("the two paths it serves today ... are outside it", "the TOKEN axis is
-- outside this list"). That stops being true here. Some keys need no chat at all
-- (a course that only generates video), and a key that holds chat can be wired to
-- a coding agent that spends money, so chat is granted like any other
-- capability. It is the one capability that spans both axes: self-serving chat
-- is refused to a key without it too.
--
-- V111 cannot be corrected in place: an applied migration is frozen down to its
-- comments, because Flyway refuses to start on a checksum mismatch. Its header
-- stays as written, and the comments below replace what the database says about
-- itself.
--
-- The vocabulary is wider than what may be granted today. All twelve names go
-- into the CHECK at once so the contract enum and the constraint move once; the
-- api decides which of them a write may grant and widens that set as each route
-- opens, without another migration. The hierarchy (web_search, pdf_ocr and
-- containers under chat; files under batches or containers) is the api's check
-- too, because a CHECK that knew it would have to move with every new route.
create or replace function llm_passthrough_endpoints_valid(endpoints jsonb)
returns boolean language sql immutable as $$
    select jsonb_typeof(endpoints) = 'array'
       and jsonb_array_length(endpoints) <= 20
       and not exists (
           select 1 from jsonb_array_elements(endpoints) e
            where jsonb_typeof(e) <> 'string'
               or (e #>> '{}') not in ('chat', 'web_search', 'pdf_ocr', 'containers',
                                       'images', 'embeddings', 'rerank', 'speech',
                                       'transcriptions', 'videos', 'batches', 'files')
       );
$$;

comment on function llm_passthrough_endpoints_valid(jsonb) is
    'LLM 키 기능 권한 목록 형식 검사. 항목은 경로가 아니라 기능 이름이고 집합이 닫혀 있다(이름 열둘). 빈 배열은 제약 없음이 아니라 아무 기능도 없다는 뜻이며, chat도 이 목록에 있어야 쓸 수 있다. 지금 부여할 수 있는 이름과 상하 관계는 api가 검사한다.';

-- The requester's choice, kept apart from the reviewer's decision. The approval
-- screen opens on this list, so the reviewer starts from what was asked for
-- rather than from an empty form, and an approval that leaves its own list out
-- grants this one.
--
-- Existing rows are filled with ["chat"], whatever their status. Before this
-- column every request implicitly asked for chat, because every key had it, so
-- that is the true reading of every row, the pending ones included: a reviewer
-- of a request still pending starts from chat, as the key would have had.
--
-- The fill value is not left behind as a default. A new request has to say what
-- it asks for, and the api refuses one that does not; a column default would be
-- a second, silent answer to the same question. The cost is on rollback: an api
-- build that predates this column inserts without it and fails the not-null, so
-- LLM key requests cannot be submitted while such a build runs. Restoring a
-- default by hand for that window, and dropping it again on the way forward, is
-- the recovery.
alter table llm_key_request_details
    add column req_passthrough_endpoints jsonb not null default '["chat"]'::jsonb,
    add constraint llm_key_request_details_req_passthrough_endpoints_check
        check (llm_passthrough_endpoints_valid(req_passthrough_endpoints));

alter table llm_key_request_details
    alter column req_passthrough_endpoints drop default;

comment on column llm_key_request_details.req_passthrough_endpoints is
    '신청자가 고른 기능 권한 목록. 승인 화면의 초깃값이고, 승인 요청이 부여 목록을 생략하면 이 목록으로 승인한다. 무엇을 줄지는 승인자가 정한다. 이 칸이 생기기 전의 신청은 상태와 무관하게 ["chat"]으로 채웠다(그때는 모든 키가 권한 없이 Chat을 썼다).';

-- Every key gets chat, so the gateway can start enforcing it without taking
-- chat away from anyone. Revoked and expired keys too: the column has to mean the
-- same thing on every row, and a key that cannot authenticate is refused before
-- this list is read.
update llm_api_keys
   set passthrough_endpoints = passthrough_endpoints || '["chat"]'::jsonb
 where not passthrough_endpoints @> '["chat"]'::jsonb;

comment on column llm_api_keys.passthrough_endpoints is
    '이 키가 쓸 수 있는 기능 권한. 빈 배열은 아무 기능도 없다는 뜻이다(모델 허용·차단 목록과 반대). chat은 자체 서빙과 유료 두 축 모두에 걸리고, 나머지 기능은 유료(금액) 축 전용이다.';

-- Every approved request's grant, because that grant is read again after
-- approval: a recipient who joins later, or whose issuance failed and is retried,
-- gets a key built from this row. Pending requests are not touched here: their
-- granted list is overwritten by whatever the reviewer approves.
--
-- The account trigger is held off for this one statement. It re-checks the
-- whole row on any update, including "positive granted credit requires an
-- OpenRouter account", and V100 tightened that rule without rewriting the rows
-- approved before it. An approved row from that window would fail the check at
-- commit and stop this migration, although nothing here touches the account or
-- the amount. Keeping the trigger on would make this migration the place that
-- enforces V100 on old rows, which is not its job.
alter table llm_key_request_details disable trigger trg_llm_request_openrouter_account;

update llm_key_request_details d
   set granted_passthrough_endpoints = d.granted_passthrough_endpoints || '["chat"]'::jsonb
  from requests r
 where r.id = d.request_id
   and r.status = 'APPROVED'
   and not d.granted_passthrough_endpoints @> '["chat"]'::jsonb;

-- The update queued events for the table's other deferred trigger (the one
-- matching a detail row to its request's decision), and a table with pending
-- trigger events cannot be altered. Running the deferred checks now, rather
-- than at commit, is what lets the account trigger be switched back on; they
-- check the same rows either way.
set constraints all immediate;

alter table llm_key_request_details enable trigger trg_llm_request_openrouter_account;

comment on column llm_key_request_details.granted_passthrough_endpoints is
    '승인자가 부여한 기능 권한 목록. 빈 배열은 부여하지 않았다는 뜻이다. chat도 이 목록으로 부여한다.';

-- The account default is no longer read by anything: the approval screen now
-- opens on the requester's list. The column stays until this release has
-- settled, because dropping it here would make the api build before this
-- migration impossible to roll back to (it maps the column). Nobody reads it, so
-- it gets no chat either.
comment on column openrouter_accounts.default_passthrough_endpoints is
    '쓰이지 않는다. 승인 화면 초깃값은 신청자가 고른 목록(llm_key_request_details.req_passthrough_endpoints)으로 옮겼고, 이 칸은 이전 api 판으로 되돌릴 수 있도록 남겨 둔 것이다. 다음 정리 마이그레이션이 지운다.';

-- The rows above are part of the document the gateway serves, and a migration's
-- update does not move the generation the gateway polls on. Bump it so the
-- next poll carries the new lists. Update only: the row is created by the first
-- application write, never by a migration, so on a database that has never
-- served a gateway there is nothing to bump and nothing to insert.
update llm_gateway_state
   set generation = generation + 1, updated_at = now();
