# CMT School — Backend Build Steps (every step, both apps)

Use together with **BACKEND ARCHITECTURE - Spring Boot.md**.
One backend serves **both** the mobile app (Staff / Teacher / Admin) and the Admin Web.

**How to use with your coding agent:** give it this file and say:
> "Do STEP 0.1 only. When the ✅ Done checks all pass, show me, then wait. Then I say 'next step'."

Every step has: **Do** (tasks) · **API** (endpoints) · **✅ Done when** (how you check).
`[APP]` = mobile app uses it · `[WEB]` = admin web uses it.

---

## PHASE 0 — Setup (1–2 days)

### STEP 0.1 — Create the repository
**Do**
- Git repo `cmt-backend` with folders `auth-server/`, `cmt-api/`, `infra/`, `docs/`.
- Java 21, Gradle (Kotlin DSL) or Maven, Spring Boot 3.3+.
- `.gitignore`, `.editorconfig`, `README.md`.
**✅ Done when** `./gradlew build` passes for both apps (empty).

### STEP 0.2 — Local infrastructure
**Do** `infra/docker-compose.yml` with: PostgreSQL 16, Redis 7, MinIO, Mailhog, pgAdmin.
**✅ Done when** `docker compose up` starts all; you can open pgAdmin and MinIO console.

### STEP 0.3 — cmt-api skeleton
**Do**
- Dependencies: web, validation, data-jpa, flyway, postgresql, redis, security, oauth2-resource-server, actuator, springdoc-openapi, mapstruct, lombok (optional), spring-modulith.
- Profiles `dev`, `staging`, `prod`; config from env vars.
- Package `kh.cmt` with `shared/` and empty module folders (see Architecture §3/§4).
**✅ Done when** app starts, `GET /actuator/health` = UP, Swagger at `/swagger-ui.html`.

### STEP 0.4 — Shared kernel
**Do**
- `BaseEntity` (id uuid, school_id, created/updated at/by, version).
- `SoftDeletable` (inactive, deleted_at, deleted_by, delete_reason) + Hibernate filter "active only".
- `LocalizedText` (km, en, ar).
- `PageResponse<T>` `{items, page, size, total}`.
- Error format RFC 7807 + `ErrorCode` enum + `GlobalExceptionHandler`.
- `Clock` bean (Asia/Phnom_Penh) — never call `LocalDateTime.now()` directly.
**✅ Done when** a test controller returns a problem JSON `{type, title, status, code}`.

### STEP 0.5 — Database migrations
**Do** Flyway `V1__base.sql`: table `school`, `outbox`, `audit_log`, `file`. Insert one school (CMT).
**✅ Done when** app start creates tables; `flyway_schema_history` has V1.

### STEP 0.6 — Testing + CI
**Do** JUnit 5, Testcontainers (Postgres, Redis), Modulith `verify()` test, ArchUnit rule "controllers never call repositories". GitHub Actions: build + test on every push.
**✅ Done when** CI is green on GitHub.

---

## PHASE 1 — Security & login (4–6 days)

### STEP 1.1 — Auth server
**Do**
- `auth-server` with Spring Authorization Server, own Postgres schema `auth`.
- Tables: `account` (id, username, password_hash, type app|web, person_id, web_user_id, status, failed_attempts, locked_until, must_change_password), `refresh_token` (hashed, family_id, rotated_from), `oauth2_*` tables.
- Clients: `cmt-mobile` (public, Authorization Code + PKCE), `cmt-web-bff` (confidential), `cmt-jobs` (client credentials).
- Keys: RS256, JWKS endpoint, key rotation support.
**✅ Done when** you get a token with Postman using PKCE for a test user.

### STEP 1.2 — Token content
**Do** Custom claims: `typ`, `person_id`, `web_user_id`, `role`, `perm_ver`, `device_id`, `amr`, `locale`, `school_id`. Access 15 min, refresh 30 days (app), rotate on refresh, reuse → revoke family.
**✅ Done when** decoded JWT shows the claims; using an old refresh token twice revokes the session.

