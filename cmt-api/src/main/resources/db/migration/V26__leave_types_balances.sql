-- V26: leave types and balance adjustments (step 9.1). A balance is not stored: for a person, a type and a calendar
-- year it is the type's days (pro-rata by months in the year the person started), plus the adjustments, minus the
-- approved leave of that year (requests come with step 9.2). Unused days do not carry over; an admin adds them with an
-- adjustment.
CREATE TABLE leave_type (
    id              uuid PRIMARY KEY,
    school_id       uuid          NOT NULL REFERENCES school (id),
    code            varchar(30)   NOT NULL,                 -- stable key the apps label by (annual, sick, personal…)
    name_km         varchar(100),
    name_en         varchar(100)  NOT NULL,
    name_ar         varchar(100),
    days_per_year   numeric(5, 1) NOT NULL CHECK (days_per_year >= 0 AND days_per_year <= 366),
    half_days       boolean       NOT NULL DEFAULT true,    -- may be taken by half days
    attachment      varchar(12)   NOT NULL DEFAULT 'NONE' CHECK (attachment IN ('NONE', 'OVER_ONE_DAY', 'ALWAYS')),
    color           varchar(7)    NOT NULL,
    position        int           NOT NULL,                 -- the order of the lists and the balance columns
    inactive        boolean       NOT NULL DEFAULT false,
    deleted_at      timestamptz,
    deleted_by      uuid,
    delete_reason   varchar(500),
    created_at      timestamptz   NOT NULL,
    created_by      uuid,
    updated_at      timestamptz   NOT NULL,
    updated_by      uuid,
    version         bigint        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX leave_type_code_idx ON leave_type (school_id, code);
CREATE UNIQUE INDEX leave_type_name_active_idx ON leave_type (school_id, lower(name_en)) WHERE NOT inactive;

-- Days added (+) or taken away (−) by an admin, with the reason: kept as they were sent, never changed.
CREATE TABLE leave_adjustment (
    id              uuid PRIMARY KEY,
    school_id       uuid          NOT NULL REFERENCES school (id),
    person_id       uuid          NOT NULL REFERENCES person (id),
    leave_type_id   uuid          NOT NULL REFERENCES leave_type (id),
    year            int           NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    days            numeric(5, 1) NOT NULL CHECK (days <> 0),
    reason          varchar(300)  NOT NULL,
    created_at      timestamptz   NOT NULL,
    created_by      uuid,
    updated_at      timestamptz   NOT NULL,
    updated_by      uuid,
    version         bigint        NOT NULL DEFAULT 0
);
CREATE INDEX leave_adjustment_person_idx ON leave_adjustment (person_id, year);

-- The design's types for the CMT school (its "Training", counted in teaching hours, is left out: teacher hours are not built).
INSERT INTO leave_type (id, school_id, code, name_km, name_en, name_ar, days_per_year, half_days, attachment, color, position, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0009-000000000001', '00000000-0000-0000-0000-000000000001', 'annual', 'ច្បាប់ប្រចាំឆ្នាំ', 'Annual leave', 'إجازة سنوية',
     12, true, 'NONE', '#1D4ED8', 1, now(), now()),
    ('00000000-0000-0000-0009-000000000002', '00000000-0000-0000-0000-000000000001', 'sick', 'ច្បាប់ឈឺ', 'Sick leave', 'إجازة مرضية',
     10, true, 'OVER_ONE_DAY', '#B91C1C', 2, now(), now()),
    ('00000000-0000-0000-0009-000000000003', '00000000-0000-0000-0000-000000000001', 'personal', 'ច្បាប់ផ្ទាល់ខ្លួន', 'Personal leave', 'إجازة شخصية',
     3, true, 'NONE', '#B45309', 3, now(), now()),
    ('00000000-0000-0000-0009-000000000004', '00000000-0000-0000-0000-000000000001', 'maternity', 'ច្បាប់សម្រាលកូន', 'Maternity leave', 'إجازة أمومة',
     90, false, 'ALWAYS', '#15803D', 4, now(), now());
