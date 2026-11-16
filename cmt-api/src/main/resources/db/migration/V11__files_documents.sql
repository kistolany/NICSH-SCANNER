-- V11: uploads (files module) and people's photo and documents (step 4.3).
--
-- A file is uploaded straight to object storage with a short-lived link, then confirmed: only then is it READY
-- (size, type and content checked, images resized and given a thumbnail). It is "attached" once a record uses it
-- (a person's photo or document, the school logo); a file that is never attached can be cleaned up later.
ALTER TABLE file
    ADD COLUMN purpose        varchar(10)  NOT NULL DEFAULT 'DOCUMENT' CHECK (purpose IN ('PHOTO', 'DOCUMENT', 'LOGO')),
    ADD COLUMN status         varchar(10)  NOT NULL DEFAULT 'READY' CHECK (status IN ('PENDING', 'READY')),
    ADD COLUMN original_name  varchar(255),
    ADD COLUMN thumbnail_key  varchar(500),
    ADD COLUMN width          int,
    ADD COLUMN height         int,
    ADD COLUMN attached       boolean      NOT NULL DEFAULT false;

-- The school logo chosen before uploads existed counts as attached.
UPDATE file SET attached = true, purpose = 'LOGO' WHERE id IN (SELECT logo_file_id FROM school_settings WHERE logo_file_id IS NOT NULL);

ALTER TABLE person ADD COLUMN photo_file_id uuid REFERENCES file (id);

-- A person's documents. Types: NATIONAL_ID_FRONT, NATIONAL_ID_BACK (national ID scans), CONTRACT, DEGREE, CV,
-- HEALTH_CERT, POLICE_CLEARANCE, OTHER (national ID & documents); several of each type may be kept.
-- No cascade from person: a person with documents cannot be deleted forever (their documents are history).
CREATE TABLE person_document (
    id           uuid PRIMARY KEY,
    school_id    uuid         NOT NULL REFERENCES school (id),
    person_id    uuid         NOT NULL REFERENCES person (id),
    type         varchar(20)  NOT NULL CHECK (type IN ('NATIONAL_ID_FRONT', 'NATIONAL_ID_BACK', 'CONTRACT', 'DEGREE', 'CV',
                                                       'HEALTH_CERT', 'POLICE_CLEARANCE', 'OTHER')),
    file_id      uuid         NOT NULL UNIQUE REFERENCES file (id),
    issue_date   date,
    expiry_date  date,
    note         varchar(200),
    created_at   timestamptz  NOT NULL,
    created_by   uuid,
    updated_at   timestamptz  NOT NULL,
    updated_by   uuid,
    version      bigint       NOT NULL DEFAULT 0
);

CREATE INDEX person_document_person_idx ON person_document (person_id);
CREATE INDEX person_document_expiry_idx ON person_document (school_id, expiry_date) WHERE expiry_date IS NOT NULL;