### STEP 1.3 — Password rules & lockout
**Do** Argon2id, rules from settings (min 8, upper, number…), 5 failed tries → locked 15 min (Redis counter), first login → `must_change_password` → change-password page/endpoint.
**API** `POST /auth/password/change`, `POST /auth/password/forgot`, `POST /auth/password/reset`
**✅ Done when** 5 wrong passwords lock; first login forces change.

### STEP 1.4 — Two-step verification (web users)
**Do** TOTP (authenticator) + SMS fallback; required per role (from iam settings). `amr` claim includes `otp`.
**✅ Done when** a role with 2FA on cannot finish login without the 6-digit code.

### STEP 1.5 — Web BFF
**Do** Small Spring Cloud Gateway / Spring BFF app: does the OAuth login for the browser, stores tokens server-side, gives HttpOnly Secure SameSite cookie, CSRF on, proxies `/api/**` to cmt-api with the access token. `[WEB]`
**✅ Done when** React calls `/api/v1/admin/me` with only the cookie.

### STEP 1.6 — Resource server in cmt-api
**Do** JWT validation via JWKS, `CurrentUser` helper, route groups: `/api/v1/app/**` requires `typ=app`, `/api/v1/admin/**` requires `typ=web` (or app admins for approval endpoints).
**API** `GET /api/v1/app/me` `[APP]` · `GET /api/v1/admin/me` `[WEB]`
**✅ Done when** an app token gets 403 on `/admin/**`.

### STEP 1.7 — Rate limiting
**Do** Bucket4j + Redis: login 10/min/IP, scan 20/min/user, general 300/min/user.
**✅ Done when** load test returns 429 after the limit.

---

## PHASE 2 — Organisation & settings (2–3 days)

### STEP 2.1 — Org module
**Do** Entities: `department` (name km/en, color), `position`, `subject` (color), `class_group`, `room`. Soft delete; inactive ones hidden from dropdowns but still shown on old records.
**API** `GET/POST/PUT/DELETE /admin/departments` (and same for positions, subjects, classes, rooms), `POST /admin/{x}/{id}/restore` `[WEB]`
**✅ Done when** Departments & positions page works end to end.

### STEP 2.2 — School settings
**Do** `school_settings` (name km/en, logo, address, working days, timezone), attendance rules (grace, early needs reason, offline allowed, missing check-out rule), teacher rules (confirm window, late confirm needs approval, extra needs approval), notification timings, default language/theme.
**API** `GET/PUT /admin/settings/{section}` `[WEB]`, `GET /app/settings/public` `[APP]`
**✅ Done when** Settings page saves and the app reads grace minutes.

### STEP 2.3 — Numbering (auto Staff ID)
**Do** `staff_id_sequence` (prefix, separator, length, per-role prefix, year part). Generate with `SELECT … FOR UPDATE` → never reused. Preview endpoint.
**API** `GET /admin/settings/numbering/preview`, `PUT /admin/settings/numbering`
**✅ Done when** two parallel creates never get the same ID.

---

## PHASE 3 — IAM: web users, roles, permissions (4–5 days)

### STEP 3.1 — Roles & permission matrix
**Do** `role` (name, system, version), `role_permission` (module, action). Modules: dashboard, approvals, attendance, teacherHours, people, shifts, timetable, leave, qr, announcements, reports, users, settings, log. Actions: view, create, edit, delete, approve, export. Not-applicable cells rejected. Seed: Super Admin, HR, Academic, QR Manager, Viewer.
Rules: any action ⇒ view; system roles locked; at least 1 active Super Admin; user cannot edit own role.
**API** `GET/POST/PUT/DELETE /admin/roles`, `PUT /admin/roles/{id}/permissions`, `GET /admin/roles/compare?a=&b=` `[WEB]`
**✅ Done when** permission table page saves; unticking View clears the row.

