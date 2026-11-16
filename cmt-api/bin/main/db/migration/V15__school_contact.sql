-- V15: the school's contact for staff (Settings > School): shown on the app's login screen in "Help", "Contact admin"
-- and "Forgot password" (GET /api/v1/public/support, no sign-in needed).
ALTER TABLE school_settings
    ADD COLUMN support_phone  varchar(30),
    ADD COLUMN support_email  varchar(150);
