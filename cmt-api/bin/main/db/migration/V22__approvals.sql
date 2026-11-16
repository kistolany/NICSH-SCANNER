-- V22 (step 7.4, ahead of the approvals inbox in phase 10): one table for every request that waits for an admin.
-- 7.4 creates LATE_REASON requests; deciding them (and the other types, limits, escalation, undo) is phase 10.

CREATE TABLE approval (
    id                 uuid PRIMARY KEY,
    school_id          uuid          NOT NULL REFERENCES school (id),
    type               varchar(20)   NOT NULL CHECK (type IN ('LEAVE', 'CORRECTION', 'LATE_REASON', 'EARLY_REASON', 'EXTRA_HOUR',
                                                              'LATE_CONFIRM', 'TEACHER_REPORT', 'DEVICE_CHANGE')),
    subject_type       varchar(30)   NOT NULL,           -- what it is about: ATTENDANCE_RECORD, LEAVE_REQUEST, TEACHING_HOUR…
    subject_id         uuid          NOT NULL,
    person_id          uuid          NOT NULL REFERENCES person (id),   -- who asked (and whom it concerns)
    requested_by       uuid          NOT NULL,           -- the login account that sent it
    requested_at       timestamptz   NOT NULL,           -- first sent (a changed reason keeps it)
    reason             varchar(300),                     -- the person's own words
    details            jsonb,                            -- what the inbox shows besides (late minutes, check-in time…)
    status             varchar(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    decided_by         uuid,
    decided_at         timestamptz,
    comment            varchar(500),                     -- the admin's note (required to reject)
    escalated_to_role  varchar(40),
    created_at         timestamptz   NOT NULL,
    created_by         uuid,
    updated_at         timestamptz   NOT NULL,
    updated_by         uuid,
    version            bigint        NOT NULL DEFAULT 0,
    CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

-- one open request per subject and type (sending again changes it)
CREATE UNIQUE INDEX approval_one_open ON approval (type, subject_id) WHERE status = 'PENDING';
CREATE INDEX approval_inbox_idx ON approval (school_id, status, type, requested_at DESC);
CREATE INDEX approval_subject_idx ON approval (subject_id, requested_at DESC);
CREATE INDEX approval_person_idx ON approval (person_id, requested_at DESC);
