-- TEST ONLY (src/test/resources): the auth server's password rules (auth-server V3), which the iam module follows
-- when it creates app logins (step 4.2). Keep it in line with the auth server.
CREATE TABLE auth.password_policy (
    school_id       uuid PRIMARY KEY,
    min_length      int         NOT NULL DEFAULT 8 CHECK (min_length BETWEEN 6 AND 64),
    require_upper   boolean     NOT NULL DEFAULT true,
    require_lower   boolean     NOT NULL DEFAULT false,
    require_digit   boolean     NOT NULL DEFAULT true,
    require_symbol  boolean     NOT NULL DEFAULT false,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

INSERT INTO auth.password_policy (school_id) VALUES ('00000000-0000-0000-0000-000000000001');
