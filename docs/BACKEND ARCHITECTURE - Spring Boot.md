# CMT School — Backend Architecture (Spring Boot + OAuth2)

Covers everything the two designs need: **CMT Staff App** (mobile — Staff, Teacher, Admin) and **CMT Admin Web** (office users).
Goal: clean, easy to manage today, ready to scale later — without rewriting.

---

## 1. Big picture

```
 ┌──────────────┐   ┌──────────────┐
 │ Mobile app   │   │ Admin Web    │
 │ Flutter      │   │ React        │
 └──────┬───────┘   └──────┬───────┘
        │ OAuth2 PKCE      │ OAuth2 PKCE (via BFF)
        ▼                  ▼
 ┌─────────────────────────────────────┐      ┌───────────────────────┐
 │  Auth Server  (Spring Authorization │◄────►│ Users / roles / 2FA   │
 │  Server)  → issues JWT              │      │ device binding        │
 └─────────────────┬───────────────────┘      └───────────────────────┘
                   │ JWT
 ┌─────────────────▼───────────────────────────────────────────────┐
 │  CMT API  — ONE Spring Boot app, split into MODULES              │
 │  (modular monolith)                                              │
 │                                                                  │
 │  people · org · schedule · timetable · attendance · teaching     │
 │  leave · approvals · devices · qr-location · announcements       │
 │  reports · payroll · cards · files · notifications · audit       │
 │  settings · iam (roles/permissions)                              │
 └───┬───────────┬────────────┬────────────┬───────────┬───────────┘
     │           │            │            │           │
 PostgreSQL    Redis      Object storage  FCM / SMS   Message broker
 (main DB)   (cache,      (MinIO / S3:    / email     (later: Kafka /
             rate limit,   photos, docs,               RabbitMQ)
             QR tokens)    PDFs)
```

