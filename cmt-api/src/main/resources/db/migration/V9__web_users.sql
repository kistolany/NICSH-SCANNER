-- V9: web users (Web users & roles > Web users), role history and the default role for new web users.

-- Default role for new web users (Roles page). At most one per school; Viewer to begin with.
ALTER TABLE role ADD COLUMN is_default boolean NOT NULL DEFAULT false;
UPDATE role SET is_default = true WHERE code = 'viewer' AND system;
CREATE UNIQUE INDEX role_one_default_idx ON role (school_id) WHERE is_default AND NOT inactive;

-- An office user of the Admin Web. Separate from app users (people). The login itself is the auth server's
-- auth.account row (account_id), which the iam module creates and keeps in line with this row; there is no foreign
-- key across the schemas because the auth schema belongs to the auth server.
-- Deactivated = inactive (soft delete, with who / when / why); restore makes the user active again.
-- What the user may see is decided by the role (permissions, record scope, sensitive fields): there is no per-user scope.
CREATE TABLE web_user (
    id                     uuid PRIMARY KEY,
    school_id              uuid         NOT NULL REFERENCES school (id),
    account_id             uuid         NOT NULL,
    name                   varchar(150) NOT NULL,
    username               varchar(100) NOT NULL,
    phone                  varchar(30),
    role_code              varchar(50)  NOT NULL,
    linked_person_id       uuid,          -- the person record (phase 4 adds the foreign key)
    two_factor_required    boolean      NOT NULL DEFAULT false,
    access_expires_on      date,          -- last day the user may sign in; null = no end
    access_expires_reason  varchar(200),
    inactive               boolean      NOT NULL DEFAULT false,
    deleted_at             timestamptz,
    deleted_by             uuid,
    delete_reason          varchar(500),
    created_at             timestamptz  NOT NULL,
    created_by             uuid,
    updated_at             timestamptz  NOT NULL,
    updated_by             uuid,
    version                bigint       NOT NULL DEFAULT 0,
    FOREIGN KEY (school_id, role_code) REFERENCES role (school_id, code)
);

CREATE UNIQUE INDEX web_user_username_idx ON web_user (lower(username));
CREATE UNIQUE INDEX web_user_account_idx ON web_user (account_id);
CREATE UNIQUE INDEX web_user_person_idx ON web_user (school_id, linked_person_id) WHERE linked_person_id IS NOT NULL AND NOT inactive;
CREATE INDEX web_user_role_idx ON web_user (school_id, role_code) WHERE NOT inactive;

-- Every saved version of a role (Roles page > Change history), so an older one can be restored.
-- snapshot: name, description, permissions, scope, sensitive fields, approval limits, login rules.
CREATE TABLE role_version (
    id             uuid PRIMARY KEY,
    school_id      uuid        NOT NULL REFERENCES school (id),
    role_id        uuid        NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    version_no     int         NOT NULL,
    change         varchar(30) NOT NULL,
    restored_from  int,
    snapshot       jsonb       NOT NULL,
    changed_by     uuid,
    changed_at     timestamptz NOT NULL,
    UNIQUE (role_id, version_no)
);
