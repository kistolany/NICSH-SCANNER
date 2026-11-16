-- V5: roles and the permission matrix (Web users & roles > Roles & permissions).
--
-- A role has a stable code (super_admin, hr, ...) which is what the access token carries in its "role" claim.
-- System roles are created for every school on first use and are locked (read-only). Custom roles can be
-- edited and deleted (soft delete). perm_version goes up every time a role's permissions change: the API keeps
-- permissions in a cache keyed by role + perm_version, so a change takes effect on the next request.

CREATE TABLE role (
    id               uuid PRIMARY KEY,
    school_id        uuid         NOT NULL REFERENCES school (id),
    code             varchar(50)  NOT NULL,
    name_km          varchar(100),
    name_en          varchar(100) NOT NULL,
    name_ar          varchar(100),
    description_km   varchar(200),
    description_en   varchar(200),
    description_ar   varchar(200),
    system           boolean      NOT NULL DEFAULT false,
    perm_version     int          NOT NULL DEFAULT 1,
    inactive         boolean      NOT NULL DEFAULT false,
    deleted_at       timestamptz,
    deleted_by       uuid,
    delete_reason    varchar(500),
    created_at       timestamptz  NOT NULL,
    created_by       uuid,
    updated_at       timestamptz  NOT NULL,
    updated_by       uuid,
    version          bigint       NOT NULL DEFAULT 0
);

-- A code is never used twice, not even after the role was deleted (old records and tokens refer to it).
CREATE UNIQUE INDEX role_code_idx ON role (school_id, code);
CREATE UNIQUE INDEX role_name_active_idx ON role (school_id, lower(name_en)) WHERE NOT inactive;

-- One row per permission a role has. Modules and actions are fixed lists (see PermissionModule / PermissionAction);
-- a module / action pair that does not apply (for example "create" on the dashboard) is refused by the service.
CREATE TABLE role_permission (
    school_id  uuid        NOT NULL REFERENCES school (id),
    role_id    uuid        NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    module     varchar(30) NOT NULL CHECK (module IN ('dashboard', 'approvals', 'attendance', 'teacherHours', 'people',
        'shifts', 'timetable', 'leave', 'qr', 'announcements', 'reports', 'users', 'settings', 'log')),
    action     varchar(10) NOT NULL CHECK (action IN ('view', 'create', 'edit', 'delete', 'approve', 'export')),
    PRIMARY KEY (role_id, module, action)
);
