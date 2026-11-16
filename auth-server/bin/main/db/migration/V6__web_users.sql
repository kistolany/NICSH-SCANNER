-- Step 3.6: web users. The iam module (cmt-api) creates and keeps the account of every web user.
--   access_expires_on    last day the account may be used (school time); from the next day no login, no refresh
--   two_factor_required  two-step verification for this user, besides the role's rule (role_login_rule)
--   sessions_revoked_at  "sign out everywhere": login sessions opened before it must sign in again
--   last_login_at        last successful password login (shown on the web users list)
ALTER TABLE account
    ADD COLUMN access_expires_on    date,
    ADD COLUMN two_factor_required  boolean NOT NULL DEFAULT false,
    ADD COLUMN sessions_revoked_at  timestamptz,
    ADD COLUMN last_login_at        timestamptz;
