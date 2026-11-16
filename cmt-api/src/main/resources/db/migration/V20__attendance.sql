-- V20 (step 7.1): attendance. scan_event = every scan as it came in (accepted or refused), never changed or removed;
-- attendance_record = one row per person, day and shift, made by the scans (7.2 / 7.3), the end-of-day job (7.8) or an
-- admin (7.7).

-- ---------------------------------------------------------------- scan_event
-- What a phone sent and what the server decided. Append-only: the database refuses UPDATE and DELETE. The same
-- idempotency key twice is the same scan (an offline scan sent again): it is stored once.
CREATE TABLE scan_event (
    id               uuid PRIMARY KEY,
    school_id        uuid          NOT NULL REFERENCES school (id),
    person_id        uuid          NOT NULL REFERENCES person (id),
    account_id       uuid          NOT NULL,
    device_id        uuid,
    idempotency_key  varchar(64)   NOT NULL UNIQUE,
    source           varchar(10)   NOT NULL CHECK (source IN ('ONLINE', 'OFFLINE')),
    client_time      timestamptz   NOT NULL,            -- when the phone says it scanned
    received_at      timestamptz   NOT NULL,            -- when the server got it
    qr_payload_hash  varchar(64),                       -- SHA-256 of what the camera read (the QR itself is a key)
    gate_id          uuid          REFERENCES gate (id),
    qr_version       int,
    latitude         numeric(9, 6),
    longitude        numeric(9, 6),
    accuracy_m       numeric(8, 1),
    distance_m       numeric(10, 1),                    -- from the nearest school area's edge (0 inside)
    area_id          uuid          REFERENCES school_area (id),
    mock_location    boolean,
    rooted           boolean,
    signature_ok     boolean,                           -- null until the phones sign scans (7.2)
    kind             varchar(10)   CHECK (kind IN ('CHECK_IN', 'CHECK_OUT')),   -- null when refused before it was known
    result           varchar(30)   NOT NULL,            -- ACCEPTED, or the refusal code (QR_EXPIRED, OUTSIDE_AREA…)
    record_id        uuid,                              -- the attendance record it made or closed
    shift_idx        int           CHECK (shift_idx IN (1, 2)),
    early_reason     varchar(300),
    created_at       timestamptz   NOT NULL,
    created_by       uuid,
    updated_at       timestamptz   NOT NULL,
    updated_by       uuid,
    version          bigint        NOT NULL DEFAULT 0
);

CREATE INDEX scan_event_person_idx ON scan_event (person_id, client_time DESC);
CREATE INDEX scan_event_school_idx ON scan_event (school_id, received_at DESC);

CREATE FUNCTION scan_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'scan_event rows are never changed or removed';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER scan_event_append_only BEFORE UPDATE OR DELETE ON scan_event FOR EACH ROW EXECUTE FUNCTION scan_event_append_only();

-- ---------------------------------------------------------------- attendance_record
-- One shift of one person on one day. The shift as it was planned that day is copied in (name, times, grace, early
-- rule), so later changes to templates or schedules never rewrite past days.
CREATE TABLE attendance_record (
    id                    uuid PRIMARY KEY,
    school_id             uuid          NOT NULL REFERENCES school (id),
    person_id             uuid          NOT NULL REFERENCES person (id),
    work_date             date          NOT NULL,
    shift_idx             int           NOT NULL CHECK (shift_idx IN (1, 2)),
    shift_name            varchar(30),
    planned_start         time          NOT NULL,
    planned_end           time          NOT NULL,
    grace_minutes         int           NOT NULL CHECK (grace_minutes BETWEEN 0 AND 120),
    early_needs_reason    boolean       NOT NULL,
    in_at                 timestamptz,
    in_gate_id            uuid          REFERENCES gate (id),
    in_scan_id            uuid          REFERENCES scan_event (id),
    out_at                timestamptz,
    out_gate_id           uuid          REFERENCES gate (id),
    out_scan_id           uuid          REFERENCES scan_event (id),
    late_minutes          int           NOT NULL DEFAULT 0 CHECK (late_minutes >= 0),
    early_minutes         int           NOT NULL DEFAULT 0 CHECK (early_minutes >= 0),
    worked_minutes        int           NOT NULL DEFAULT 0 CHECK (worked_minutes >= 0),
    -- ON_TIME / LATE / EARLY: from the scans (LATE wins over EARLY; both minutes are kept); MISSING: checked in, never
    -- out (end of day); ABSENT: no scan on a working day; LEAVE: on approved leave
    status                varchar(10)   NOT NULL CHECK (status IN ('ON_TIME', 'LATE', 'EARLY', 'MISSING', 'ABSENT', 'LEAVE')),
    source                varchar(10)   NOT NULL CHECK (source IN ('SCAN', 'OFFLINE', 'MANUAL', 'SYSTEM')),
    late_reason           varchar(300),
    early_reason          varchar(300),
    edited_by             uuid,
    edited_at             timestamptz,
    edit_reason           varchar(300),
    created_at            timestamptz   NOT NULL,
    created_by            uuid,
    updated_at            timestamptz   NOT NULL,
    updated_by            uuid,
    version               bigint        NOT NULL DEFAULT 0,
    UNIQUE (person_id, work_date, shift_idx),
    CHECK (out_at IS NULL OR in_at IS NULL OR out_at >= in_at)
);

CREATE INDEX attendance_record_school_day_idx ON attendance_record (school_id, work_date);
CREATE INDEX attendance_record_person_day_idx ON attendance_record (person_id, work_date DESC);
