# pickle-api

Backend of Pickle (부산대학교 클라우드 플랫폼): REST API, JobRunr background workers,
and the Proxmox VE client. Spring Boot 4.1 / Java 25 / PostgreSQL 18.

Design documents live in the `pickle-docs` repository (`docs/plan/`).

## Development

```bash
scripts/setup-hooks.sh   # once: install git hooks
mvn spring-boot:run      # needs local PostgreSQL (see docs/plan/08)
scripts/verify.sh        # build + all tests (embedded PostgreSQL, no Docker needed)
```

Tests boot Zonky embedded-postgres. Provisioning in M2 is an in-process mock (MockProvisionVmJob); WireMock stubs for the real Proxmox client arrive with M3.
The OpenAPI document is exposed at `/api/v1/openapi` and is the contract for the
`pickle-console` frontend (frozen source: `docs/api/openapi.yaml` in pickle-docs;
`ContractDriftTest` compares the implemented subset against it).

## Configuration (env vars)

Secrets come from `/etc/pickle/api.env` on managed environments (docs/plan/07);
locally, export them or rely on the dev/test defaults.

| Variable | Purpose | Default |
|---|---|---|
| `PICKLE_DB_URL` / `PICKLE_DB_USER` / `PICKLE_DB_PASSWORD` | PostgreSQL connection | `jdbc:postgresql://localhost:5432/pickle_dev` / `pickle` / `pickle` |
| `PICKLE_JWT_SECRET` | HS256 signing key (>= 32 bytes). **Required** outside dev/test — startup fails fast without it | dev/test only: built-in dev secret |
| `PICKLE_VERIFICATION_BASE_URL` | Base URL of the console verify-email page in mails | `https://pickle.pnuops.com/verify-email` |
| `PICKLE_SMTP_HOST` / `PICKLE_SMTP_PORT` / `PICKLE_SMTP_USERNAME` / `PICKLE_SMTP_PASSWORD` | Real SMTP (staging/prod profiles only; dev/test log mails via `MockMailSender`) | — |

The `users.email` column uses the `citext` extension; `V2__identity.sql` runs
`create extension if not exists citext`, so the migration role must be allowed
to create extensions (superuser or pre-installed extension).

## Seed accounts (dev/test profiles only)

`DevDataSeeder` idempotently inserts (insert-if-absent by email/slug):

| Account | Env vars | Dev default |
|---|---|---|
| SYS_ADMIN | `PICKLE_SEED_SYSADMIN_EMAIL` / `PICKLE_SEED_SYSADMIN_PASSWORD` | `admin@pickle.local` / `pickle-sysadmin-dev!` |
| Org `SW교육센터` (slug `sw-edu`) | — | created automatically |
| ORG_ADMIN (bound to `sw-edu`) | `PICKLE_SEED_ORGADMIN_EMAIL` / `PICKLE_SEED_ORGADMIN_PASSWORD` | `orgadmin@pickle.local` / `pickle-orgadmin-dev!` |

The defaults are development-only; set the env vars on any shared environment.

## Layout

```
src/main/java/kr/ac/pusan/pickle/   application code (feature-package layout)
src/main/resources/db/migration/    Flyway migrations (schema source of truth)
scripts/                            verify + hook helpers
```
