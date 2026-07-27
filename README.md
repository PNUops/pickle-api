# pickle-api

Pickle(피클)은 부산대학교 구성원을 위한 셀프서비스 클라우드 플랫폼 **PNU Cloud**(정식 명칭: 부산대학교 클라우드 플랫폼)의 코드네임이다. 사용자가 웹
콘솔에서 VM을 신청하면 관리자 승인 후 Proxmox VE에 자동 프로비저닝되며, SSH 접속과
도메인 기반 HTTP(S) 공개까지 제공한다. 이 저장소는 그중 백엔드 API 서버를 담당한다.

## 구성

REST API(`/api/v1`) + JobRunr 백그라운드 워커 + Proxmox REST 클라이언트 + 주기
스케줄러가 하나의 fat jar로 묶여 동작한다.

- **REST API**: 콘솔이 사용하는 `/api/v1` 엔드포인트.
- **JobRunr 워커**: 프로비저닝/삭제 파이프라인, 라우트 적용 등 장시간 작업을 DB 큐
  기반으로 비동기 실행한다.
- **Proxmox REST 클라이언트**: `/api2/json` API로 VM을 clone·설정·기동한다.
- **주기 스케줄러**: 상태 폴링, 드리프트 점검, 만료 스윕, 알림 발송, 보존 정리 등.

스택: Spring Boot 4.1, Java 25, PostgreSQL 18, Flyway, JobRunr.

## API 계약

`contract/openapi.yaml`가 커밋된 as-built 스펙(springdoc가 생성)이다. 엔드포인트를
변경한 뒤에는 아래로 재생성한다.

```bash
mvn test -Dtest=ContractDriftTest -Dcontract.update=true
```

`ContractDriftTest`가 커밋된 스펙의 신선도와 실제 구현 표면을 강제하므로, 계약을
갱신하지 않으면 빌드가 실패한다.

환경 변수 `PICKLE_CONTRACT_MASTER`에 손으로 쓴 설계 계약(YAML) 경로를 주면
`ContractDriftTest`가 세 번째 검사(설계 계약의 path+method 집합 = 구현된 표면 ∪
아직 구현되지 않은 표면)를 추가로 수행한다. 설정하지 않으면 그 검사만 건너뛴다.

## 개발

```bash
scripts/setup-hooks.sh   # 최초 1회: git 훅 설치

# 로컬 기동에 필요한 값(기본값 없음)을 먼저 export 한다
export PICKLE_JWT_SECRET=...             # 32바이트 이상
export PICKLE_CREDENTIALS_KEY=...        # base64 32바이트
export PICKLE_SEED_SYSADMIN_PASSWORD=...
export PICKLE_SEED_ORGADMIN_PASSWORD=...

mvn spring-boot:run -Dspring-boot.run.profiles=dev   # 로컬 PostgreSQL 18(citext 확장 필요), :8080 기동
scripts/verify.sh        # checkstyle + 빌드 + 전체 테스트 (= mvn verify) + 공개 위생 검사 + 의존성 감사
```

프로파일을 지정하지 않으면 기동이 실패한다. `MailSender` 구현은 프로파일 한정
(dev/test = `MockMailSender`, staging/prod = `SmtpMailSender`)인데
`AsyncMailDispatcher`와 `NotificationDispatchJob`은 프로파일과 무관하게 이 빈을
주입받으므로, 활성 프로파일이 없으면 컨텍스트 생성 단계에서 빈을 찾지 못한다.
위 네 환경 변수도 비어 있으면 기동이 실패한다.

`scripts/verify.sh`는 checkstyle 하드 게이트(`pom.xml`의 validate 단계에 묶여
있고 `failOnViolation=true`, 위반 시 빌드 실패) → 컴파일 → 전체 테스트를 실행한
뒤, 마지막에 직접 의존성의 신규 버전 목록을 참고용으로 출력한다(이 감사는 빌드를
실패시키지 않는다).

dev 프로파일에서는 메일이 실제로 발송되지 않는다. `MockMailSender`가 저널에는
수신자/제목만 남기고, 본문 전체는 스풀 파일(`PICKLE_MOCK_MAIL_SPOOL`, 기본
`/var/lib/pickle/mock-mail.log`, 서비스 사용자 전용 `rw-------`)에 덧붙인다.
로컬에서 회원가입을 끝내는 데 필요한 인증 링크(= bearer 토큰)가 이 파일에 있다.
스풀 기록이 실패해도 경고 로그만 남고 발송 흐름은 계속되므로, 링크가 보이지
않으면 먼저 경로와 권한을 확인한다.

테스트는 Zonky embedded-postgres와 WireMock으로 동작하므로 도커가 필요 없다.
`users.email` 컬럼은 `citext` 확장을 사용한다. `V2__identity.sql`이
`create extension if not exists citext`를 실행하므로, 마이그레이션 역할은 확장 생성
권한(슈퍼유저 또는 사전 설치된 확장)을 가져야 한다.

