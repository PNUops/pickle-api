-- The gateway serves three client paths today, so a key that reaches the money
-- axis reaches all of it and no per-key surface question has ever arisen. The
-- passthrough round opens more of what OpenRouter sells, and the moment it
-- lands every money-axis key would gain every new path at once: a key issued
-- for a course that only chats would gain image generation, which settles per
-- image rather than per token and so can spend an approved amount in a shape
-- nobody approved.
--
-- This adds the list that answers that question. It is deliberately NOT an
-- extension of the two model lists beside it, and the difference is the empty
-- value. Those two say "an empty list places no constraint"; this one has to
-- default to closed, because a path nobody granted must not open by the mere
-- act of the gateway learning to serve it. Folding the opposite default into
-- the same mechanism would put two meanings on one shape.
--
-- Keeping them apart costs nothing here because the axes do not overlap. This
-- list governs ONLY the passthrough paths the gateway adds. The two paths it
-- serves today, chat completions and the model catalogue, are outside it and
-- keep the fences they already have, so an empty list can mean "no passthrough
-- path" without taking anything away from a key that exists now. That is also
-- why an old control plane, which sends no such member at all, is safe against
-- a gateway that reads one: absent reads as empty, empty means no passthrough
-- path, and the paths in question are exactly the ones an old gateway does not
-- serve either.
--
-- The TOKEN axis (self-serving models) is outside this list as it is outside
-- the other two. Self-serving offers chat completions and nothing else, so
-- there is no surface there to fence.

-- Entries name a capability, not a path. The approval screen shows this
-- vocabulary and an approver has to read it and decide, so "images" carries
-- better than "POST /v1/images" plus the catalogue read that belongs with it.
-- It also survives the vendor adding a sub-path under a capability already
-- granted, which a path list would not: that would silently widen or silently
-- break depending on how the gateway matched.
--
-- The set is closed on purpose. An unknown token cannot be stored, so a typo
-- in a form fails at the write rather than becoming a permission nobody can
-- explain later. Adding a capability is a migration, which is the right cost:
-- it is a decision about what the platform resells.
create or replace function llm_passthrough_endpoints_valid(endpoints jsonb)
returns boolean language sql immutable as $$
    select jsonb_typeof(endpoints) = 'array'
       and jsonb_array_length(endpoints) <= 20
       and not exists (
           select 1 from jsonb_array_elements(endpoints) e
            where jsonb_typeof(e) <> 'string'
               or (e #>> '{}') not in ('images', 'embeddings')
       );
$$;

comment on function llm_passthrough_endpoints_valid(jsonb) is
    '패스스루 표면의 키 단위 허용 목록 형식 검사. 항목은 경로가 아니라 기능 이름이고 집합이 닫혀 있다. 빈 배열은 제약 없음이 아니라 패스스루 경로 없음이며, 그 점이 모델 허용·차단 목록과 다르다.';

-- What the gateway serves: the live state of an issued key.
--
-- Without the "needs a positive amount" rule that guards the model allow list.
-- That rule exists because an allow list with no money behind it restricts
-- nothing and so is a misread form. Here the reading is the opposite: this list
-- is what grants, so an entry with no money behind it grants a path the key
-- cannot pay to use, which fails closed at the credential check the way it
-- already does. Requiring money would also make the column impossible to fill
-- in advance of funding a key, which is a shape approvers may reasonably want.
alter table llm_api_keys
    add column passthrough_endpoints jsonb not null default '[]'::jsonb,
    add constraint llm_api_keys_passthrough_endpoints_check
        check (llm_passthrough_endpoints_valid(passthrough_endpoints));

comment on column llm_api_keys.passthrough_endpoints is
    '이 키가 쓸 수 있는 패스스루 기능. 빈 배열은 패스스루 경로가 하나도 없다는 뜻이다(모델 허용·차단 목록과 반대). 자체 서빙(토큰) 축과 기존 두 경로는 이 목록과 무관하다.';

-- What the reviewer decided, kept beside the other granted limits.
alter table llm_key_request_details
    add column granted_passthrough_endpoints jsonb not null default '[]'::jsonb,
    add constraint llm_key_request_details_passthrough_endpoints_check
        check (llm_passthrough_endpoints_valid(granted_passthrough_endpoints));

comment on column llm_key_request_details.granted_passthrough_endpoints is
    '승인자가 부여한 패스스루 기능 목록. 빈 배열은 부여하지 않았다는 뜻이다.';

-- The prefill source for the approval form, on the same terms as the two model
-- lists: copied once at approval, never inherited at runtime, so editing it
-- moves no already-issued key.
alter table openrouter_accounts
    add column default_passthrough_endpoints jsonb not null default '[]'::jsonb,
    add constraint openrouter_accounts_passthrough_endpoints_check
        check (llm_passthrough_endpoints_valid(default_passthrough_endpoints));

comment on column openrouter_accounts.default_passthrough_endpoints is
    '승인 화면 프리필용 패스스루 기능 기본값. 런타임 상속이 아니므로 이 값을 바꿔도 이미 발급된 키는 바뀌지 않는다.';
