# pickle-api

Pickle(피클)은 부산대학교 구성원을 위한 셀프서비스 클라우드 플랫폼이다. 사용자가 웹
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

## 개발

```bash
scripts/setup-hooks.sh   # 최초 1회: git 훅 설치
mvn spring-boot:run      # 로컬 PostgreSQL 18(citext 확장 필요), :8080 기동
scripts/verify.sh        # 빌드 + 전체 테스트 (= mvn verify)
```

테스트는 Zonky embedded-postgres와 WireMock으로 동작하므로 도커가 필요 없다.
`users.email` 컬럼은 `citext` 확장을 사용한다. `V2__identity.sql`이
`create extension if not exists citext`를 실행하므로, 마이그레이션 역할은 확장 생성
권한(슈퍼유저 또는 사전 설치된 확장)을 가져야 한다.

## 환경 변수

관리형 환경에서는 비밀값을 `/etc/pickle/api.env`로 주입한다. 로컬에서는 직접
export 하거나 dev/test 프로파일의 기본값에 의존한다.

| 변수 | 용도 | 기본값 |
|---|---|---|
| `PICKLE_DB_URL` / `PICKLE_DB_USER` / `PICKLE_DB_PASSWORD` | PostgreSQL 접속 정보 | `jdbc:postgresql://localhost:5432/pickle_dev` / `pickle` / `pickle` |
| `PICKLE_JWT_SECRET` | HS256 서명 키(32바이트 이상). dev/test 외에는 **필수** — 없으면 기동 즉시 실패 | dev/test 전용 내장 기본값 |
| `PICKLE_CREDENTIALS_KEY` | 가역 자격증명 저장(`vms.initial_password_enc`)용 AES-256-GCM 키(base64 32바이트). dev/test 외에는 **필수** — 없으면 기동 즉시 실패 | dev/test 전용 내장 기본값 |
| `PICKLE_VERIFICATION_BASE_URL` | 인증 메일이 링크하는 콘솔 이메일 인증 페이지 기본 URL | `https://pickle.pnuops.com/verify-email` |
| `PICKLE_SMTP_HOST` / `PICKLE_SMTP_PORT` / `PICKLE_SMTP_USERNAME` / `PICKLE_SMTP_PASSWORD` | 실제 SMTP(staging/prod 프로파일 전용. dev/test는 `MockMailSender`로 메일을 로깅) | — |

## 시드 계정 (dev/test 프로파일 전용)

`DevDataSeeder`가 이메일/슬러그 기준으로 없을 때만 삽입한다(멱등).

| 계정 | 환경 변수 | dev 기본값 |
|---|---|---|
| SYS_ADMIN | `PICKLE_SEED_SYSADMIN_EMAIL` / `PICKLE_SEED_SYSADMIN_PASSWORD` | `admin@pickle.local` / `pickle-sysadmin-dev!` |
| 조직 `SW교육센터` (slug `sw-edu`) | — | 자동 생성 |
| ORG_ADMIN (`sw-edu` 소속) | `PICKLE_SEED_ORGADMIN_EMAIL` / `PICKLE_SEED_ORGADMIN_PASSWORD` | `orgadmin@pickle.local` / `pickle-orgadmin-dev!` |

기본값은 개발 전용이므로 공유 환경에서는 반드시 환경 변수를 설정한다.

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
