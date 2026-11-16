-- Auth server schema (runs inside Postgres schema "auth"; see spring.flyway.default-schema).
-- Never edit after it has run anywhere; add V2, V3... instead.

-- ---------------------------------------------------------------- account
-- One login per person (type app) or per office user (type web). Passwords: hashed, never plain.
CREATE TABLE account (
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
    version               bigint       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX account_username_idx ON account (lower(username));
CREATE UNIQUE INDEX account_person_idx ON account (person_id) WHERE person_id IS NOT NULL;
CREATE UNIQUE INDEX account_web_user_idx ON account (web_user_id) WHERE web_user_id IS NOT NULL;

-- ---------------------------------------------------------------- refresh_token
-- Our own record of every refresh token (hash only). A family = one login session; token rotation
-- keeps the family_id and points rotated_from at the previous token. Reuse detection comes in step 1.2.
CREATE TABLE refresh_token (
    id            uuid PRIMARY KEY,
    account_id    uuid         NOT NULL REFERENCES account (id),
    family_id     uuid         NOT NULL,
    token_hash    varchar(64)  NOT NULL UNIQUE,
    rotated_from  uuid REFERENCES refresh_token (id),
    issued_at     timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    used_at       timestamptz,
    revoked_at    timestamptz
);

CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
CREATE INDEX refresh_token_account_idx ON refresh_token (account_id);

-- ---------------------------------------------------------------- signing keys (RS256, with rotation)
-- Exactly one active key signs new tokens; retired keys stay in the JWKS until old tokens have expired.
-- The private key is stored AES-GCM encrypted with the secret from AUTH_KEY_SECRET.
CREATE TABLE signing_key (
    kid               varchar(64) PRIMARY KEY,
    algorithm         varchar(10) NOT NULL DEFAULT 'RS256',
    public_key        text        NOT NULL,
    private_key_enc   text        NOT NULL,
    status            varchar(10) NOT NULL CHECK (status IN ('active', 'retired')),
    created_at        timestamptz NOT NULL,
    retired_at        timestamptz
);

CREATE UNIQUE INDEX signing_key_one_active_idx ON signing_key ((true)) WHERE status = 'active';

-- ---------------------------------------------------------------- Spring Authorization Server tables
-- From spring-security-oauth2-authorization-server 1.5.2, with 'blob' columns changed to 'text' for PostgreSQL.
CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamp DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX oauth2_registered_client_client_id_idx ON oauth2_registered_client (client_id);

CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamp DEFAULT NULL,
    authorization_code_expires_at timestamp DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamp DEFAULT NULL,
    access_token_expires_at timestamp DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamp DEFAULT NULL,
    oidc_id_token_expires_at timestamp DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamp DEFAULT NULL,
    refresh_token_expires_at timestamp DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamp DEFAULT NULL,
    user_code_expires_at timestamp DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamp DEFAULT NULL,
    device_code_expires_at timestamp DEFAULT NULL,
    device_code_metadata text DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX oauth2_authorization_principal_idx ON oauth2_authorization (principal_name);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
