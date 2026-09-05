-- Re-issue the column comments that still call the CREDIT axis 상용.
--
-- The screens, the error messages and the generated spec now say 유료 모델;
-- these comments are the last place the old word survives, and they are the
-- one place a reader meets it with no screen next to it to correct them. They
-- live in the database rather than in a file, so a later reader running \d
-- gets whatever was last written here.
--
-- The migrations that wrote them (V88, V100, V102, V104, V106, V109) are
-- applied and are not edited: changing an applied file by even one character
-- breaks its checksum and Flyway then refuses to start. A correction is
-- carried by the next migration, which is this one.
--
-- Eight objects, not the eleven the files mention. A later `comment on`
-- replaces an earlier one on the same object, and not every occurrence is a
-- comment at all. Asked of the live database rather than counted in the files:
--
--   7  live column comments   V88 x2, V97, V102, V106, V109 x2
--   1  live function comment  V109, superseding V102 and V104
--   2  dead function comments V102 and V104, replaced by the above
--   1  not a comment          V87 inserts it as an `llm_upstreams.note` value
--
-- The first count of this was seven, because it asked `col_description` and
-- then spoke about the database. Columns are one form of `comment on`; the
-- function comment survives `create or replace` and had to be re-issued here
-- too. The note row is data, so a migration must not touch it without operator
-- approval; nothing reads it into a response, so it reaches a person only
-- through psql.
--
-- Comments only. No schema change, no data.

comment on column llm_models.budget_axis is
    '이 모델의 사용이 어느 예산 축에 계상되는가. TOKEN=자체 서빙(일일 토큰 한도), CREDIT=유료 모델(키별 금액 한도).';

comment on column llm_api_keys.credit_limit is
    '유료 모델 한도, USD 크레딧. 0이면 유료 모델을 쓸 수 없다. daily_tokens의 null=무제한과 달리 금액에는 무제한이 없다.';

comment on column llm_api_keys.credit_allowed_models is
    '이 키가 쓸 수 있는 유료 모델 패턴. 빈 배열은 제한 없음이고 null 상태는 없다. 자체 서빙 모델은 이 목록과 무관하다.';

comment on column llm_api_keys.credit_denied_models is
    '이 키가 쓸 수 없는 유료 모델 패턴. 빈 배열은 차단 없음이고 null 상태는 없다. 허용 목록과 함께 걸리면 차단이 이긴다. 자체 서빙 모델은 이 목록과 무관하다.';

comment on column llm_api_keys.openrouter_account_id is
    '이 key의 유료 모델 지출을 대는 기관 사업 account. 금액 한도가 0보다 크면 반드시 있어야 하고 한 번 정해지면 바뀌지 않는다. V97이 두던 legacy 대체 출처는 V100이 없앴다.';

comment on column llm_key_request_details.req_use_commercial is
    '신청자가 유료 모델을 쓰겠다고 했는지. 금액은 req_credit_limit이 든다.';

comment on column llm_key_request_details.granted_credit_denied_models is
    '승인자가 부여한 유료 모델 차단 목록. 빈 배열은 차단 없음이다. 허용 목록과 달리 금액 한도가 0이어도 남는다.';

comment on function llm_credit_model_patterns_valid(jsonb) is
    '유료 모델 허용 목록과 차단 목록 항목의 형식 검사. 두 목록의 문법은 같고 뜻만 반대다. 모델 이름, 벤더 프리픽스(vendor/*), 모델 자리의 앞뒤 와일드카드 하나를 받고, 벤더 자리의 와일드카드와 ''*'' 단독과 대문자는 거부한다.';
