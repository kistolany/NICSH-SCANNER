-- V8: approval limits and login rules per role (Roles page). Stored as JSON next to the sensitive fields (V7).
--
-- approval_limits: {"leaveMaxDays": 3 | null (no limit), "oldCorrections": bool, "ownRequests": bool,
--                   "escalateTo": role code | null}
--   A request above what the role may approve goes to the inbox of the escalation role (and on up the chain).
-- login_rules:     {"twoFactorRequired": bool, "sessionTimeoutMinutes": 480, "allowedFrom": "06:00" | null,
--                   "allowedTo": "20:00" | null, "allowedDays": [1..7] (ISO, Monday = 1), "allowedIps": ["10.0.0.0/8", ...]}
--   The iam module copies them into auth.role_login_rule, where the auth server and (through the tokens) the BFF
--   check them.

ALTER TABLE role_settings
    ADD COLUMN approval_limits jsonb,
    ADD COLUMN login_rules     jsonb;

UPDATE role_settings s SET
    approval_limits = CASE WHEN r.code = 'super_admin'
        THEN '{"leaveMaxDays": null, "oldCorrections": true, "ownRequests": false, "escalateTo": null}'
        ELSE '{"leaveMaxDays": 3, "oldCorrections": false, "ownRequests": false, "escalateTo": "super_admin"}' END::jsonb,
    login_rules = CASE WHEN r.code = 'super_admin'
        THEN '{"twoFactorRequired": false, "sessionTimeoutMinutes": 120, "allowedFrom": null, "allowedTo": null, "allowedDays": [1,2,3,4,5,6,7], "allowedIps": []}'
        ELSE '{"twoFactorRequired": false, "sessionTimeoutMinutes": 480, "allowedFrom": null, "allowedTo": null, "allowedDays": [1,2,3,4,5,6,7], "allowedIps": []}' END::jsonb
FROM role r WHERE r.id = s.role_id;

ALTER TABLE role_settings
    ALTER COLUMN approval_limits SET NOT NULL,
    ALTER COLUMN login_rules SET NOT NULL;
