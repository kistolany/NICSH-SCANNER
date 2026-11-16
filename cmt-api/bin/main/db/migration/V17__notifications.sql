-- V17 (step 5.5, with the core of 11.1): the outbox is read by a relay; notifications for people; phones to push to.

-- ---------------------------------------------------------------- outbox: retries
-- A row that fails is tried again later (attempts, next_attempt_at); after too many tries it stays with its error.
ALTER TABLE outbox
    ADD COLUMN attempts        int         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN last_error      text;

DROP INDEX outbox_unpublished_idx;
CREATE INDEX outbox_unpublished_idx ON outbox (coalesce(next_attempt_at, created_at)) WHERE published_at IS NULL;

-- ---------------------------------------------------------------- notification
-- One per person and event. Only the code and its values are stored; the text is made in the reader's language
-- (km / en / ar) when it is shown or pushed.
CREATE TABLE notification (
    id          uuid PRIMARY KEY,
    school_id   uuid         NOT NULL REFERENCES school (id),
    person_id   uuid         NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    kind        varchar(30)  NOT NULL,
    code        varchar(60)  NOT NULL,
    params      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    read_at     timestamptz,
    created_at  timestamptz  NOT NULL,
    created_by  uuid,
    updated_at  timestamptz  NOT NULL,
    updated_by  uuid,
    version     bigint       NOT NULL DEFAULT 0
);

CREATE INDEX notification_person_idx ON notification (person_id, created_at DESC);
CREATE INDEX notification_unread_idx ON notification (person_id) WHERE read_at IS NULL;

-- ---------------------------------------------------------------- push_token
-- A phone's push token (FCM) for a person, with the app's language so pushes come in it. A token belongs to one
-- person: registering it again moves it (the phone changed hands or accounts).
CREATE TABLE push_token (
    id          uuid PRIMARY KEY,
    school_id   uuid         NOT NULL REFERENCES school (id),
    person_id   uuid         NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    account_id  uuid         NOT NULL,
    token       varchar(512) NOT NULL UNIQUE,
    platform    varchar(10)  NOT NULL CHECK (platform IN ('android', 'ios')),
    locale      varchar(2)   NOT NULL CHECK (locale IN ('km', 'en', 'ar')),
    device_id   uuid,
    created_at  timestamptz  NOT NULL,
    created_by  uuid,
    updated_at  timestamptz  NOT NULL,
    updated_by  uuid,
    version     bigint       NOT NULL DEFAULT 0
);

CREATE INDEX push_token_person_idx ON push_token (person_id);
