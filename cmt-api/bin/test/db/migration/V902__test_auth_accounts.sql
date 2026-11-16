-- TEST ONLY (src/test/resources): the auth server's tables the iam module writes for web users
-- (auth-server V1, V2, V3, V4, V6). In dev and production the auth server creates them in the same database; the
-- cmt-api tests run without the auth server, so they get this copy. Keep it in line with the auth server.
CREATE TABLE auth.account (
    id                    uuid PRIMARY KEY,
    school_id             uuid         NOT NULL,
    username              varchar(100) NOT NULL,
    password_hash         varchar(200) NOT NULL,
    type                  varchar(10)  NOT NULL CHECK (type IN ('app', 'web')),
    person_id             uuid,
    web_user_id           uuid,
    status                varchar(20)  NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive', 'blocked')),
    failed_attempts       int          NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    must_change_password  boolean      NOT NULL DEFAULT false,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    version               bigint       NOT NULL DEFAULT 0,
    role                  varchar(50)  NOT NULL DEFAULT 'staff',
    perm_ver              int          NOT NULL DEFAULT 1,
    locale                varchar(5)   NOT NULL DEFAULT 'km',
    device_id             uuid,
    password_changed_at   timestamptz,
    totp_secret_enc       text,
    totp_enabled_at       timestamptz,
    totp_last_step        bigint,
    phone                 varchar(30),
    access_expires_on     date,
    two_factor_required   boolean      NOT NULL DEFAULT false,
    sessions_revoked_at   timestamptz,
    last_login_at         timestamptz
);
CREATE UNIQUE INDEX account_username_idx ON auth.account (lower(username));
-- auth-server V1 + V8: one app and one web login per person, one account per web user
CREATE UNIQUE INDEX account_person_idx ON auth.account (person_id, type) WHERE person_id IS NOT NULL;
CREATE UNIQUE INDEX account_web_user_idx ON auth.account (web_user_id) WHERE web_user_id IS NOT NULL;

CREATE TABLE auth.refresh_token (
    id            uuid PRIMARY KEY,
    account_id    uuid         NOT NULL REFERENCES auth.account (id),
    family_id     uuid         NOT NULL,
    token_hash    varchar(64)  NOT NULL UNIQUE,
    rotated_from  uuid,
    issued_at     timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    used_at       timestamptz,
    revoked_at    timestamptz
);

CREATE TABLE auth.password_reset_token (
    id           uuid PRIMARY KEY,
    account_id   uuid         NOT NULL REFERENCES auth.account (id),
    token_hash   varchar(64)  NOT NULL UNIQUE,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz
);

CREATE TABLE auth.oauth2_authorization (
    id                        varchar(100) PRIMARY KEY,
    registered_client_id      varchar(100) NOT NULL,
    principal_name            varchar(200) NOT NULL,
    authorization_grant_type  varchar(100) NOT NULL
);