### STEP 3.2 — Enforce permissions
**Do** `@RequirePermission(module, action)` annotation + aspect on every use case. Permissions cached in Redis by `role:perm_ver`. Role change bumps `perm_ver`.
**✅ Done when** a Viewer gets 403 on any POST/PUT/DELETE.

### STEP 3.3 — Record scope
**Do** Scope types: all, selected departments, own department, own team. `ScopeFilter` added to every person-related query (Specification / Hibernate filter).
**✅ Done when** "Primary school only" user sees only primary people in every list and export.

### STEP 3.4 — Sensitive fields
**Do** Flags: salary & bank, national ID & documents, phone numbers, ID scans. `FieldMask` in DTO mappers → `"••••"`; exporters skip the columns.
**✅ Done when** HR without salary flag sees `••••` in profile and Excel has no salary column.

### STEP 3.5 — Approval limits & login rules
**Do** Per role: leave ≤ N days, corrections older than 7 days, own requests, escalate-to role. Login rules: 2FA required, session timeout, allowed hours/days, allowed IP ranges (checked in auth server + BFF).
**✅ Done when** a request above the limit moves to the escalation role's inbox.

### STEP 3.6 — Web users
**Do** `web_user` (name, username/email, role, scope, linked person, 2FA, access_expires_on + reason, status). Force logout everywhere (revoke refresh tokens). Role history snapshot + restore version. "View as role" = backend returns permissions of that role with `readOnly=true`.
**API** `GET/POST/PUT/DELETE /admin/web-users`, `POST /admin/web-users/{id}/reset-password`, `POST /admin/roles/{id}/force-logout`, `GET /admin/roles/{id}/history`, `POST /admin/roles/{id}/history/{v}/restore`, `GET /admin/roles/{id}/users`, `POST /admin/web-users/move-role` `[WEB]`
**✅ Done when** an expired web user cannot log in the day after `access_expires_on`.

---

## PHASE 4 — People (4–6 days)

### STEP 4.1 — Person record
**Do** Fields: names km/en/ar, auto Staff ID, gender, date of birth, nationality, national ID no., marital, blood type, phones, email, address (province/district/commune/village/street), emergency contact, role (staff/teacher/admin), department, position, employment type, start/contract end/probation, supervisor, campus, default gate, subjects/classes (teacher). Soft delete with reason + last working day.
**API** `GET /admin/people?q=&dept=&role=&status=&page=&sort=` (search in km/en/ar), `GET /admin/people/{id}`, `POST`, `PUT`, `POST /admin/people/{id}/inactivate`, `POST /admin/people/{id}/restore`, `DELETE /admin/people/{id}` (forever, Super Admin, only if no history) `[WEB]` · `GET /app/me/profile` `[APP]`
**✅ Done when** Add person (4 steps) and Edit person save; inactive person cannot log in.

### STEP 4.2 — App account for a person
**Do** Creating a person creates an `account` in auth-server (username auto from name, first password generated, must change) and optionally sends SMS/email.
**API** `POST /admin/people/{id}/send-login`, `POST /admin/people/{id}/reset-password`
**✅ Done when** new staff logs in on the app with the generated password and must change it.

### STEP 4.3 — Files & documents
**Do** `files` module: presigned upload URL → client uploads to MinIO → confirm. Types: photo, national ID front/back, contract, degree (many), CV, health cert, police clearance, other. Issue/expiry dates; image resize + thumbnail; max 5 MB; JPG/PNG/PDF.
**API** `POST /files/upload-url`, `POST /files/{id}/confirm`, `GET /files/{id}/url`, `GET/POST/DELETE /admin/people/{id}/documents` `[WEB]` · `POST /app/me/photo` `[APP]`
**✅ Done when** documents show on profile; expired ones flagged.

### STEP 4.4 — Pay info
**Do** `pay_rate` history rows: type monthly | hourly | per-teaching-hour | none, amount, currency, payment day, allowances, overtime, per-subject rates, effective_from, bank, tax/NSSF. Never update past rows.
**API** `GET/POST /admin/people/{id}/pay` (needs salary flag)
**✅ Done when** pay history shows old → new with effective dates.

