-- V19 (step 6.2): QR code versions of a gate. The printed QR holds cmt://gate/{gateId}?v={version}&sig={sig};
-- sig is an HMAC made with the server's key (cmt.qr.secret) over the school, gate, version and this row's salt, so the
-- server can check it and print it again. Only a SHA-256 hash of sig is stored: the database alone cannot make a QR.
-- A gate has at most one open version (expires_at null). A new one makes the old one expire 5 minutes later; an admin
-- can expire one at once.
CREATE TABLE gate_qr (
    id              uuid PRIMARY KEY,
    school_id       uuid         NOT NULL REFERENCES school (id),
    gate_id         uuid         NOT NULL REFERENCES gate (id),
    qr_version      int          NOT NULL CHECK (qr_version >= 1),
    salt            varchar(32)  NOT NULL,
    sig_hash        varchar(64)  NOT NULL,
    expires_at      timestamptz,
    expired_reason  varchar(20)  CHECK (expired_reason IN ('REPLACED', 'MANUAL', 'GATE_INACTIVE')),
    expired_by      uuid,
    created_at      timestamptz  NOT NULL,
    created_by      uuid,
    updated_at      timestamptz  NOT NULL,
    updated_by      uuid,
    version         bigint       NOT NULL DEFAULT 0,
    UNIQUE (gate_id, qr_version)
);

CREATE UNIQUE INDEX gate_qr_open_idx ON gate_qr (gate_id) WHERE expires_at IS NULL;
CREATE INDEX gate_qr_school_idx ON gate_qr (school_id, created_at DESC);
