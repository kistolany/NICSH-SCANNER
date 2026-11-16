-- V1: base tables shared by every module: school (tenant), outbox, audit_log, file.
-- Never edit this file after it has run anywhere; add V2, V3... instead.

-- ---------------------------------------------------------------- school
CREATE TABLE school (
    id          uuid PRIMARY KEY,
    code        varchar(20)  NOT NULL UNIQUE,
    name_km     varchar(200),
    name_en     varchar(200) NOT NULL,
    name_ar     varchar(200),
    created_at  timestamptz  NOT NULL,
    created_by  uuid,
    updated_at  timestamptz  NOT NULL,
    updated_by  uuid,
    version     bigint       NOT NULL DEFAULT 0
);

-- The one school we run today. Same id as cmt.school.default-id in application.yml.
INSERT INTO school (id, code, name_km, name_en, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'CMT', 'សាលា CMT', 'CMT School', now(), now());

-- ---------------------------------------------------------------- outbox
-- Transactional outbox: written in the same transaction as the change, published afterwards.
CREATE TABLE outbox (
    id            uuid PRIMARY KEY,
    school_id     uuid         NOT NULL REFERENCES school (id),
    aggregate     varchar(100) NOT NULL,
    type          varchar(100) NOT NULL,
    payload       jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL,
    published_at  timestamptz
);

CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;

-- ---------------------------------------------------------------- audit_log
-- Append-only. Nobody edits or deletes rows (no updated_*, no soft delete).
CREATE TABLE audit_log (
    id           uuid PRIMARY KEY,
    school_id    uuid         NOT NULL REFERENCES school (id),
    at           timestamptz  NOT NULL,
    actor_type   varchar(20)  NOT NULL,
    actor_id     uuid,
    action       varchar(100) NOT NULL,
    target_type  varchar(100),
    target_id    varchar(100),
    before       jsonb,
    after        jsonb,
    ip           varchar(45),
    user_agent   varchar(500)
);

CREATE INDEX audit_log_at_idx ON audit_log (at);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, at);
CREATE INDEX audit_log_target_idx ON audit_log (target_type, target_id);

-- ---------------------------------------------------------------- file
-- Metadata only; the bytes live in object storage (MinIO / S3).
CREATE TABLE file (
    id           uuid PRIMARY KEY,
    school_id    uuid          NOT NULL REFERENCES school (id),
    bucket       varchar(100)  NOT NULL,
    key          varchar(500)  NOT NULL,
    mime         varchar(100)  NOT NULL,
    size         bigint        NOT NULL,
    sha256       varchar(64),
    uploaded_by  uuid,
    created_at   timestamptz   NOT NULL,
    created_by   uuid,
    updated_at   timestamptz   NOT NULL,
    updated_by   uuid,
    version      bigint        NOT NULL DEFAULT 0,
    UNIQUE (bucket, key)
);

CREATE INDEX file_school_idx ON file (school_id);
CREATE INDEX file_sha256_idx ON file (sha256);