### STEP 4.5 — Excel import
**Do** Download template → upload → validate each row (show errors) → import. Empty ID = auto.
**API** `GET /admin/people/import/template`, `POST /admin/people/import/preview`, `POST /admin/people/import/commit`
**✅ Done when** a file with 2 bad rows imports the good ones and lists the errors.

---

## PHASE 5 — Schedules & holidays (3–4 days)

### STEP 5.1 — Shift templates
**Do** `shift_template` (name, color, grace, early needs reason) + 1 or 2 `shift_template_part` (optional name "Morning", start, end). Validate no overlap.
**API** `GET/POST/PUT/DELETE /admin/shift-templates` `[WEB]`

### STEP 5.2 — Weekly schedules
**Do** Assign per everyone / department / person; Mon–Sun → template or Off; effective_from. Most specific wins (person > department > everyone).
**API** `GET/PUT /admin/schedules/weekly?target=`, `GET /admin/schedules/weekly/preview` (grouped text "Monday – Friday · …") `[WEB]`

### STEP 5.3 — Date changes & holidays
**Do** `schedule_date_change` (dates, target, template or off, label "Exam day"); `holiday` (national/school, scope). Conflict check with leave/holiday.
**API** `GET/POST/DELETE /admin/schedules/date-changes`, `GET/POST/PUT/DELETE /admin/holidays` `[WEB]`

### STEP 5.4 — "Today's schedule" resolver (core!)
**Do** One service `ScheduleResolver.forPerson(personId, date)` → holiday? leave? date change? weekly? → list of shifts (0, 1 or 2) with names/times/grace. Used by scan, Today card, reports.
**API** `GET /app/me/schedule/today` `[APP]` (Today card), `GET /app/me/schedule/week` `[APP]` (My schedule + upcoming changes)
**✅ Done when** unit tests cover: normal day, 2 shifts, Saturday 1 shift, Sunday off, exam day change, holiday, on leave.

### STEP 5.5 — Schedule change notification
**Do** On any change affecting a person → event → notification "Your schedule for Fri 17 Oct changed…".
**✅ Done when** the app receives the push.

---

## PHASE 6 — Locations, QR & devices (3–4 days)

### STEP 6.1 — School areas & gates
**Do** `school_area` (center lat/lng, radius m, several allowed), `gate` (name, area).
**API** `GET/POST/PUT/DELETE /admin/areas`, `/admin/gates` `[WEB]`

### STEP 6.2 — QR versions
**Do** QR content = `cmt://gate/{gateId}?v={version}&sig={hmac}`. New QR → old one `expires_at = now + 5 min`. Store secret hash only. Generate PNG/PDF print layout (logo, school, gate, "Scan to check in" km/en).
**API** `POST /admin/gates/{id}/qr` (create new), `GET /admin/gates/{id}/qr/print.pdf`, `GET /admin/qr/history`, `POST /admin/qr/{id}/expire` `[WEB]`
**✅ Done when** old QR is accepted for 5 min then returns `QR_EXPIRED`.

### STEP 6.3 — Device binding
**Do** App sends at first login: device public key (generated in Keystore/Secure Enclave), name, platform, OS, app version. Auth server binds person ↔ device. Other phone → `DEVICE_MISMATCH`. Blocked device → refuse. Min app version → `APP_UPDATE_REQUIRED`. Root/jailbreak + fake GPS flags (Play Integrity / App Attest).
**API** `POST /app/device/register` `[APP]`, `POST /app/device/change-request` `[APP]`, `DELETE /app/device/change-request` `[APP]`, `GET /app/me/device` `[APP]` · `GET /admin/devices?tab=&q=&platform=&status=&version=&idle7=`, `POST /admin/devices/{personId}/reset`, `POST /admin/devices/{personId}/block`, `POST /admin/devices/{personId}/unblock`, `GET /admin/devices/history`, `GET/PUT /admin/devices/settings` `[WEB]`
**✅ Done when** login on a 2nd phone shows "linked to another phone"; admin reset lets the new phone in.

