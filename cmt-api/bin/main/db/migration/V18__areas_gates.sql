-- V18 (step 6.1): school areas and gates (Locations & QR). A check-in counts only inside an area: a circle around a
-- centre point (latitude / longitude, WGS 84) with a radius in metres. A school may have several (main campus, sports
-- field…), and they may overlap. Each gate belongs to an area; its QR codes come in step 6.2.
CREATE TABLE school_area (
    id             uuid PRIMARY KEY,
    school_id      uuid           NOT NULL REFERENCES school (id),
    name           varchar(60)    NOT NULL,
    latitude       numeric(9, 6)  NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude      numeric(9, 6)  NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    radius_m       int            NOT NULL CHECK (radius_m BETWEEN 20 AND 1000),
    inactive       boolean        NOT NULL DEFAULT false,
    deleted_at     timestamptz,
    deleted_by     uuid,
    delete_reason  varchar(500),
    created_at     timestamptz    NOT NULL,
    created_by     uuid,
    updated_at     timestamptz    NOT NULL,
    updated_by     uuid,
    version        bigint         NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX school_area_name_idx ON school_area (school_id, lower(name)) WHERE NOT inactive;

CREATE TABLE gate (
    id             uuid PRIMARY KEY,
    school_id      uuid           NOT NULL REFERENCES school (id),
    name           varchar(60)    NOT NULL,
    area_id        uuid           NOT NULL REFERENCES school_area (id),
    inactive       boolean        NOT NULL DEFAULT false,
    deleted_at     timestamptz,
    deleted_by     uuid,
    delete_reason  varchar(500),
    created_at     timestamptz    NOT NULL,
    created_by     uuid,
    updated_at     timestamptz    NOT NULL,
    updated_by     uuid,
    version        bigint         NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX gate_name_idx ON gate (school_id, lower(name)) WHERE NOT inactive;
CREATE INDEX gate_area_idx ON gate (area_id);
