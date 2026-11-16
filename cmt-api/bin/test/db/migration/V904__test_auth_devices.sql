-- TEST ONLY (src/test/resources): the auth server's device tables (auth-server V7 + V9) the devices module reads and
-- writes. In dev and production the auth server creates them in the same database. Keep it in line with the auth server.
ALTER TABLE auth.account
    ADD COLUMN device_name          varchar(100),
    ADD COLUMN device_bound_at      timestamptz,
    ADD COLUMN device_platform      varchar(10),
    ADD COLUMN device_os            varchar(40),
    ADD COLUMN device_app_version   varchar(20),
    ADD COLUMN device_public_key    text,
    ADD COLUMN device_rooted        boolean,
    ADD COLUMN device_last_seen_at  timestamptz;

CREATE TABLE auth.device_policy (
    school_id            uuid PRIMARY KEY,
    one_device           boolean      NOT NULL DEFAULT true,
    change_requests      boolean      NOT NULL DEFAULT true,
    auto_rebind_days     int          CHECK (auto_rebind_days IN (7, 30)),
    min_app_version      varchar(20),
    block_rooted         boolean      NOT NULL DEFAULT true,
    block_mock_location  boolean      NOT NULL DEFAULT true,
    updated_at           timestamptz,
    updated_by           uuid,
    version              bigint       NOT NULL DEFAULT 0
);

CREATE TABLE auth.device_block (
    device_id    uuid PRIMARY KEY,
    school_id    uuid          NOT NULL,
    account_id   uuid          REFERENCES auth.account (id) ON DELETE SET NULL,
    device_name  varchar(100),
    reason       varchar(100),
    blocked_by   uuid,
    blocked_at   timestamptz   NOT NULL
);

CREATE TABLE auth.device_change_request (
    id                uuid PRIMARY KEY,
    school_id         uuid          NOT NULL,
    account_id        uuid          NOT NULL REFERENCES auth.account (id) ON DELETE CASCADE,
    status            varchar(10)   NOT NULL CHECK (status IN ('pending', 'seen', 'approved', 'rejected', 'cancelled')),
    reason            varchar(20)   NOT NULL CHECK (reason IN ('new_phone', 'lost_phone', 'broken')),
    old_device_id     uuid,
    old_device_name   varchar(100),
    new_device_id     uuid          NOT NULL,
    new_device_name   varchar(100),
    new_platform      varchar(10),
    created_at        timestamptz   NOT NULL,
    seen_at           timestamptz,
    decided_at        timestamptz,
    decided_by        uuid,
    rejection_reason  varchar(300)
);
CREATE UNIQUE INDEX device_change_request_open_idx ON auth.device_change_request (account_id) WHERE status IN ('pending', 'seen');

CREATE TABLE auth.device_change_token (
    token_hash   varchar(64)   PRIMARY KEY,
    account_id   uuid          NOT NULL REFERENCES auth.account (id) ON DELETE CASCADE,
    device_id    uuid          NOT NULL,
    device_name  varchar(100),
    platform     varchar(10),
    created_at   timestamptz   NOT NULL,
    expires_at   timestamptz   NOT NULL
);

CREATE TABLE auth.device_event (
    id               uuid PRIMARY KEY,
    school_id        uuid          NOT NULL,
    account_id       uuid          NOT NULL REFERENCES auth.account (id) ON DELETE CASCADE,
    kind             varchar(20)   NOT NULL
                     CHECK (kind IN ('LINKED', 'RESET', 'CHANGE_APPROVED', 'AUTO_REBIND', 'BLOCKED', 'UNBLOCKED')),
    old_device_name  varchar(100),
    new_device_name  varchar(100),
    reason           varchar(100),
    by_account       uuid,
    at               timestamptz   NOT NULL
);