---

## PHASE 7 — Attendance (5–7 days) — the heart of the system

### STEP 7.1 — Tables
**Do** `scan_event` (append-only, idempotency_key unique), `attendance_record` (person, date, shift_idx, in/out times and gates, late_min, early_min, status, source, edited_by/reason), unique (person, date, shift_idx).

### STEP 7.2 — Scan endpoint
**Do** `POST /app/attendance/scan` body: `{qrPayload, lat, lng, accuracy, clientTime, idempotencyKey, signature}`. Steps (in one transaction):
1. verify JWT + device signature + device status + app version
2. QR valid / in 5-min grace → else `QR_INVALID` / `QR_EXPIRED`
3. geofence (distance ≤ radius + accuracy tolerance) → else `OUTSIDE_AREA` with `distanceKm`
4. `ScheduleResolver` → which shift, check-in or check-out → `NO_SHIFT_TODAY`, `ALREADY_CHECKED_IN`, `ALREADY_CHECKED_OUT`
5. late/early with grace; early check-out needs reason → `EARLY_REASON_REQUIRED` (app shows the "Leave early?" sheet, then re-sends with reason)
6. save scan_event + upsert record → outbox event
**Response** `{result: CHECKED_IN|CHECKED_OUT, time, shiftIdx, shiftName, status: ON_TIME|LATE|EARLY, lateMin, earlyMin, workedMin, nextShift}` `[APP]`
**✅ Done when** every result screen in the app design can be produced by a test.

### STEP 7.3 — Offline scans
**Do** App stores signed scans; `POST /app/attendance/scan/batch` on reconnect. Accept within 24 h, validate QR version valid at clientTime, mark `source=offline`. Same idempotency key = no duplicate.
**✅ Done when** sending the same offline scan 3 times creates 1 record.

### STEP 7.4 — Late reason / early reason
**API** `POST /app/attendance/records/{id}/late-reason` → creates approval type LATE_REASON `[APP]`

### STEP 7.5 — Staff history (app)
**API** `GET /app/attendance?range=week|month|last|custom&from=&to=&status=` → list + summary row (present, late, early, absent, leave, total hours) `[APP]`, `GET /app/attendance/{date}` → day detail (times, gate, reasons, request status) `[APP]`, `GET /app/attendance/calendar?month=` `[APP]`, `GET /app/attendance/export.pdf` `[APP]`
**✅ Done when** filters change the summary like in the design.

### STEP 7.6 — Corrections
**API** `POST /app/attendance/corrections` (date, field in/out, requested time, reason, attachment) → approval CORRECTION `[APP]`

### STEP 7.7 — Admin attendance
**API** `GET /admin/attendance/day?date=&dept=&role=&status=&q=` (one row per person, 2-shift lines, flags manual/edited/offline) `[WEB]`, `GET /admin/attendance/month?month=&dept=` (grid person × day + present/late/hours totals) `[WEB]`, `PUT /admin/attendance/records/{id}` (edit times + required reason → "Edited by admin") `[WEB]`, `GET /admin/attendance/export.xlsx` `[WEB]`
**✅ Done when** month grid numbers match day records.

### STEP 7.8 — End-of-day job
**Do** ShedLock job at day end: missing check-out → status MISSING (+ notification "You forgot to check out"), no scan + no leave/holiday → ABSENT, update `attendance_daily_summary`.
**✅ Done when** a test day without scans becomes ABSENT, not on holidays.

### STEP 7.9 — Today card endpoint
**API** `GET /app/home` → today card state (one of the 14 single-shift states or 2-shift states), month tiles (present/late/absent/leave + "6 of 22 working days" bar), leave balances, 3 recent notifications `[APP]`
**✅ Done when** the app Home needs only this one call.

---

## PHASE 8 — Teacher timetable & hours (4–5 days)