### Why a **modular monolith** (not microservices on day 1)
- One codebase, one deploy, one database → simple for a small team.
- Each module has its **own package, own tables, own public API** — modules talk only through interfaces or events, never through each other's tables.
- When one module gets heavy (usually **attendance**, **reports**, **notifications**), you cut it out into its own service **without** changing the others. The boundaries are already there.
- Enforce the boundaries with **Spring Modulith** (fails the build if a module reaches into another's internals).

---

## 2. Tech stack

| Area | Choice |
|---|---|
| Language / runtime | Java 21, Spring Boot 3.3+ |
| Modules | Spring Modulith |
| Security | Spring Authorization Server (or Keycloak), Spring Security Resource Server (JWT) |
| Database | PostgreSQL 16 + Flyway migrations |
| Data access | Spring Data JPA (writes) + jOOQ or JDBC (heavy reports) |
| Cache / locks | Redis (Spring Cache, rate limiting, QR token store) |
| Files | MinIO in dev / S3-compatible in prod, presigned URLs |
| Jobs | Spring `@Scheduled` + ShedLock (one runner across instances) |
| Events | Spring application events + **Transactional Outbox** table (later → Kafka) |
| Push / SMS / email | FCM, SMS gateway adapter, SMTP adapter |
| PDF / Excel | OpenPDF or Flying Saucer (cards, reports), Apache POI (Excel) |
| API docs | springdoc-openapi (Swagger UI) |
| Mapping / validation | MapStruct, Jakarta Validation |
| Observability | Micrometer + Prometheus + Grafana, OpenTelemetry tracing, JSON logs → Loki/ELK |
| Tests | JUnit 5, Testcontainers (Postgres, Redis), ArchUnit / Modulith tests |
| Deploy | Docker, docker-compose (dev), Kubernetes or VM + Nginx (prod), GitHub Actions CI |

---

## 3. Project structure

```
cmt-backend/
├─ auth-server/                     ← separate Spring Boot app (OAuth2)
├─ cmt-api/
│  └─ src/main/java/kh/cmt/
│     ├─ CmtApplication.java
│     ├─ shared/                    ← ONLY truly shared things
│     │  ├─ domain/      (BaseEntity, AuditFields, SoftDelete, Money, TimeRange)
│     │  ├─ web/         (ApiError, PageResponse, GlobalExceptionHandler)
│     │  ├─ security/    (CurrentUser, @RequirePermission, ScopeFilter, FieldMask)
│     │  ├─ i18n/        (LocalizedText km/en/ar)
│     │  └─ events/      (DomainEvent, Outbox)
│     ├─ people/
│     ├─ org/
│     ├─ schedule/
│     ├─ attendance/
│     ├─ ... one folder per module
│     └─ audit/
└─ docker-compose.yml
```

### Inside every module — the same 4 layers

```
attendance/
├─ api/            ← public: interfaces + DTOs other modules may use
│   AttendanceQueries.java
├─ web/            ← REST controllers + request/response DTOs
│   ScanController.java
├─ application/    ← use cases (one class per action), transactions here
│   CheckInUseCase.java   CheckOutUseCase.java   EditRecordUseCase.java
├─ domain/         ← entities, value objects, rules — NO Spring, NO JPA annotations if you can
│   AttendanceRecord.java   LatePolicy.java   ShiftResolver.java
└─ infrastructure/ ← JPA repositories, external adapters
    AttendanceJpaRepository.java
```

**Rule of thumb:** controller → use case → domain → repository. Business rules live in `domain/`, never in controllers. This is what keeps it easy to manage.

---

## 4. Modules (what each one owns)

| Module | Owns | Used by screens |
|---|---|---|
| **iam** | web users, roles, permission matrix, record scope, sensitive-field rules, approval limits, login rules per role, role history | Web users & roles |
| **people** | person record (names km/en/ar, auto Staff ID, role, dept, position, employment, emergency contact, pay info), documents metadata, inactive/soft delete | People, profile, app Profile |
| **org** | departments, positions, subjects, classes, rooms, school settings, numbering format | Departments & positions, Settings |
| **schedule** | shift templates (1 or 2 shifts, names, times, grace), weekly schedules per person/dept, date changes, holidays | Shifts & schedules, app My schedule, Today card |
| **timetable** | teaching periods, week grid per teacher/class, conflict check, publish/effective date, free slots | Teacher timetable, app Timetable |
| **attendance** | scan check-in/out, day records, late/early calculation, offline sync, corrections, "Edited by admin" | Scan, Today card, Staff attendance, app Attendance |
| **teaching** | hour confirmation (with location), late confirmation, extra hours, substitutes | Teacher hours, teacher app |
| **leave** | leave types, balances, requests (full/half/specific hours), affected hours | Leave & holidays, app Leave |
| **approvals** | one inbox over all request types (leave, correction, late/early reason, extra hour, late confirmation, report, device change), approve/reject/undo, escalation | Approvals (web + admin app) |
| **devices** | device binding, change requests, reset/block, app version / root / fake-GPS rules | Devices, blocked-login screen, My device |
| **qr-location** | school areas (geofence circles), gates, QR versions with 5-min expiry of the old one | Locations & QR |
| **announcements** | compose, audience, schedule, read receipts | Announcements, app Notifications |
| **notifications** | push/SMS/email sending, in-app notification list, reminders | everywhere |
| **reports** | attendance summary, staff work time, absence report, teacher hours, exports | Reports, Staff work time, app My report |
| **payroll** | pay types (monthly / hourly / per teaching hour / none), rate history, payroll export | People step 3, payroll export |
| **cards** | card template, card PDF (CR80, 300 DPI, A4 sheets), print history | Staff cards |
| **files** | upload, virus scan hook, thumbnails, presigned download, expiry tracking | documents, photos, attachments |
| **audit** | activity log (who, what, before → after, IP, device) | Activity log |

---

## 5. Security (OAuth2) — the important part

### 5.1 Two kinds of accounts (as in the design)
- **App users** = people (Staff / Teacher / Admin) → log in on the **mobile app**.
- **Web users** = office accounts → log in on the **Admin Web**. A web user can be *linked* to a person.
- Stored in the auth server; the API only trusts the JWT.

### 5.2 Flows
| Client | Flow | Notes |
|---|---|---|
| Mobile app | **Authorization Code + PKCE** (public client) | refresh token in secure storage (Keychain / Keystore), rotate on every refresh |
| Admin Web | **BFF pattern**: a small Spring Cloud Gateway / Spring BFF holds the tokens; browser gets an **HttpOnly, Secure, SameSite=Strict** session cookie | no tokens in the browser, CSRF protection on |
| Jobs / integrations | Client Credentials | e.g. payroll system pulling exports |

> If you prefer not to run your own auth server: **Keycloak** gives the same flows, 2FA and admin UI out of the box. Spring Authorization Server gives more control (device binding, custom claims). Either works with the same API.

### 5.3 Tokens
- **Access token**: JWT, 10–15 min, signed RS256/ES256, keys rotated (JWKS endpoint).
- Claims: `sub` (user id), `typ` (app|web), `person_id`, `role`, `perm_ver` (permission version), `device_id` (app only), `amr` (2FA done), `locale`.
- **Do not put the whole permission list in the token** — keep it small. The API loads permissions by `role` + `perm_ver` from Redis (cached), so a role change takes effect on the next request, and "Force logout everywhere" just bumps a version / revokes refresh tokens.
- **Refresh token**: opaque, stored hashed in DB, 30 days (app) / session-length (web), rotated, reuse = revoke whole family.

### 5.4 Device binding (from the design)
1. First login on the app → app sends a **device key** (public key generated on the phone, stored in Keystore/Secure Enclave) + device info.
2. Auth server binds `person ↔ device_id`. Token gets `device_id`.
3. Login from another phone → auth server returns error `DEVICE_MISMATCH` → app shows **"This account is linked to another phone"**.
4. "Request device change" → creates an approval of type `DEVICE_CHANGE`. Approve = reset binding.
5. Every **check-in request is signed** with the device private key → server verifies with the stored public key. A copied token alone cannot check in.
6. Blocked device → login and refresh refused. Min app version, root/jailbreak, fake-GPS flags are checked at login and at scan (Play Integrity / App Attest).

### 5.5 Authorization — 4 layers (matches the Roles screen)
1. **Module × action permission** (View, Create, Edit, Delete, Approve, Export) → `@RequirePermission(module = ATTENDANCE, action = EDIT)` on use cases. Rules: any action implies View; system roles locked; ≥1 active Super Admin; a user cannot change their own role.
2. **Record scope** (All school / selected departments / own department / own team) → applied automatically in queries via a `ScopeFilter` (Hibernate filter or a `Specification` added in every repository query). Never trust the client.
3. **Field-level** (salary & bank, national ID & documents, phone numbers, ID scans) → DTO mapper masks to `••••` and exports skip the columns. One `FieldMask` component used by all controllers and exporters.
4. **Approval limits** (leave ≤ N days, corrections older than 7 days, own requests) → checked in the approvals module; above limit → escalate to the configured role.

Plus **login rules per role**: 2FA required, session timeout, allowed hours/days, allowed IP ranges → enforced in the auth server (at login) and in a gateway filter (per request).

### 5.6 Other security must-haves
- 2FA: TOTP (authenticator app) + SMS fallback; required per role.
- Passwords: Argon2id/bcrypt, rules from Settings, lockout after 5 tries (rate limit in Redis), first-login forced change.
- Rate limiting per IP + per user (Bucket4j + Redis), stricter on login and scan.
- All input validated (Jakarta Validation), all output DTOs (never return entities).
- Files: presigned URLs that expire (5 min), content-type + size check, image re-encode, optional antivirus (ClamAV).
- Secrets in Vault / environment, never in git. TLS everywhere, HSTS.
- Audit log for every sensitive action (see §9).
- OWASP dependency check + SAST in CI.

---

## 6. Data model (core tables)

All tables: `id uuid`, `created_at`, `created_by`, `updated_at`, `updated_by`, `version` (optimistic lock). Deletable things also have `inactive`, `deleted_at`, `deleted_by`, `delete_reason`.

```
person(id, staff_id UNIQUE, name_km, name_en, name_ar, role, department_id, position_id,
       employment_type, start_date, contract_end, supervisor_id, campus, default_gate_id,
       phone, email, blood_type, emergency_name, emergency_relation, emergency_phone,
       photo_file_id, inactive, ...)
staff_id_sequence(prefix, year, last_value)            -- never reused
person_document(id, person_id, type, file_id, issue_date, expiry_date)
pay_rate(id, person_id, pay_type, amount, currency, overtime_rate, per_subject json,
         effective_from)                                -- history = rows, never update

department, position, subject, class_group, room

shift_template(id, name, color, grace_min, early_needs_reason)
shift_template_part(id, template_id, idx 1|2, name, start_time, end_time)
weekly_schedule(id, target_type person|dept|all, target_id, weekday, template_id|null=off, effective_from)
schedule_date_change(id, date, target_type, target_id, template_id|null, label)
holiday(id, date, name_km, name_en, kind national|school, scope)

timetable_period(id, code, start_time, end_time)
timetable_slot(id, teacher_id, weekday, period_id, subject_id, class_id, room_id, effective_from, published)

attendance_record(id, person_id, work_date, shift_idx, check_in_at, check_in_gate_id,
                  check_out_at, check_out_gate_id, late_min, early_min, status,
                  source scan|offline|manual|correction, edited_by, edit_reason)
                  UNIQUE(person_id, work_date, shift_idx)
scan_event(id, person_id, device_id, qr_version_id, lat, lng, accuracy, distance_m,
           client_time, server_time, result, idempotency_key UNIQUE)   -- raw, append-only

teaching_hour(id, teacher_id, date, slot_id, status scheduled|confirmed|missed|leave|extra|substitute,
              confirmed_at, lat, lng, substitute_for_id)

leave_type(id, name, days_per_year, unit day|half|hour, needs_attachment, color)
leave_balance(person_id, leave_type_id, year, entitled, used, adjusted)
leave_request(id, person_id, type_id, length full|half|hours, start_date, end_date, half am|pm,
              reason, attachment_file_id, status, substitute_suggestion)
leave_request_hour(id, request_id, teaching_hour_id, substitute_id)

approval(id, type, subject_type, subject_id, requester_id, status, decided_by, decided_at,
         comment, escalated_to_role)                    -- one inbox for all types

device(id, person_id, name, platform, os_version, app_version, public_key, bound_at,
       last_seen_at, status linked|blocked|reset)
device_change_request(...)  → approval

school_area(id, name, center_lat, center_lng, radius_m)
gate(id, name, area_id)
qr_version(id, gate_id, version, secret_hash, created_by, created_at, expires_at)

announcement, announcement_audience, announcement_read(announcement_id, person_id, read_at)
notification(id, person_id, kind, title, body, data json, read_at)

web_user(id, username, email, person_id null, role_id, scope_type, scope_depts[], two_factor,
         access_expires_on, status)
role(id, name, system bool, version), role_permission(role_id, module, action),
role_settings(role_id, sensitive json, approval_limits json, login_rules json)
role_history(id, role_id, version, snapshot json, changed_by, changed_at)

card_template, card_print(person_id, printed_by, printed_at)
file(id, bucket, key, mime, size, sha256, uploaded_by)
audit_log(id, at, actor_type, actor_id, action, target_type, target_id, before json, after json, ip, user_agent)
outbox(id, aggregate, type, payload json, created_at, published_at)
```

**Indexes that matter:** `attendance_record(work_date, person_id)`, `scan_event(server_time)`, `teaching_hour(date, teacher_id)`, `approval(status, type)`, `audit_log(at)`.
**Big tables later:** partition `scan_event`, `attendance_record`, `audit_log` by month (PostgreSQL native partitioning).

---

## 7. Key business flows

### 7.1 QR check-in (Scan screen)
```
App: scan QR → get location → sign {qr, lat, lng, accuracy, time, idempotencyKey} with device key
 → POST /api/v1/attendance/scan
API:
 1. verify JWT + device signature + device not blocked + app version ok
 2. resolve QR → gate → qr_version: active, or old one still inside its 5-min grace?  else QR_EXPIRED / QR_INVALID
 3. geofence: distance(point, area center) ≤ radius (+ accuracy tolerance)          else OUTSIDE_AREA (1.2 km)
 4. today's schedule for the person (date change > weekly schedule > holiday/leave)  → which shift / check-in or check-out
 5. compute late/early with grace → status On time / Late 12 min / Early 1h 30m (needs reason)
 6. save scan_event (idempotent) + upsert attendance_record in one transaction
 7. publish AttendanceRecorded event (outbox) → notifications, reports cache
 → response tells the app exactly which result screen to show (on time / late / out / early sheet / already / wrong QR)
```
- **Offline**: app stores the signed scan with its client time; on reconnect sends it with the same idempotency key. Server accepts within a window (e.g. 24 h), marks `source=offline`, re-checks QR version that was valid at that time.
- **Two shifts**: `shift_idx` 1 or 2 resolved by time; late/early per shift.

### 7.2 Teacher hour confirmation
`POST /teaching/hours/{id}/confirm` → allowed from hour start until end of day, location must be inside area → `confirmed`. After the day → only `late-confirmation` request (approval). Extra hour → request on a free slot; **first request locks the slot** (unique constraint on slot + date).

### 7.3 Approvals
One `approval` table + a `ApprovalHandler` per type (Strategy pattern):
`LeaveHandler`, `CorrectionHandler`, `LateReasonHandler`, `ExtraHourHandler`, `LateConfirmHandler`, `TeacherReportHandler`, `DeviceChangeHandler`.
`approve()` / `reject(comment)` → handler applies the effect (update balance, edit record, reset device…) → event → notification "Approved · Name notified". Undo = a compensating action within 10 s.

### 7.4 Required vs worked hours (Staff work time report)
- Required = scheduled shift minutes in period − holidays − approved leave.
- Worked = Σ(check-out − check-in) per shift; missing check-out = 0 + flag.
- Teachers: required = scheduled teaching hours; worked = confirmed + approved extra.
- Run as SQL (jOOQ) on a **daily summary table** `attendance_daily_summary` filled by a nightly job + updated by events → reports stay fast for years of data.

### 7.5 Scheduled jobs (ShedLock)
- every minute: send reminders (check-in, check-out, Shift 2 in 10 min, class in 10 min)
- end of day: mark missing check-outs, missed teaching hours, absences
- nightly: daily summaries, document expiry alerts (30 days), auto-inactivate expired web users, auto-rebind devices if enabled
- monthly: emailed reports, payroll export

---

## 8. API design

- REST, JSON, base path `/api/v1`. Separate route groups: `/api/v1/app/**` (mobile) and `/api/v1/admin/**` (web) — easier to secure and version.
- Resources: `/people`, `/people/{id}/documents`, `/schedules`, `/attendance/records`, `/attendance/scan`, `/approvals?type=&status=`, `/devices`, `/roles/{id}/permissions`, `/reports/work-time?period=`…
- Lists: `?page=0&size=25&sort=shortfall,desc&q=&dept=&status=` → `{ items, page, size, total }`.
- Errors: RFC 7807 problem JSON with a stable `code` the app maps to text: `OUTSIDE_AREA`, `QR_EXPIRED`, `DEVICE_MISMATCH`, `ALREADY_CHECKED_IN`, `LOCATION_OFF`, `VALIDATION_ERROR`…
  → **all display text stays in the apps** (km / en / ar), the API returns codes + numbers (e.g. `distanceKm: 1.2`).
- Writes that can be retried send `Idempotency-Key`.
- Optimistic locking: `If-Match` / `version` field → "Someone else changed this" instead of silent overwrite.
- OpenAPI spec generated → the Flutter and React agents generate their API clients from it (no hand-written models).

---

## 9. Audit, soft delete, history

- **Soft delete everywhere** (`inactive=true` + who/when/why). A global Hibernate filter hides inactive rows by default; the "Inactive" tab asks for them explicitly. "Delete forever" only when no history + Super Admin.
- **Audit log** written by an `@Audited` aspect on use cases + JPA entity listeners for before/after JSON. Read-only API, nobody can edit/delete it.
- **History tables** (never update in place): pay rates, role versions, QR versions, schedule effective dates → past months always calculate with the rule that was valid then.

---

## 10. Scaling path (do it only when needed)

| Stage | When | What |
|---|---|---|
| 1 | 1 school, < 500 people | 1 API instance + Postgres + Redis + MinIO on one server, docker-compose |
| 2 | more load / high availability | 2+ stateless API instances behind Nginx/LB, Postgres managed + read replica for reports, Redis managed |
| 3 | many schools (SaaS) | add `school_id` (**tenant**) to every table from day 1 (cheap now, painful later) + Postgres RLS or tenant filter; one auth realm per school or tenant claim |
| 4 | very heavy modules | move **notifications**, **reports**, **attendance-scan** into own services; outbox → Kafka; reports read from a replica / warehouse |

Already safe for scale because: stateless API, JWT, idempotent scans, outbox events, ShedLock jobs, summary tables, partitioning plan.

> **Recommendation:** add `school_id` now even if you have one school.

---

## 11. Environments & DevOps

- `docker-compose.yml` for dev: postgres, redis, minio, mailhog, auth-server, cmt-api.
- Profiles: `dev`, `staging`, `prod`. Config via env vars.
- CI (GitHub Actions): build → unit tests → Testcontainers integration tests → Modulith boundary test → OWASP check → Docker image → deploy staging → manual approve → prod.
- Flyway runs migrations on start; never edit an old migration.
- Backups: daily Postgres dump + WAL archiving, object storage versioning; test restore monthly.
- Monitoring: health/readiness endpoints, Grafana dashboards (scan latency, failed logins, queue lag), alerts.

---

## 12. Build order (suggested)

1. Skeleton: auth-server + cmt-api + docker-compose, shared kernel, error format, audit, soft delete.
2. **iam + people + org** (login, roles, permission matrix, scope, field masks, auto Staff ID).
3. **schedule + qr-location + devices + attendance** (scan end-to-end with the app).
4. **leave + approvals + notifications**.
5. **timetable + teaching** (teacher app).
6. **reports + payroll + cards + announcements + files**.
7. Hardening: rate limits, 2FA per role, login rules, monitoring, load test the scan endpoint (e.g. 300 people scanning between 07:30–07:45).

---

## 13. Prompt to give your coding agent

```
Build the CMT School backend exactly as described in "BACKEND ARCHITECTURE - Spring Boot.md".
- Java 21, Spring Boot 3, Spring Modulith modular monolith, PostgreSQL + Flyway, Redis, MinIO.
- Separate auth-server using Spring Authorization Server: Authorization Code + PKCE for the mobile app,
  BFF session cookie for the admin web, JWT access tokens (15 min), rotating refresh tokens,
  device binding with signed check-in requests.
- Every module uses the layers web / application / domain / infrastructure / api.
- Implement authorization in 4 layers: module×action permissions, record scope, field masking, approval limits.
- Soft delete + audit log on everything. Add school_id to every table.
- Follow the build order in section 12. After each step: run tests, show me the endpoints in Swagger, then continue.
- Read the two design files (CMT Staff App v7.dc.html, CMT Admin Web.dc.html) to get every field,
  status, rule and error case. Return error CODES, not display text.
```
