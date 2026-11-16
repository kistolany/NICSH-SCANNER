-- Step 1.4: two-step verification for web users.

-- Authenticator-app (TOTP) enrolment and the phone for the SMS fallback.
--   phone is filled from the person / web user record (steps 3.6, 4.2).
--   totp_last_step is the last 30-second step that was accepted, so a code cannot be used twice.
ALTER TABLE account
    ADD COLUMN totp_secret_enc  text,
    ADD COLUMN totp_enabled_at  timestamptz,
    ADD COLUMN totp_last_step   bigint,
    ADD COLUMN phone            varchar(30);

-- "Require two-step verification" per role (Roles screen > login rules, step 3.5). The iam module keeps
-- this table in line with the role settings; a role without a row does not require it.
CREATE TABLE role_login_rule (
    school_id            uuid        NOT NULL,
    role                 varchar(50) NOT NULL,
    two_factor_required  boolean     NOT NULL DEFAULT false,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (school_id, role)
);
