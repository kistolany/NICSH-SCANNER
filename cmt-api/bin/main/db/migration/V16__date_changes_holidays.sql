-- V16: date changes and holidays (Shifts & schedules > Date changes; Leave & holidays > Holiday calendar).

-- A date change: on one date, everyone / a department / a person works another template, or is off ("Exam day").
-- One per date and target: saving the same date and target again replaces it. On a holiday it does not apply.
CREATE TABLE schedule_date_change (
    id             uuid PRIMARY KEY,
    school_id      uuid         NOT NULL REFERENCES school (id),
    change_date    date         NOT NULL,
    target_type    varchar(12)  NOT NULL CHECK (target_type IN ('EVERYONE', 'DEPARTMENT', 'PERSON')),
    department_id  uuid REFERENCES department (id),
    person_id      uuid REFERENCES person (id),
    template_id    uuid REFERENCES shift_template (id),   -- null = off
    label          varchar(40),
    created_at     timestamptz  NOT NULL,
    created_by     uuid,
    updated_at     timestamptz  NOT NULL,
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0,
    CHECK ((target_type = 'EVERYONE' AND department_id IS NULL AND person_id IS NULL)
        OR (target_type = 'DEPARTMENT' AND department_id IS NOT NULL AND person_id IS NULL)
        OR (target_type = 'PERSON' AND person_id IS NOT NULL AND department_id IS NULL))
);

CREATE UNIQUE INDEX schedule_date_change_target_idx ON schedule_date_change
    (school_id, change_date, target_type, coalesce(department_id, person_id, '00000000-0000-0000-0000-000000000000'));
CREATE INDEX schedule_date_change_date_idx ON schedule_date_change (school_id, change_date);

-- A holiday: from start_date to end_date (one day: the same date), a national holiday or a school break, for
-- everyone or one department. No absence is counted on it and the app shows "Holiday".
CREATE TABLE holiday (
    id             uuid PRIMARY KEY,
    school_id      uuid         NOT NULL REFERENCES school (id),
    name_km        varchar(100),
    name_en        varchar(100),
    name_ar        varchar(100),
    kind           varchar(10)  NOT NULL CHECK (kind IN ('NATIONAL', 'SCHOOL')),
    start_date     date         NOT NULL,
    end_date       date         NOT NULL,
    department_id  uuid REFERENCES department (id),        -- null = everyone
    created_at     timestamptz  NOT NULL,
    created_by     uuid,
    updated_at     timestamptz  NOT NULL,
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0,
    CHECK (end_date >= start_date),
    CHECK (coalesce(name_km, name_en) IS NOT NULL)
);

CREATE INDEX holiday_dates_idx ON holiday (school_id, start_date, end_date);
