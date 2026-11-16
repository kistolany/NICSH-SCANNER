-- V25: teacher timetable (step 8.1). Teaching periods of the school, and the week grid in versions: each version holds
-- every teacher's week from its effective_from on. A version is a draft until it is published; the apps only read
-- published versions (the one in force on a day = the latest published effective_from <= that day). At most one draft
-- and one published version per date.
CREATE TABLE timetable_period (
    id          uuid PRIMARY KEY,
    school_id   uuid         NOT NULL REFERENCES school (id),
    code        varchar(10)  NOT NULL,
    start_time  time         NOT NULL,
    end_time    time         NOT NULL,
    created_at  timestamptz  NOT NULL,
    created_by  uuid,
    updated_at  timestamptz  NOT NULL,
    updated_by  uuid,
    version     bigint       NOT NULL DEFAULT 0,
    CHECK (end_time > start_time)
);
CREATE UNIQUE INDEX timetable_period_code_idx ON timetable_period (school_id, lower(code));

CREATE TABLE timetable_version (
    id              uuid PRIMARY KEY,
    school_id       uuid         NOT NULL REFERENCES school (id),
    effective_from  date         NOT NULL,
    published       boolean      NOT NULL DEFAULT false,
    published_at    timestamptz,
    published_by    uuid,
    created_at      timestamptz  NOT NULL,
    created_by      uuid,
    updated_at      timestamptz  NOT NULL,
    updated_by      uuid,
    version         bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX timetable_version_idx ON timetable_version (school_id, effective_from, published);

-- One teaching period of a teacher on a weekday (ISO, Monday = 1) in a version: subject, class, room (optional).
CREATE TABLE timetable_slot (
    id              uuid PRIMARY KEY,
    school_id       uuid         NOT NULL REFERENCES school (id),
    version_id      uuid         NOT NULL REFERENCES timetable_version (id) ON DELETE CASCADE,
    teacher_id      uuid         NOT NULL REFERENCES person (id),
    weekday         int          NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    period_id       uuid         NOT NULL REFERENCES timetable_period (id),
    subject_id      uuid         NOT NULL REFERENCES subject (id),
    class_id        uuid         NOT NULL REFERENCES class_group (id),
    room_id         uuid REFERENCES room (id),
    created_at      timestamptz  NOT NULL,
    created_by      uuid,
    updated_at      timestamptz  NOT NULL,
    updated_by      uuid,
    version         bigint       NOT NULL DEFAULT 0,
    UNIQUE (version_id, teacher_id, weekday, period_id)
);
CREATE INDEX timetable_slot_teacher_idx ON timetable_slot (teacher_id);
CREATE INDEX timetable_slot_period_idx ON timetable_slot (period_id);
