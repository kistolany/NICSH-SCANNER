-- TEST ONLY (src/test/resources): the auth server's table that the iam module keeps up to date
-- (auth-server V4__two_factor.sql + V5__login_rules.sql). In dev and production the auth server creates it in the same
-- database; the cmt-api tests run without the auth server, so they get this copy. Keep it in line with the auth server.
CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE auth.role_login_rule (
    school_id                uuid        NOT NULL,
    role                     varchar(50) NOT NULL,
    two_factor_required      boolean     NOT NULL DEFAULT false,
    updated_at               timestamptz NOT NULL DEFAULT now(),
    session_timeout_minutes  int,
    allowed_from             time,
    allowed_to               time,
    allowed_days             smallint[],
    allowed_ips              text[],
    PRIMARY KEY (school_id, role)
);
