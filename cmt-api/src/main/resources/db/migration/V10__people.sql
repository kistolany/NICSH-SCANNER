-- V10: the person record (People page, Add / Edit person). App users are people (staff / teacher / admin); their login
-- account comes in step 4.2, documents in 4.3, pay in 4.4. Campus and default gate are added with school areas and
-- gates (step 6.1).
--
-- Codes (stored lower case, checked here and in the service):
--   role             staff | teacher | admin
--   gender           female | male
--   marital_status   single | married | divorced | widowed
--   blood_type       A+ A- B+ B- AB+ AB- O+ O-
--   employment_type  full_time | part_time | contract | volunteer | intern
--   inactive reason  RESIGNED | CONTRACT_ENDED | TRANSFERRED | MISTAKE | OTHER (delete_reason), with last_working_day
-- The staff ID is unique in the school among all people, inactive ones too: IDs are never used twice.
CREATE TABLE person (
    id                    uuid PRIMARY KEY,
    school_id             uuid         NOT NULL REFERENCES school (id),
    staff_id              varchar(30)  NOT NULL,
    name_km               varchar(150) NOT NULL,
    name_en               varchar(150) NOT NULL,
    name_ar               varchar(150),
    gender                varchar(10)  CHECK (gender IN ('female', 'male')),
    date_of_birth         date,
    nationality           varchar(2),
    national_id           varchar(30),
    marital_status        varchar(10)  CHECK (marital_status IN ('single', 'married', 'divorced', 'widowed')),
    blood_type            varchar(3)   CHECK (blood_type IN ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-')),
    phone                 varchar(30),
    phone_alt             varchar(30),
    email                 varchar(150),
    address_province      varchar(100),
    address_district      varchar(100),
    address_commune       varchar(100),
    address_village       varchar(100),
    address_street        varchar(200),
    emergency_name        varchar(150),
    emergency_relation    varchar(50),
    emergency_phone       varchar(30),
    role                  varchar(10)  NOT NULL CHECK (role IN ('staff', 'teacher', 'admin')),
    department_id         uuid REFERENCES department (id),
    position_id           uuid REFERENCES job_position (id),
    employment_type       varchar(12)  CHECK (employment_type IN ('full_time', 'part_time', 'contract', 'volunteer', 'intern')),
    start_date            date,
    contract_end_date     date,
    probation_end_date    date,
    supervisor_id         uuid REFERENCES person (id),
    last_working_day      date,
    inactive              boolean      NOT NULL DEFAULT false,
    deleted_at            timestamptz,
    deleted_by            uuid,
    delete_reason         varchar(500),
    created_at            timestamptz  NOT NULL,
    created_by            uuid,
    updated_at            timestamptz  NOT NULL,
    updated_by            uuid,
    version               bigint       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX person_staff_id_idx ON person (school_id, lower(staff_id));
CREATE UNIQUE INDEX person_national_id_idx ON person (school_id, upper(national_id)) WHERE national_id IS NOT NULL;
CREATE INDEX person_department_idx ON person (school_id, department_id) WHERE NOT inactive;
CREATE INDEX person_supervisor_idx ON person (supervisor_id) WHERE supervisor_id IS NOT NULL;
CREATE INDEX person_name_en_idx ON person (school_id, lower(name_en));

-- What a teacher teaches (Add person > Work). Only for role teacher.
CREATE TABLE person_subject (
    person_id   uuid NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    subject_id  uuid NOT NULL REFERENCES subject (id),
    PRIMARY KEY (person_id, subject_id)
);

CREATE TABLE person_class (
    person_id       uuid NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    class_group_id  uuid NOT NULL REFERENCES class_group (id),
    PRIMARY KEY (person_id, class_group_id)
);

-- A web user may be linked to a person (step 3.6 left the foreign key for now). No person existed before this
-- migration, so any value there cannot point anywhere.
UPDATE web_user SET linked_person_id = NULL WHERE linked_person_id IS NOT NULL;
ALTER TABLE web_user ADD CONSTRAINT web_user_linked_person_fk FOREIGN KEY (linked_person_id) REFERENCES person (id);
