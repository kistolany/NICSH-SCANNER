-- Step 1.3: password rules, reset tokens.

-- Rules for new passwords. One row per school; the Settings page (step 2.2) will edit it.
CREATE TABLE password_policy (
    school_id       uuid PRIMARY KEY,
    min_length      int         NOT NULL DEFAULT 8 CHECK (min_length BETWEEN 6 AND 64),
    require_upper   boolean     NOT NULL DEFAULT true,
    require_lower   boolean     NOT NULL DEFAULT false,
    require_digit   boolean     NOT NULL DEFAULT true,
    require_symbol  boolean     NOT NULL DEFAULT false,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

INSERT INTO password_policy (school_id) VALUES ('00000000-0000-0000-0000-000000000001');

ALTER TABLE account ADD COLUMN password_changed_at timestamptz;

-- "Forgot password": the token is sent to the user, only its hash is stored. Single use, short life.
CREATE TABLE password_reset_token (
    id           uuid PRIMARY KEY,
    account_id   uuid         NOT NULL REFERENCES account (id),
    token_hash   varchar(64)  NOT NULL UNIQUE,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz
);

CREATE INDEX password_reset_token_account_idx ON password_reset_token (account_id);
