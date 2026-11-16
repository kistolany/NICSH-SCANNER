-- V3: the school settings (Settings page). One row per school, five sections.
-- Each section has its own revision counter, so saving one section never conflicts with another.

CREATE TABLE school_settings (
    id                           uuid PRIMARY KEY,
    school_id                    uuid         NOT NULL UNIQUE REFERENCES school (id),

    -- School
    name_km                      varchar(100),
    name_en                      varchar(100) NOT NULL,
    name_ar                      varchar(100),
    logo_file_id                 uuid REFERENCES file (id),
    address                      varchar(300),
    working_days                 varchar(13)  NOT NULL DEFAULT '1,2,3,4,5,6',   -- ISO weekdays, 1 = Monday ... 7 = Sunday
    timezone                     varchar(50)  NOT NULL DEFAULT 'Asia/Phnom_Penh',
    school_rev                   bigint       NOT NULL DEFAULT 0,

    -- Attendance rules
    default_grace_min            int          NOT NULL DEFAULT 5 CHECK (default_grace_min BETWEEN 0 AND 120),
    early_checkout_needs_reason  boolean      NOT NULL DEFAULT true,
    manual_checkin_allowed       boolean      NOT NULL DEFAULT true,
    offline_checkin_allowed      boolean      NOT NULL DEFAULT true,
    missing_checkout_rule        varchar(40)  NOT NULL DEFAULT 'ASK_CORRECTION_NEXT_DAY'
        CHECK (missing_checkout_rule IN ('ASK_CORRECTION_NEXT_DAY', 'AUTO_CLOSE_AT_SHIFT_END', 'FLAG_ONLY')),
    attendance_rev               bigint       NOT NULL DEFAULT 0,

    -- Teacher rules
    confirm_window               varchar(40)  NOT NULL DEFAULT 'HOUR_START_TO_END_OF_DAY'
        CHECK (confirm_window IN ('HOUR_START_TO_END_OF_DAY', 'HOUR_START_TO_HOUR_END')),
    late_confirm_needs_approval  boolean      NOT NULL DEFAULT true,
    extra_hours_need_approval    boolean      NOT NULL DEFAULT true,
    teacher_rev                  bigint       NOT NULL DEFAULT 0,

    -- Notifications (minutes before the shift / hour starts; the check-out reminder is minutes after the shift ends)
    checkin_reminder             boolean      NOT NULL DEFAULT true,
    checkin_reminder_min         int          NOT NULL DEFAULT 10 CHECK (checkin_reminder_min BETWEEN 1 AND 120),
    checkout_reminder            boolean      NOT NULL DEFAULT true,
    checkout_reminder_min        int          NOT NULL DEFAULT 0  CHECK (checkout_reminder_min BETWEEN 0 AND 120),
    teacher_hour_reminder        boolean      NOT NULL DEFAULT true,
    teacher_hour_reminder_min    int          NOT NULL DEFAULT 10 CHECK (teacher_hour_reminder_min BETWEEN 1 AND 120),
    shift2_reminder              boolean      NOT NULL DEFAULT true,
    shift2_reminder_min          int          NOT NULL DEFAULT 10 CHECK (shift2_reminder_min BETWEEN 1 AND 120),
    notifications_rev            bigint       NOT NULL DEFAULT 0,

    -- Appearance
    default_language             varchar(2)   NOT NULL DEFAULT 'km' CHECK (default_language IN ('km', 'en', 'ar')),
    default_theme                varchar(10)  NOT NULL DEFAULT 'system' CHECK (default_theme IN ('system', 'light', 'dark')),
    appearance_rev               bigint       NOT NULL DEFAULT 0,

    created_at                   timestamptz  NOT NULL,
    created_by                   uuid,
    updated_at                   timestamptz  NOT NULL,
    updated_by                   uuid,
    version                      bigint       NOT NULL DEFAULT 0
);

-- The CMT school starts with the values from the design. Other schools get a row with the defaults on first use.
INSERT INTO school_settings (id, school_id, name_km, name_en, address, created_at, updated_at)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 'សាលា CMT', 'CMT School', 'St. 271, Phnom Penh',
        now(), now());
