-- V6: record scope of a role (Roles page, "Record scope"): whose records the role's users may see.
--
--   ALL                   all school (every system role)
--   SELECTED_DEPARTMENTS  people of the departments in role_scope_department
--   OWN_DEPARTMENT        people of the user's own department (from the user's linked person)
--   OWN_TEAM              the user's linked person and the people who have that person as supervisor
--
-- A change of scope raises role.perm_version like a change of permissions, so it applies on the next request.

ALTER TABLE role
    ADD COLUMN scope_type varchar(30) NOT NULL DEFAULT 'ALL'
        CHECK (scope_type IN ('ALL', 'SELECTED_DEPARTMENTS', 'OWN_DEPARTMENT', 'OWN_TEAM'));

CREATE TABLE role_scope_department (
    school_id      uuid NOT NULL REFERENCES school (id),
    role_id        uuid NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    department_id  uuid NOT NULL REFERENCES department (id),
    PRIMARY KEY (role_id, department_id)
);
CREATE INDEX role_scope_department_dept_idx ON role_scope_department (department_id);
