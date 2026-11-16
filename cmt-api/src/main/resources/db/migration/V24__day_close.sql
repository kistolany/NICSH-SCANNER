-- V24 (step 7.8): the end-of-day job — which school days are closed, the daily summary (step 12.1's table, filled
-- from now on), and ShedLock's table (one runner of each job across instances).

-- A day the end-of-day job closed for a school: from then on, missing check-outs and absences are stored records.
CREATE TABLE attendance_day_close (
    school_id    uuid         NOT NULL REFERENCES school (id),
    work_date    date         NOT NULL,
    closed_at    timestamptz  NOT NULL,
    people       int          NOT NULL,   -- active people looked at
    absent       int          NOT NULL,   -- ABSENT records made
    missing      int          NOT NULL,   -- check-outs marked missing (or closed automatically)
    PRIMARY KEY (school_id, work_date)
);

-- One row per person and day they had something (a working day, a holiday, leave, or a record): what reports read.
CREATE TABLE attendance_daily_summary (
    school_id     uuid         NOT NULL REFERENCES school (id),
    person_id     uuid         NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    work_date     date         NOT NULL,
    status        varchar(12)  NOT NULL CHECK (status IN ('ON_TIME', 'LATE', 'EARLY', 'MISSING', 'ABSENT', 'LEAVE', 'HOLIDAY')),
    required_min  int          NOT NULL DEFAULT 0,   -- planned minutes of the shifts not on leave
    worked_min    int          NOT NULL DEFAULT 0,
    late_min      int          NOT NULL DEFAULT 0,
    early_min     int          NOT NULL DEFAULT 0,
    extra_min     int          NOT NULL DEFAULT 0,   -- teachers' extra hours (phase 8)
    absent        boolean      NOT NULL DEFAULT false,
    leave         boolean      NOT NULL DEFAULT false,
    closed        boolean      NOT NULL DEFAULT false,   -- the end-of-day job has run for the day
    updated_at    timestamptz  NOT NULL,
    PRIMARY KEY (person_id, work_date)
);

CREATE INDEX attendance_daily_summary_school_day_idx ON attendance_daily_summary (school_id, work_date);

CREATE TABLE shedlock (
    name        varchar(64)   PRIMARY KEY,
    lock_until  timestamptz   NOT NULL,
    locked_at   timestamptz   NOT NULL,
    locked_by   varchar(255)  NOT NULL
);
