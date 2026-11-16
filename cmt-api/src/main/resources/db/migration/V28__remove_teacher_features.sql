-- V28 (2026-10-26): the school does not use the teacher features — the timetable, teaching hours (confirm, late
-- confirmation, extra hours, free slots, reports), subjects / classes / rooms, the teacher rules and teacher-hour reminder,
-- pay per teaching hour, the Academic role and the Timetable / Teacher hours permissions. Their tables, columns and rows go.
-- A person may still have the role "teacher": they use the app like staff (QR check-in, attendance, leave).
-- (V25 stays: migrations that ran are never removed.)

-- the timetable (step 8.1)
DROP TABLE timetable_slot;
DROP TABLE timetable_version;
DROP TABLE timetable_period;

-- subjects, classes, rooms and what teachers taught
DROP TABLE person_subject;
DROP TABLE person_class;
DROP TABLE subject;
DROP TABLE class_group;
DROP TABLE room;

-- teacher rules and the teacher-hour reminder (Settings)
ALTER TABLE school_settings
    DROP COLUMN confirm_window,
    DROP COLUMN late_confirm_needs_approval,
    DROP COLUMN extra_hours_need_approval,
    DROP COLUMN teacher_rev,
    DROP COLUMN teacher_hour_reminder,
    DROP COLUMN teacher_hour_reminder_min;

-- pay per teaching hour
UPDATE pay_rate SET pay_type = 'hourly' WHERE pay_type = 'teaching';
ALTER TABLE pay_rate DROP CONSTRAINT pay_rate_pay_type_check;
ALTER TABLE pay_rate ADD CONSTRAINT pay_rate_pay_type_check CHECK (pay_type IN ('monthly', 'hourly', 'none'));
ALTER TABLE pay_rate DROP COLUMN subject_rates, DROP COLUMN substitute_rate;

-- teacher approval types (never created: teaching hours were not built)
DELETE FROM approval WHERE type IN ('EXTRA_HOUR', 'LATE_CONFIRM', 'TEACHER_REPORT');
ALTER TABLE approval DROP CONSTRAINT approval_type_check;
ALTER TABLE approval ADD CONSTRAINT approval_type_check
    CHECK (type IN ('LEAVE', 'CORRECTION', 'LATE_REASON', 'EARLY_REASON', 'DEVICE_CHANGE'));

-- the Timetable and Teacher hours permissions
DELETE FROM role_permission WHERE module IN ('teacherHours', 'timetable');
ALTER TABLE role_permission DROP CONSTRAINT role_permission_module_check;
ALTER TABLE role_permission ADD CONSTRAINT role_permission_module_check
    CHECK (module IN ('dashboard', 'approvals', 'attendance', 'people', 'shifts', 'leave', 'qr', 'announcements', 'reports',
                      'users', 'settings', 'log'));
UPDATE role SET perm_version = perm_version + 1;   -- caches keyed by role + version read them again

-- the Academic role ("Teachers, timetable, hours"): its web users and logins become Viewer, then it goes
UPDATE web_user SET role_code = 'viewer' WHERE role_code = 'academic';
DO $$
BEGIN
    -- the sign-in server's tables (absent where it has not run yet, e.g. a test database before its copies are made)
    IF to_regclass('auth.account') IS NOT NULL THEN
        UPDATE auth.account SET role = 'viewer' WHERE role = 'academic';
    END IF;
    IF to_regclass('auth.role_login_rule') IS NOT NULL THEN
        DELETE FROM auth.role_login_rule WHERE role = 'academic';
    END IF;
END $$;
UPDATE role_settings SET approval_limits = jsonb_set(approval_limits, '{escalateTo}', 'null'::jsonb)
    WHERE approval_limits ->> 'escalateTo' = 'academic';
DELETE FROM role WHERE code = 'academic';
