-- V13: shift templates (Shifts & schedules > Shift templates). A template is one working day: 1 or 2 shifts
-- ("Morning" 07:45-11:30, "Afternoon" 13:30-17:00), the late grace and whether leaving early needs a reason.
-- Weekly schedules (step 5.2) and date changes (5.3) point at templates.
CREATE TABLE shift_template (
    id                  uuid PRIMARY KEY,
    school_id           uuid         NOT NULL REFERENCES school (id),
    name                varchar(60)  NOT NULL,
    color               varchar(7)   NOT NULL,
    grace_minutes       int          NOT NULL CHECK (grace_minutes BETWEEN 0 AND 120),
    early_needs_reason  boolean      NOT NULL,
    inactive            boolean      NOT NULL DEFAULT false,
    deleted_at          timestamptz,
    deleted_by          uuid,
    delete_reason       varchar(500),
    created_at          timestamptz  NOT NULL,
    created_by          uuid,
    updated_at          timestamptz  NOT NULL,
    updated_by          uuid,
    version             bigint       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX shift_template_name_idx ON shift_template (school_id, lower(name)) WHERE NOT inactive;

-- The shifts of a template, in order (1, then 2). Times are school time; a shift ends on the day it starts.
CREATE TABLE shift_template_part (
    template_id  uuid        NOT NULL REFERENCES shift_template (id) ON DELETE CASCADE,
    position     int         NOT NULL CHECK (position IN (1, 2)),
    name         varchar(30),
    start_time   time        NOT NULL,
    end_time     time        NOT NULL,
    PRIMARY KEY (template_id, position),
    CHECK (end_time > start_time)
);