### STEP 8.1 — Timetable setup
**Do** periods, week grid per teacher/class (subject, class, room), conflict checks (teacher double-booked, room double-booked), effective date, publish (teachers notified), copy week.
**API** `GET /admin/timetable?teacher=|class=`, `PUT /admin/timetable/slots`, `POST /admin/timetable/check-conflicts`, `POST /admin/timetable/publish`, `POST /admin/timetable/copy-week` `[WEB]` · `GET /app/teacher/timetable?view=day|week&date=` `[APP]`

### STEP 8.2 — Teaching hours generation
**Do** Nightly job creates `teaching_hour` rows for tomorrow from the timetable (minus holidays, leave → status LEAVE + free slot).

### STEP 8.3 — Confirm hour (teacher app)
**API** `GET /app/teacher/today` (Today card + hours list with states Upcoming / Open / Confirmed / Missed / Extra) `[APP]`, `POST /app/teacher/hours/{id}/confirm` (lat/lng, signed, open from hour start to end of day, inside area) `[APP]`
Errors: `HOUR_NOT_OPEN`, `HOUR_CLOSED`, `OUTSIDE_AREA`.

### STEP 8.4 — Late confirmation & extra hours
**API** `POST /app/teacher/hours/{id}/late-confirm` → approval LATE_CONFIRM `[APP]`, `GET /app/teacher/free-slots?date=` `[APP]`, `POST /app/teacher/extra-hours` (slot or other time, subject (own only), class, room, note) → approval EXTRA_HOUR; first request locks the slot `[APP]`, `POST /app/teacher/hours/{id}/report` (report a problem) → TEACHER_REPORT `[APP]`

### STEP 8.5 — Admin teacher hours
**API** `GET /admin/teacher-hours/day`, `GET /admin/teacher-hours/month`, `GET /admin/teacher-hours/free-slots`, `POST /admin/teacher-hours/free-slots/{id}/assign`, `PUT /admin/teacher-hours/{id}` (mark taught/missed, reason required) `[WEB]` + admin app "Teachers today" card.
**✅ Done when** two teachers asking for the same slot → second gets `SLOT_TAKEN`.

---

## PHASE 9 — Leave (3–4 days)

### STEP 9.1 — Leave types & balances
**API** `GET/POST/PUT/DELETE /admin/leave-types`, `GET /admin/leave-balances`, `POST /admin/leave-balances/{personId}/adjust` (days ± + reason) `[WEB]` · `GET /app/leave/balances` `[APP]`

### STEP 9.2 — Leave requests
**Do** full day range / half day AM-PM / specific teaching hours; compute days and "hours you will miss" for teachers; short-notice flag; attachment; suggested substitute (free teachers of that subject).
**API** `GET /app/leave/requests`, `POST /app/leave/requests`, `GET /app/leave/requests/{id}` (status timeline), `DELETE /app/leave/requests/{id}` (cancel while pending), `GET /app/leave/preview?…` (days + affected hours before submit) `[APP]`
**✅ Done when** approving updates balance and turns affected teaching hours into LEAVE + free slots.

---

## PHASE 10 — Approvals inbox (3–4 days)

### STEP 10.1 — One inbox
**Do** `approval` table + `ApprovalHandler` per type: LEAVE, CORRECTION, LATE_REASON, EARLY_REASON, EXTRA_HOUR, LATE_CONFIRM, TEACHER_REPORT, DEVICE_CHANGE. Approve → handler applies effect; Reject → comment required; Undo within 10 s (compensating action). Approval limits + escalation from Phase 3.
**API** `GET /admin/approvals?status=pending|done&type=&group=leave|correction|lateEarly|teacherHours|device` (counts per chip), `GET /admin/approvals/{id}` (detail incl. history "Late 3 times", balance after, affected hours), `POST /admin/approvals/{id}/approve` (optional substitutes per hour), `POST /admin/approvals/{id}/reject` `{comment}`, `POST /admin/approvals/bulk-approve` (Late/Early "Approve all"), `POST /admin/approvals/{id}/undo` `[WEB]` `[APP admin]`
**✅ Done when** badge counts on web sidebar, admin app nav and "Needs your action" all match.

