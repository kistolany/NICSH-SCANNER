-- V4: automatic Staff IDs (Settings > Numbering).
--
-- Format: PREFIX [ROLE] [YY] NUMBER, joined by a separator, e.g. CMT-0151, CMT-TCH-26-0151.
-- The number comes from staff_id_sequence and only ever goes up.

ALTER TABLE school_settings
    ADD COLUMN numbering_prefix       varchar(10) NOT NULL DEFAULT 'CMT'
        CHECK (numbering_prefix ~ '^[A-Z0-9]{1,10}$'),
    ADD COLUMN numbering_separator    varchar(3)  NOT NULL DEFAULT '-'
        CHECK (numbering_separator ~ '^[-_./]{0,3}$'),
    ADD COLUMN numbering_length       int         NOT NULL DEFAULT 4
        CHECK (numbering_length BETWEEN 3 AND 8),
    ADD COLUMN numbering_role_prefix  boolean     NOT NULL DEFAULT false,   -- STF / TCH / ADM after the prefix
    ADD COLUMN numbering_year         boolean     NOT NULL DEFAULT false,   -- two-digit year, and the number restarts each year
    ADD COLUMN numbering_rev          bigint      NOT NULL DEFAULT 0;

-- One counter per school, prefix and year (year is 0 when the ID has no year part). The counter is not tied to the
-- separator, length or role prefix, so changing those never makes a number come back. A row is locked while a Staff ID
-- is being handed out, so two people created at the same moment can never receive the same number.
CREATE TABLE staff_id_sequence (
    school_id   uuid        NOT NULL REFERENCES school (id),
    prefix      varchar(10) NOT NULL,
    year        int         NOT NULL DEFAULT 0,
    last_value  bigint      NOT NULL DEFAULT 0 CHECK (last_value >= 0),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (school_id, prefix, year)
);
