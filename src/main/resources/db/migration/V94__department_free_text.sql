-- 소속을 두 모양으로 받는다.
--
-- 학생 직책은 학과 카탈로그에서 고른다(users.department_code). 교수·연구원·직원은
-- 학과가 아니라 연구소나 부서에 속할 수 있어 목록으로는 담기지 않으므로 직접 적는다.
-- 목록에 없는 학과를 가진 학생도 같은 칸을 쓴다(코드는 OTHER).
--
-- 소속으로 사람을 거르는 화면이 없다는 것이 이 결정의 전제다. 읽는 곳은 표시용
-- 이름 해석 하나뿐이고 필터에도 권한 판정에도 쓰이지 않는다. 그것이 바뀌면 자유
-- 입력의 표기 변형(「정보컴퓨터공학부」·「정컴」·「CSE」)이 그때 문제가 된다.

alter table users add column department_other varchar(100);

-- 코드와 자유 입력이 함께 서는 경우는 하나뿐이다. 학생이 카탈로그의 OTHER 를
-- 고르고 실제 학과를 적는 경우. 그 밖에는 둘 중 하나만 값을 갖는다 — 둘 다 있으면
-- 읽는 쪽이 어느 것이 참인지 판단해야 한다.
alter table users add constraint chk_users_department_other
    check (department_other is null
        or department_code is null
        or department_code = 'OTHER');