## 환경 변수

관리형 환경에서는 비밀값을 `/etc/pickle/api.env`로 주입한다. 로컬 실행도
마찬가지로 비밀값을 직접 export 해야 한다 — 비밀값에는 커밋된 기본값이 없다
(test 프로파일만 자체 고정값을 쓴다).

기본값은 모두 `src/main/resources/application.yml` 기준이다.

### 코어

| 변수 | 용도 | 기본값 |
|---|---|---|
| `PICKLE_DB_URL` / `PICKLE_DB_USER` / `PICKLE_DB_PASSWORD` | PostgreSQL 접속 정보 | `jdbc:postgresql://localhost:5432/pickle_dev` / `pickle` / `pickle` |
| `PICKLE_JWT_SECRET` | HS256 서명 키(32바이트 이상). **필수** — 없으면 기동 즉시 실패 | 없음 (test 프로파일만 자체 고정값) |
| `PICKLE_CREDENTIALS_KEY` | 가역 자격증명 저장(`vms.initial_password_enc`)용 AES-256-GCM 키(base64 32바이트). **필수** — 없으면 기동 즉시 실패 | 없음 (test 프로파일만 자체 고정값) |
| `PICKLE_MFA_ENFORCE_ADMIN` | 관리자 계층 2FA 등록 강제(미등록 계정은 로그인은 되고 권한만 제한) | `false` (prod 프로파일은 `true`) |
| `PICKLE_JOBRUNR_DASH_ENABLED` / `PICKLE_JOBRUNR_DASH_USER` / `PICKLE_JOBRUNR_DASH_PASS` | JobRunr 대시보드(:8000) 노출과 basic auth 자격. 활성화하고 자격을 비워 두면 `JobRunrDashboardGuard`가 기동을 거부한다(JobRunr 자체는 인증 없이 열어 주는 fail-open) | `false` / 없음 / 없음 |
| `PICKLE_BOOTSTRAP_ADMIN_EMAIL` / `PICKLE_BOOTSTRAP_ADMIN_PASSWORD` | prod 최초 SYS_ADMIN 계정. **prod 배포 필수** — 비어 있거나 비밀번호가 12자 미만 또는 뻔한 값이면 `ProdBootstrapSeeder`가 기동을 중단한다 | 없음 |

### 메일

| 변수 | 용도 | 기본값 |
|---|---|---|
| `PICKLE_VERIFICATION_BASE_URL` | 인증 메일이 링크하는 콘솔 이메일 인증 페이지 기본 URL | `https://pickle.pnuops.com/verify-email` |
| `PICKLE_PASSWORD_RESET_BASE_URL` | 비밀번호 재설정 메일이 링크하는 콘솔 페이지 기본 URL | `https://pickle.pnuops.com/reset-password` |
| `PICKLE_MOCK_MAIL_SPOOL` | dev 전용. `MockMailSender`가 메일 본문 전체를 적는 스풀 파일 경로 | `/var/lib/pickle/mock-mail.log` (dev 프로파일) |
| `PICKLE_SMTP_HOST` / `PICKLE_SMTP_USERNAME` / `PICKLE_SMTP_PASSWORD` | 실제 SMTP 접속 정보(staging/prod 프로파일 전용. dev/test는 `MockMailSender`). 발신 주소도 `PICKLE_SMTP_USERNAME`을 쓴다 | 없음 (staging/prod에서 미설정이면 기동 실패) |
| `PICKLE_SMTP_PORT` | SMTP 포트(STARTTLS) | `587` |

### Proxmox / 프로비저닝

| 변수 | 용도 | 기본값 |
|---|---|---|
| `PICKLE_PROXMOX_TOKEN_ID` / `PICKLE_PROXMOX_TOKEN_SECRET` | PVE API 토큰. 기동 시점에는 검사하지 않고, 비어 있으면 첫 호출에서 명확한 오류로 실패한다(Proxmox를 쓰지 않는 dev/test를 위해) | 없음 |
| `PICKLE_PROXMOX_CA_CERT` | PVE API를 검증할 유일한 신뢰 CA PEM 경로(비우면 JVM 기본 트러스트스토어) | 없음 |
| `PICKLE_SSH_PLATFORM_PUBLIC_KEY` | 모든 VM에 authorize 되는 SSH 게이트웨이 상단 공개키. **미설정이면 프로비저닝이 fail-closed로 중단된다** | 없음 |
| `PICKLE_TERMINAL_PUBLIC_KEY` | 웹 터미널 브리지 공개키(게이트웨이 키와 별도로 주입/폐기). 선택 — 비우면 경고만 남기고 게이트웨이 키만 주입한다 | 없음 |
| `PICKLE_SSH_HOST` / `PICKLE_SSH_PORT` | 초기 비밀번호 응답에 노출하는 SSH 접속 주소. 비우면(`0`이면) 응답에서 null | 없음 / `0` |

