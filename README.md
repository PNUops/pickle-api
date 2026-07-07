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

Tests boot Zonky embedded-postgres; Proxmox calls are stubbed with WireMock.
The OpenAPI document is exposed at `/api/v1/openapi` and is the contract for the
`pickle-console` frontend.

## Layout

```
src/main/java/kr/ac/pusan/pickle/   application code (feature-package layout)
src/main/resources/db/migration/    Flyway migrations (schema source of truth)
scripts/                            verify + hook helpers
```
