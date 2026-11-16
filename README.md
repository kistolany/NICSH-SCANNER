# cmt-backend

Backend for the CMT School Staff App (mobile) and Admin Web.

| Folder | What |
|---|---|
| `auth-server/` | Spring Authorization Server (OAuth2 / JWT), port 9200 |
| `cmt-api/` | Main API — modular monolith (`kh.cmt`), port 8080 |
| `web-bff/` | Backend-for-frontend of the Admin Web (login + token relay), port 8081 |
| `rate-limit/` | Shared library: request rate limiting (Bucket4j + Redis) used by auth-server and cmt-api |
| `infra/` | Local infrastructure: `cd infra && docker compose up -d` |
| `docs/` | Architecture, build steps, progress |
| `design/` | Reference designs (Staff App, Admin Web) |

Stack: Java 21, Spring Boot 3.5, Gradle (Kotlin DSL).

## Build

```
./gradlew build
```

Builds and tests both apps. See `docs/BACKEND ARCHITECTURE - Spring Boot.md` and `docs/BACKEND BUILD STEPS.md`; progress is tracked in `docs/PROGRESS.md`.

## Ports (local)

| App | Port |
|---|---|
| cmt-api | 8080 |
| web-bff | 8081 |
| auth-server | 9200 |
| Postgres / Redis | 5432 / 6379 |
| MinIO API / console | 9000 / 9001 |
| Mailhog UI / pgAdmin | 8025 / 5050 |



//docker compose stop
//CMT-Backend/start-dev.bat. 