---

## PHASE 11 — Notifications & announcements (3 days)

### STEP 11.1 — Notification service
**Do** Outbox consumer → in-app `notification` row + push (FCM) + optional SMS/email. Templates with codes + params; **text in km/en/ar chosen by user locale**.
**API** `POST /app/push-token` `[APP]`, `GET /app/notifications`, `POST /app/notifications/{id}/read`, `POST /app/notifications/read-all` `[APP]`, `GET /admin/notifications` (bell) `[WEB]`

### STEP 11.2 — Reminder jobs
**Do** Every minute: check-in reminder, check-out reminder, Shift 2 in 10 min, teacher "hour starts in 10 min — remember to confirm", end-of-day "you forgot to confirm", document expiring in 30 days (to admins).

### STEP 11.3 — Announcements
**API** `GET/POST /admin/announcements` (title, rich text, attachment, audience everyone/dept/role/people, now or scheduled), `GET /admin/announcements/{id}/readers` (read / not read), `POST /admin/announcements/{id}/remind` `[WEB]` · announcements appear in `/app/notifications` `[APP]`

---

## PHASE 12 — Reports & payroll (4–5 days)

### STEP 12.1 — Daily summary table
**Do** `attendance_daily_summary(person, date, required_min, worked_min, late_min, early_min, absent, leave, extra_min)` filled by events + nightly job. All reports read from it.

### STEP 12.2 — Staff work time report
**API** `GET /admin/reports/work-time?period=m0|m1|m3|m6|y|custom&from=&to=&dept=&role=&q=&sort=shortfall,desc` → 6 summary cards (+ vs last month) + rows `[WEB]`, `GET /admin/reports/work-time/{personId}` (person modal: monthly bars, absence bars, daily table, absence summary: longest streak, most absent weekday, with/without leave, 12-month chart) `[WEB]`, `GET /admin/reports/absence?minDays=` (needs attention ≥ 5 unexcused) `[WEB]` · `GET /app/me/report?period=` (My report) `[APP]`
**✅ Done when** rules match Architecture §7.4 in unit tests.

### STEP 12.3 — Other reports
**API** `GET /admin/reports/{type}` for attendance summary, late & early, absence, teacher hours, leave, corrections, edited records; `…/export.xlsx` and `…/export.pdf`; `POST /admin/reports/schedules` (email monthly) `[WEB]`

### STEP 12.4 — Payroll export
**Do** One row per person: pay type, rate (valid on each date), required, worked, shortfall, extra, absent days, late minutes, leave days, estimated pay. Respect salary flag.
**API** `GET /admin/payroll/export?month=` `[WEB]`

### STEP 12.5 — Dashboard
**API** `GET /admin/dashboard` → school today card, teachers today, needs action counts, month attendance bars, late today, teaching hours per teacher, free slots, recent activity, upcoming holidays `[WEB]` · `GET /app/admin/home` (admin app School today + Needs your action) `[APP]`

---

## PHASE 13 — Staff cards (2–3 days)

### STEP 13.1 — Card template
**API** `GET/PUT /admin/cards/template` (orientation, accent, logo, background, fields, validity, QR/barcode/signature) `[WEB]`

### STEP 13.2 — Card PDF
**Do** CR80 85.6×54 mm, 300 DPI, 2 mm bleed, front + back; A4 sheet 9/10 per page with cut marks, duplex option. Card QR = staff ID only (not attendance). Skip / placeholder people without photo. Record "Card printed on … by …".
**API** `POST /admin/cards/print.pdf` `{personIds, layout, duplex, noPhoto: skip|placeholder}`, `GET /admin/cards/people?filter=` `[WEB]` · `GET /app/me/card` (data for "My card") `[APP]`
**✅ Done when** printed card at 100% measures exactly 85.6×54 mm.

