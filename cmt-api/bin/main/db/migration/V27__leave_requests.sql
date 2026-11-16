-- V27: leave requests (step 9.2). A person asks for whole days (start – end, one calendar year) or half a day (AM | PM
-- of one date); the days are counted from the person's schedule (days off and holidays do not count). Each request is
-- also an approval (type LEAVE, subject LEAVE_REQUEST) that an admin approves or rejects; approved requests count as
-- used days and make those days LEAVE for attendance. Taking a request back while it waits cancels it.
CREATE TABLE leave_request (
    id                  uuid PRIMARY KEY,
    school_id           uuid          NOT NULL REFERENCES school (id),
    person_id           uuid          NOT NULL REFERENCES person (id),
    leave_type_id       uuid          NOT NULL REFERENCES leave_type (id),
    start_date          date          NOT NULL,
    end_date            date          NOT NULL,
    half                varchar(2)    CHECK (half IN ('AM', 'PM')),     -- null = whole days
    days                numeric(5, 1) NOT NULL CHECK (days > 0),
    working_dates       jsonb         NOT NULL,                         -- the dates counted, as sent
    reason              varchar(300)  NOT NULL,
    attachment_file_id  uuid          REFERENCES file (id),
    short_notice        boolean       NOT NULL DEFAULT false,           -- starts in less than 3 days (or already started)
    status              varchar(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    decided_by          uuid,
    decided_at          timestamptz,
    comment             varchar(500),
    created_at          timestamptz   NOT NULL,
    created_by          uuid,
    updated_at          timestamptz   NOT NULL,
    updated_by          uuid,
    version             bigint        NOT NULL DEFAULT 0,
    CHECK (end_date >= start_date),
    CHECK (half IS NULL OR start_date = end_date),
    CHECK (extract(year FROM start_date) = extract(year FROM end_date)),
    CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
CREATE INDEX leave_request_person_idx ON leave_request (person_id, start_date DESC);
CREATE INDEX leave_request_dates_idx ON leave_request (person_id, start_date, end_date) WHERE status IN ('PENDING', 'APPROVED');