### 내부 연동 (게이트웨이 · 프록시 · 터미널)

| 변수 | 용도 | 기본값 |
|---|---|---|
| `PICKLE_SSHGW_TOKEN` | `/internal` 호출용 공유 bearer. 비어 있으면 `/internal` 필터 체인이 모든 요청을 거부한다(fail-closed) | 없음 |
| `PICKLE_SSHGW_SOURCE_IP` | `/internal` 호출을 허용하는 출발지 IP | `172.30.1.30` |
| `PICKLE_SSHGW_RATE_LIMIT` / `PICKLE_SSHGW_GLOBAL_RATE_LIMIT` | `/internal` 분당 요청 한도(클라이언트별 / 게이트웨이 전체) | `60` / `600` |
| `PICKLE_PROXY_AGENT_URL` / `PICKLE_PROXY_AGENT_TOKEN` | 리버스 프록시 제어 에이전트 주소와 bearer. 토큰이 비면 첫 호출에서 fail-closed | `http://172.30.1.10:9443` / 없음 |
| `PICKLE_TERMINAL_BRIDGE_URL` / `PICKLE_TERMINAL_CONTROL_TOKEN` | 터미널 브리지 제어 주소와 bearer(강제 종료 전용). 토큰이 비면 종료 호출이 503으로 fail-closed | `http://172.30.1.30:8083` / 없음 |
| `PICKLE_TERMINAL_PER_USER_CAP` / `PICKLE_TERMINAL_PER_VM_CAP` / `PICKLE_TERMINAL_PER_ORG_CAP` | 동시 터미널 세션 상한(사용자 / VM / 조직) | `3` / `5` / `20` |
| `PICKLE_TERMINAL_RATE_LIMIT` | 터미널 티켓 발급 분당 한도(클라이언트 IP와 userId 양쪽에 적용) | `10` |
| `PICKLE_TERMINAL_SINGLE_INSTANCE` | 기동 시 단일 인스턴스 확인(PG advisory lock). 세션 미러와 상한 계산이 단일 인스턴스를 전제한다 | `true` |
| `PICKLE_PROXY_PUBLIC_IP` | 커스텀 도메인 A 레코드가 가리켜야 하는 리버스 프록시 공개 IPv4 | `164.125.249.87` |

## 시드 계정 (dev/test 프로파일 전용)

`DevDataSeeder`가 이메일/슬러그 기준으로 없을 때만 삽입한다(멱등).

| 계정 | 환경 변수 | 기본값 |
|---|---|---|
| SYS_ADMIN | `PICKLE_SEED_SYSADMIN_EMAIL` / `PICKLE_SEED_SYSADMIN_PASSWORD` | `admin@pickle.local` / 없음 |
| 기관 `테스트 기관` (slug `test-org`, hidden — USER 롤의 `GET /orgs` 목록에서 제외) | — | 자동 생성 |
| ORG_ADMIN (`test-org` 소속) | `PICKLE_SEED_ORGADMIN_EMAIL` / `PICKLE_SEED_ORGADMIN_PASSWORD` | `orgadmin@pickle.local` / 없음 |

비밀번호에는 기본값이 없다. dev 프로파일로 기동하려면 두 `*_PASSWORD` 값을
반드시 설정해야 하며, 비어 있으면 기동이 실패한다.

prod 프로파일에서는 `DevDataSeeder`가 아예 돌지 않고, 대신
`ProdBootstrapSeeder`가 `PICKLE_BOOTSTRAP_ADMIN_EMAIL` /
`PICKLE_BOOTSTRAP_ADMIN_PASSWORD`로 최초 SYS_ADMIN 한 명만 만든다. 둘 중 하나가
비어 있거나 비밀번호가 12자 미만 또는 뻔한 값이면 기동을 중단하고, SYS_ADMIN이
이미 있으면 아무것도 하지 않는다(멱등).

## 구조

```
src/main/java/kr/ac/pusan/pickle/   애플리케이션 코드 (기능별 패키지 구성)
src/main/resources/db/migration/    Flyway 마이그레이션 (스키마 원본)
scripts/                            verify + 훅 헬퍼
```

콘솔(pickle-console), SSH 게이트웨이(pickle-sshgw), 리버스 프록시
제어 에이전트(pickle-proxy-agent)가 형제 저장소다.

## 커밋 규약

`type: subject` 형식, 영어 명령형, 72자 이내(git 훅이 강제). type은 feat, fix,
docs, test, chore, refactor, perf, build, style, ci, revert, merge 중 하나이며
제목 끝에 마침표를 붙이지 않는다.

`scripts/hygiene.sh`는 이 저장소가 공개물이라는 전제를 검사한다 — 비공개 문서 저장소나 인프라 저장소를 가리키는 참조, 내부 진행 표기(마일스톤·웨이브 등)가 있으면 검증이 실패한다. 수동 점검이 두 차례 위반을 놓친 뒤 자동화했다.
