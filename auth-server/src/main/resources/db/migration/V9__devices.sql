-- V9 (step 6.3): the phone of an app account, the school's device rules, blocked phones, change requests and the
-- device history. The app login (POST /auth/login) reads and writes them; cmt-api's devices module (Admin Web ›
-- Devices) reads them and makes the admin's changes (reset, block, approve a change, settings).

-- the linked phone: besides device_id / device_name / device_bound_at (V2, V7)
ALTER TABLE account
    ADD COLUMN device_platform      varchar(10),
    ADD COLUMN device_os            varchar(40),
    ADD COLUMN device_app_version   varchar(20),
    ADD COLUMN device_public_key    text,
    ADD COLUMN device_rooted        boolean,
    ADD COLUMN device_last_seen_at  timestamptz;

-- Device settings of a school (Admin Web › Devices › Device settings). No row = the defaults.
CREATE TABLE device_policy (
    school_id            uuid PRIMARY KEY,
    one_device           boolean      NOT NULL DEFAULT true,   -- false: every login moves the account to that phone
    change_requests      boolean      NOT NULL DEFAULT true,   -- staff may ask for a new phone from the app
    auto_rebind_days     int          CHECK (auto_rebind_days IN (7, 30)),   -- null = off
    min_app_version      varchar(20),                          -- below it: UPDATE_REQUIRED
    block_rooted         boolean      NOT NULL DEFAULT true,   -- used by the scan (step 7.2)
    block_mock_location  boolean      NOT NULL DEFAULT true,   -- used by the scan (step 7.2)
    updated_at           timestamptz,
    updated_by           uuid,
    version              bigint       NOT NULL DEFAULT 0
);

-- A blocked phone (stolen, lost…): no account can log in on it until it is unblocked.
CREATE TABLE device_block (
    device_id    uuid PRIMARY KEY,
    school_id    uuid          NOT NULL,
    account_id   uuid          REFERENCES account (id) ON DELETE SET NULL,
    device_name  varchar(100),
    reason       varchar(100),
    blocked_by   uuid,
    blocked_at   timestamptz   NOT NULL
);

-- "Request device change" from the app, sent from the new phone after DEVICE_MISMATCH. One open request per account.
CREATE TABLE device_change_request (
    id                uuid PRIMARY KEY,
    school_id         uuid          NOT NULL,
    account_id        uuid          NOT NULL REFERENCES account (id) ON DELETE CASCADE,
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
CREATE UNIQUE INDEX device_change_request_open_idx ON device_change_request (account_id) WHERE status IN ('pending', 'seen');
CREATE INDEX device_change_request_school_idx ON device_change_request (school_id, created_at DESC);

-- What the DEVICE_MISMATCH answer hands the new phone (deviceChangeToken): it lets that phone ask for the change and
-- follow it, nothing else. Only a hash is kept.
CREATE TABLE device_change_token (
    token_hash   varchar(64)   PRIMARY KEY,
    account_id   uuid          NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    device_id    uuid          NOT NULL,
    device_name  varchar(100),
    platform     varchar(10),
    created_at   timestamptz   NOT NULL,
    expires_at   timestamptz   NOT NULL
);

-- Device history (Devices › Reset history; audit later).
CREATE TABLE device_event (
    id               uuid PRIMARY KEY,
    school_id        uuid          NOT NULL,
    account_id       uuid          NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    kind             varchar(20)   NOT NULL
                     CHECK (kind IN ('LINKED', 'RESET', 'CHANGE_APPROVED', 'AUTO_REBIND', 'BLOCKED', 'UNBLOCKED')),
    old_device_name  varchar(100),
    new_device_name  varchar(100),
    reason           varchar(100),
    by_account       uuid,
    at               timestamptz   NOT NULL
);
CREATE INDEX device_event_school_idx ON device_event (school_id, at DESC);
CREATE INDEX device_event_account_idx ON device_event (account_id, at DESC);
