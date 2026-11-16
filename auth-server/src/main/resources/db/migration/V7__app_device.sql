-- App login with device binding (the app's own login screen, POST /auth/login). One account works on one phone:
-- the first login binds the phone (device_id, already a column since V2), a login from another phone is refused with
-- DEVICE_MISMATCH. device_name is shown to the user ("Linked to iPhone 13"). Changing the phone comes with step 6.3.
ALTER TABLE account
    ADD COLUMN device_name      varchar(100),
    ADD COLUMN device_bound_at  timestamptz;
