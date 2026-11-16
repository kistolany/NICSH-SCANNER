-- V7: per-role settings of the Roles page. This step: "Sensitive fields" (what the role's users may see).
-- Approval limits and login rules are added to this table in step 3.5.
--
-- sensitive: {"salaryBank": bool, "nationalId": bool, "phone": bool, "idScans": bool}
--   salaryBank  salary, pay rates and bank details
--   nationalId  national ID number and personal documents
--   phone       phone numbers
--   idScans     scans of the national ID card
-- A group the role may not see shows as "••••" and is left out of exports. A change raises role.perm_version.

CREATE TABLE role_settings (
    role_id    uuid PRIMARY KEY REFERENCES role (id) ON DELETE CASCADE,
    school_id  uuid  NOT NULL REFERENCES school (id),
    sensitive  jsonb NOT NULL
);

-- Roles that already exist: the defaults of the design (system roles by code; custom roles: ID and phone).
INSERT INTO role_settings (role_id, school_id, sensitive)
SELECT id, school_id, CASE code
        WHEN 'super_admin' THEN '{"salaryBank": true,  "nationalId": true,  "phone": true, "idScans": true}'
        WHEN 'hr'          THEN '{"salaryBank": true,  "nationalId": true,  "phone": true, "idScans": false}'
        WHEN 'viewer'      THEN '{"salaryBank": false, "nationalId": false, "phone": true, "idScans": false}'
        ELSE                    '{"salaryBank": false, "nationalId": true,  "phone": true, "idScans": false}'
    END::jsonb
FROM role;
