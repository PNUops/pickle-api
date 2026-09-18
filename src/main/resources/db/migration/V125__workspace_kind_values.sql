-- The workspace kind becomes a real classification.
--
-- Until now the enum held three values and only one of them meant anything:
-- PERSONAL decides automatic creation, undeletability and member management,
-- while TEAM and PROJECT never branched anywhere. Courses were being recorded
-- as PROJECT and told apart by reading the workspace name.
--
-- The axis is who runs the space, not what the activity is called. A
-- department-run competition follows the department; a team that enters one on
-- its own is a competition. PROJECT keeps its name and becomes the residual:
-- a group building something that is none of the above.
--
-- Alone in its own file on purpose: PostgreSQL refuses to use an enum value in
-- the same transaction that added it, and Flyway runs one file per
-- transaction. The remap that uses these values is V126.
alter type workspace_kind add value if not exists 'COURSE';
alter type workspace_kind add value if not exists 'PROGRAM';
alter type workspace_kind add value if not exists 'LAB';
alter type workspace_kind add value if not exists 'CLUB';
alter type workspace_kind add value if not exists 'COMPETITION';
alter type workspace_kind add value if not exists 'STUDY';

-- Naming a value here does not order it on screen. Display order is a rule the
-- console owns, so nothing may reach for 'add value ... before/after' to make
-- this declaration match what a list shows.
comment on type workspace_kind is
    '워크스페이스 유형. 분류와 표시 전용이며 권한, 한도, 기간, 승인 경로를 바꾸지 않는다. '
    'PERSONAL은 가입 시 자동 생성되는 본인 전용 공간이고 다른 유형으로 바꿀 수 없다. '
    'TEAM은 폐기된 값이라 새로 만들 수 없다. 표시 순서는 이 선언 순서가 아니다.';
