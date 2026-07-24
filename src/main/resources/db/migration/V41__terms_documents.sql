-- Terms of service + privacy policy documents. One row per
-- (doc_type, version); the "current" version per doc_type is the highest
-- version whose effective_at has passed. v1 of both documents is seeded here
-- with effective_at = now() so consent is enforceable immediately.
--
-- NOTE: the seeded Korean text is delegated-review pending — it must be reviewed
-- by an operator before the production launch (dev deploy is fine meanwhile).

create type terms_doc_type as enum ('TERMS_OF_SERVICE', 'PRIVACY_POLICY');

create table terms_versions (
    id           bigint generated always as identity primary key,
    doc_type     terms_doc_type not null,
    version      int not null,
    title        text not null,
    body         text not null,
    effective_at timestamptz not null,
    created_at   timestamptz not null default now(),
    unique (doc_type, version)
);

create index terms_versions_current_idx on terms_versions (doc_type, version desc);

comment on table terms_versions is
    'Versioned terms/privacy documents. Current version per doc_type = max(version) with effective_at <= now().';

insert into terms_versions (doc_type, version, title, body, effective_at) values
('TERMS_OF_SERVICE', 1, '피클 서비스 이용약관', $body$# 피클(Pickle) 서비스 이용약관

## 제1조 (목적)
이 약관은 부산대학교 클라우드 플랫폼 "피클"(이하 "서비스")의 이용 조건과
운영자·이용자의 권리·의무를 규정합니다.

## 제2조 (서비스 제공 범위)
- 서비스는 학내 구성원에게 가상 머신(VM) 생성·관리, SSH 접속, HTTP 공개 등
  클라우드 자원을 제공합니다.
- 서비스는 **단일 호스트 기반의 개발·교육용 인프라**로 운영되며, 제공 범위와
  용량은 운영 사정에 따라 달라질 수 있습니다.

## 제3조 (계정)
- 계정은 **@pusan.ac.kr 이메일을 가진 본인만** 생성할 수 있으며, 타인에게
  양도·대여할 수 없습니다.
- 이용자는 계정 및 인증 수단(비밀번호·2단계 인증)을 안전하게 관리할 책임이
  있으며, 계정을 통해 발생한 활동에 대한 책임을 집니다.

## 제4조 (자원 회수 및 제재)
운영자는 다음의 경우 사전 통지 없이 계정을 비활성화하거나 VM을 강제
삭제·정지할 수 있습니다.
- 보안 사고가 발생했거나 발생이 우려되는 경우
- 본 약관 또는 학내 정책을 위반한 경우
- 자원을 과도하게 점유하거나 남용하는 경우

## 제5조 (데이터 보관 및 책임의 한계)
- **서비스는 이용자의 VM 데이터를 백업하지 않습니다.** VM 삭제·장애·호스트
  장애 등으로 인한 데이터 손실의 책임은 이용자에게 있으며, 중요한 데이터는
  이용자가 별도로 백업해야 합니다.
- 서비스는 **가동률(SLA)을 보장하지 않습니다.** 단일 호스트 인프라 특성상
  점검·장애로 인한 중단이 발생할 수 있습니다.

## 제6조 (서비스의 변경 및 중단)
운영자는 운영상·기술상 필요에 따라 서비스의 전부 또는 일부를 변경하거나
중단할 수 있으며, 중요한 변경은 콘솔 공지 등으로 안내합니다.

## 제7조 (문의)
서비스 이용과 관련한 문의는 운영자 문의 채널(콘솔 하단 문의처)로 연락해
주시기 바랍니다.
$body$, now()),
('PRIVACY_POLICY', 1, '개인정보처리방침', $body$# 피클(Pickle) 개인정보처리방침

## 1. 수집하는 개인정보 항목
- **계정 정보**: 이메일 주소, 이름
- **접속·활동 기록**: 로그인 IP 주소, 서비스 이용·요청 기록, 감사 로그

## 2. 개인정보의 이용 목적
- 계정 식별 및 인증, 서비스 제공과 자원 관리
- 부정 이용 방지, 보안 사고 대응, 감사 및 기록 무결성 유지
- 문의 응대 및 서비스 운영 관련 통지

## 3. 보유 및 이용 기간
- **탈퇴 후에도 계정 행(레코드)은 영구 보존하며 익명화하지 않습니다**
  (2026-07-08 운영 결정). 이는 부정 이용 방지와 기록 무결성 확보를 위한
  것이며, 그 결과 **동일한 이메일로는 다시 가입할 수 없습니다.**
- **감사 로그는 영구 보존**됩니다.

## 4. 소속에 따른 관리자 열람 범위
- 기관 관리자는 소속(파생 소속 포함) 이용자의 계정·자원 현황을 열람할 수
  있습니다.
- 공유 그룹(팀·프로젝트)에 속한 경우, 해당 그룹과 연관된 관리자는 **로그인
  IP를 포함한** 활동 정보를 열람할 수 있습니다.

## 5. 제3자 제공
서비스는 법령에 따른 경우를 제외하고 이용자의 개인정보를 외부 제3자에게
제공하지 않습니다.

## 6. 문의처
개인정보 처리에 관한 문의는 운영자 문의 채널(콘솔 하단 문의처)로 연락해
주시기 바랍니다.
$body$, now());
