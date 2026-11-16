-- Step 3.5: the rest of the per-role login rules (Roles screen > Security). The iam module copies them from the role
-- settings (cmt-api AuthLoginRuleSync). A null column means "no restriction".
--   session_timeout_minutes  idle time before the Admin Web session ends (enforced by the BFF, from the token)
--   allowed_from / _to       sign-in hours in school time; allowed_to before allowed_from = the hours run past midnight
--   allowed_days             ISO weekdays (Monday = 1)
--   allowed_ips              addresses or CIDR ranges; empty = anywhere
ALTER TABLE role_login_rule
    ADD COLUMN session_timeout_minutes int,
    ADD COLUMN allowed_from            time,
    ADD COLUMN allowed_to              time,
    ADD COLUMN allowed_days            smallint[],
    ADD COLUMN allowed_ips             text[];
