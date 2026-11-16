-- Data the access token carries (step 1.2). The real owners fill these in later phases:
--   role      <- people (app users: staff | teacher | admin) / iam (web users: role code)   phases 3-4
--   perm_ver  <- bumped by iam when a role's permissions change or on "force logout"        phase 3
--   locale    <- the user's language choice                                                  phase 4
--   device_id <- device binding for app users                                                step 6.3
ALTER TABLE account
    ADD COLUMN role       varchar(50) NOT NULL DEFAULT 'staff',
    ADD COLUMN perm_ver   int         NOT NULL DEFAULT 1,
    ADD COLUMN locale     varchar(5)  NOT NULL DEFAULT 'km' CHECK (locale IN ('km', 'en', 'ar')),
    ADD COLUMN device_id  uuid;