---

## PHASE 14 — Audit, soft delete, history (1–2 days, but check every module)

### STEP 14.1 — Audit log everywhere
**Do** `@Audited` on all write use cases: approved, rejected, edited record, changed permission, created QR, device linked/reset/blocked, device change requested/approved/rejected, login, inactivate, restore, delete forever, card printed. Store before → after JSON, IP, device.
**API** `GET /admin/activity-log?user=&action=&from=&to=&q=`, `…/export` (read-only for everyone) `[WEB]`

### STEP 14.2 — Inactive tabs
**Do** Every list endpoint supports `?status=active|inactive` and returns `deletedBy`, `deletedAt`, `reason` for inactive.
**✅ Done when** every web page's Active / Inactive tab works and Restore writes to the log.

---

## PHASE 15 — Internationalisation (1 day)

**Do** API returns **codes + numbers**, never display sentences (e.g. `{code:"OUTSIDE_AREA", distanceKm:1.2}`). Data with names stores km/en/ar. Notifications and PDFs render in the user's locale (km default, en, ar with RTL in PDFs). Dates ISO 8601, times `HH:mm`, Western digits.
**✅ Done when** switching app language to Arabic gives Arabic push text.

---

## PHASE 16 — Hardening & go-live (4–6 days)

### STEP 16.1 — Security review
- OWASP ASVS checklist, dependency check, secrets in Vault/env, TLS + HSTS, CORS only for the web domain, file type checks, SQL only via parameters.
- Pen-test the scan endpoint (replay, fake GPS, other device token).

### STEP 16.2 — Performance
- Load test: 300 people scanning in 15 minutes (07:30–07:45) → p95 < 300 ms.
- Indexes from Architecture §6, partition big tables by month.

### STEP 16.3 — Observability
- Grafana: scans/min, failed scans by code, login failures, approval queue, job durations. Alerts on errors.

### STEP 16.4 — Backups & restore
- Daily dump + WAL, MinIO versioning, monthly restore test.

### STEP 16.5 — Deploy
- Docker images, staging → prod pipeline with manual approval, Flyway on start, health checks, zero-downtime rolling deploy.

### STEP 16.6 — Pilot
- One department for 2 weeks → fix → whole school.

---

## Endpoint checklist by app screen

**Mobile — Staff:** login · device register/blocked/change · `/app/home` · scan · scan batch (offline) · late/early reason · attendance list/calendar/day/export · correction · my report · leave balances/requests/preview/cancel · notifications · profile · my schedule · my device · my card · change password · language.
**Mobile — Teacher:** + teacher today · confirm hour · late confirm · free slots · extra hour · report problem · timetable day/week.
**Mobile — Admin:** + admin home · approvals inbox/detail/approve/reject/undo/bulk · staff list/detail · teachers today · free slots assign · monthly hours · announcements send.
**Admin Web (18 pages):** dashboard · approvals · staff attendance · teacher hours · reports · staff work time · people (+ import, documents, pay) · staff cards · devices · departments & positions · shifts & schedules · teacher timetable · leave & holidays · locations & QR · announcements · web users & roles · settings · activity log.

---

## Rough timeline (1–2 backend developers)

| Phase | Days |
|---|---|
| 0 Setup | 2 |
| 1 Security | 6 |
| 2 Org & settings | 3 |
| 3 IAM | 5 |
| 4 People | 6 |
| 5 Schedules | 4 |
| 6 QR & devices | 4 |
| 7 Attendance | 7 |
| 8 Teacher | 5 |
| 9 Leave | 4 |
| 10 Approvals | 4 |
| 11 Notifications | 3 |
| 12 Reports & payroll | 5 |
| 13 Cards | 3 |
| 14 Audit | 2 |
| 15 i18n | 1 |
| 16 Hardening | 6 |
| **Total** | **≈ 70 working days (≈ 3½ months)** |

Start the Flutter app and React web **after Phase 1**, in parallel, using the OpenAPI spec as it grows.
