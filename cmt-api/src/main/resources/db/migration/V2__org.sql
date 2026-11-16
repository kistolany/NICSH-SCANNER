-- V2: organisation lists used by dropdowns everywhere: departments, positions, subjects, classes, rooms.
-- Every row can be moved to "inactive" (kept for history, hidden from dropdowns) and restored.
-- A name only has to be unique among ACTIVE rows, so a name can be used again after the old one was retired.

-- ---------------------------------------------------------------- department
CREATE TABLE department (
    id             uuid PRIMARY KEY,
    school_id      uuid         NOT NULL REFERENCES school (id),
    name_km        varchar(100),
    name_en        varchar(100) NOT NULL,
    name_ar        varchar(100),
    color          varchar(7)   NOT NULL DEFAULT '#4B5563',
    inactive       boolean      NOT NULL DEFAULT false,
    deleted_at     timestamptz,
    deleted_by     uuid,
    delete_reason  varchar(500),
    created_at     timestamptz  NOT NULL,
    created_by     uuid,
    updated_at     timestamptz  NOT NULL,
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX department_name_active_idx ON department (school_id, lower(name_en)) WHERE NOT inactive;

-- ---------------------------------------------------------------- job_position (the word "position" is an SQL keyword)
CREATE TABLE job_position (
    id             uuid PRIMARY KEY,
    school_id      uuid         NOT NULL REFERENCES school (id),
    name_km        varchar(100),
    name_en        varchar(100) NOT NULL,
    name_ar        varchar(100),
    inactive       boolean      NOT NULL DEFAULT false,
    deleted_at     timestamptz,
    deleted_by     uuid,
    delete_reason  varchar(500),
    created_at     timestamptz  NOT NULL,
    created_by     uuid,
    updated_at     timestamptz  NOT NULL,
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX job_position_name_active_idx ON job_position (school_id, lower(name_en)) WHERE NOT inactive;

-- ---------------------------------------------------------------- subject
CREATE TABLE subject (
    id             uuid PRIMARY KEY,
    school_id      uuid         NOT NULL REFERENCES school (id),
    name_km        varchar(100),
    name_en        varchar(100) NOT NULL,
    name_ar        varchar(100),
    color          varchar(7)   NOT NULL DEFAULT '#4B5563',
    inactive       boolean      NOT NULL DEFAULT false,
    deleted_at     timestamptz,
    deleted_by     uuid,
    delete_reason  varchar(500),
    created_at     timestamptz  NOT NULL,
    created_by     uuid,
    updated_at     timestamptz  NOT NULL,
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX subject_name_active_idx ON subject (school_id, lower(name_en)) WHERE NOT inactive;

-- ---------------------------------------------------------------- class_group (a class such as 9A)
CREATE TABLE class_group (
    id             uuid PRIMARY KEY,
    school_id      uuid         NOT NULL REFERENCES school (id),
    name           varchar(30)  NOT NULL,
    inactive       boolean      NOT NULL DEFAULT false,
    deleted_at     timestamptz,
    deleted_by     uuid,
    delete_reason  varchar(500),
    created_at     timestamptz  NOT NULL,
    created_by     uuid,
    updated_at     timestamptz  NOT NULL,
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX class_group_name_active_idx ON class_group (school_id, lower(name)) WHERE NOT inactive;

-- ---------------------------------------------------------------- room
CREATE TABLE room (
    id             uuid PRIMARY KEY,
    school_id      uuid         NOT NULL REFERENCES school (id),
    name           varchar(30)  NOT NULL,
    inactive       boolean      NOT NULL DEFAULT false,
    deleted_at     timestamptz,
    deleted_by     uuid,
    delete_reason  varchar(500),
    created_at     timestamptz  NOT NULL,
    created_by     uuid,
    updated_at     timestamptz  NOT NULL,
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX room_name_active_idx ON room (school_id, lower(name)) WHERE NOT inactive;
