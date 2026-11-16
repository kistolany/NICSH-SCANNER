-- V14: weekly schedules (Shifts & schedules > Weekly schedules). For everyone, a department or one person: Monday to
-- Sunday each a shift template or off, from a date on. Every save is a version with its effective_from (history kept);
-- the most specific one in force wins for a person: person > department > everyone. A department or person version
-- with inherit = true means "from this date, follow the level above again" (no days of its own).
CREATE TABLE weekly_schedule (
    id              uuid PRIMARY KEY,
    school_id       uuid         NOT NULL REFERENCES school (id),
    target_type     varchar(12)  NOT NULL CHECK (target_type IN ('EVERYONE', 'DEPARTMENT', 'PERSON')),
    department_id   uuid REFERENCES department (id),
    person_id       uuid REFERENCES person (id),
    effective_from  date         NOT NULL,
    inherit         boolean      NOT NULL DEFAULT false,
    created_at      timestamptz  NOT NULL,
    created_by      uuid,
    updated_at      timestamptz  NOT NULL,
    updated_by      uuid,
    version         bigint       NOT NULL DEFAULT 0,
    CHECK ((target_type = 'EVERYONE' AND department_id IS NULL AND person_id IS NULL)
        OR (target_type = 'DEPARTMENT' AND department_id IS NOT NULL AND person_id IS NULL)
        OR (target_type = 'PERSON' AND person_id IS NOT NULL AND department_id IS NULL)),
    CHECK (NOT (inherit AND target_type = 'EVERYONE'))
);

CREATE UNIQUE INDEX weekly_schedule_version_idx ON weekly_schedule
    (school_id, target_type, coalesce(department_id, person_id, '00000000-0000-0000-0000-000000000000'), effective_from);

-- The seven days of a version (none for an "inherit" version). template_id null = off.
CREATE TABLE weekly_schedule_day (
    schedule_id  uuid NOT NULL REFERENCES weekly_schedule (id) ON DELETE CASCADE,
    weekday      int  NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    template_id  uuid REFERENCES shift_template (id),
    PRIMARY KEY (schedule_id, weekday)
);
