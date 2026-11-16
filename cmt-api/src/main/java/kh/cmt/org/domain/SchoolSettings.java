package kh.cmt.org.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;

/**
 * The settings of one school (one row). Five sections; each has its own revision counter so that saving one
 * section does not conflict with someone saving another. Enumerated values are stored as their codes.
 */
@Entity
@Table(name = "school_settings")
public class SchoolSettings extends BaseEntity {

    // ---- School
    @Column(name = "name_km") private String nameKm;
    @Column(name = "name_en") private String nameEn;
    @Column(name = "name_ar") private String nameAr;
    @Column(name = "logo_file_id") private UUID logoFileId;
    private String address;
    @Column(name = "working_days") private String workingDays;
    private String timezone;
    @Column(name = "support_phone") private String supportPhone;
    @Column(name = "support_email") private String supportEmail;
    @Column(name = "school_rev") private long schoolRev;

    public String getSupportPhone() { return supportPhone; }
    public void setSupportPhone(String v) { this.supportPhone = v; }
    public String getSupportEmail() { return supportEmail; }
    public void setSupportEmail(String v) { this.supportEmail = v; }

    // ---- Attendance rules
    @Column(name = "default_grace_min") private int defaultGraceMin;
    @Column(name = "early_checkout_needs_reason") private boolean earlyCheckoutNeedsReason;
    @Column(name = "manual_checkin_allowed") private boolean manualCheckinAllowed;
    @Column(name = "offline_checkin_allowed") private boolean offlineCheckinAllowed;
    @Column(name = "missing_checkout_rule") private String missingCheckoutRule;
    @Column(name = "attendance_rev") private long attendanceRev;

    // ---- Notifications
    @Column(name = "checkin_reminder") private boolean checkinReminder;
    @Column(name = "checkin_reminder_min") private int checkinReminderMin;
    @Column(name = "checkout_reminder") private boolean checkoutReminder;
    @Column(name = "checkout_reminder_min") private int checkoutReminderMin;
    @Column(name = "shift2_reminder") private boolean shift2Reminder;
    @Column(name = "shift2_reminder_min") private int shift2ReminderMin;
    @Column(name = "notifications_rev") private long notificationsRev;

    // ---- Numbering (Staff IDs)
    @Column(name = "numbering_prefix") private String numberingPrefix;
    @Column(name = "numbering_separator") private String numberingSeparator;
    @Column(name = "numbering_length") private int numberingLength;
    @Column(name = "numbering_role_prefix") private boolean numberingRolePrefix;
    @Column(name = "numbering_year") private boolean numberingYear;
    @Column(name = "numbering_rev") private long numberingRev;

    // ---- Appearance
    @Column(name = "default_language") private String defaultLanguage;
    @Column(name = "default_theme") private String defaultTheme;
    @Column(name = "appearance_rev") private long appearanceRev;

    /** A settings row with the defaults, for a school that has none yet. */
    public static SchoolSettings withDefaults(String nameEn) {
        SchoolSettings s = new SchoolSettings();
        s.nameEn = nameEn;
        s.workingDays = "1,2,3,4,5,6";
        s.timezone = "Asia/Phnom_Penh";
        s.defaultGraceMin = 5;
        s.earlyCheckoutNeedsReason = true;
        s.manualCheckinAllowed = true;
        s.offlineCheckinAllowed = true;
        s.missingCheckoutRule = "ASK_CORRECTION_NEXT_DAY";
        s.checkinReminder = true;
        s.checkinReminderMin = 10;
        s.checkoutReminder = true;
        s.checkoutReminderMin = 0;
        s.shift2Reminder = true;
        s.shift2ReminderMin = 10;
        s.numberingPrefix = "CMT";
        s.numberingSeparator = "-";
        s.numberingLength = 4;
        s.defaultLanguage = "km";
        s.defaultTheme = "system";
        return s;
    }

    public String getNameKm() { return nameKm; }
    public void setNameKm(String v) { this.nameKm = v; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String v) { this.nameEn = v; }
    public String getNameAr() { return nameAr; }
    public void setNameAr(String v) { this.nameAr = v; }
    public UUID getLogoFileId() { return logoFileId; }
    public void setLogoFileId(UUID v) { this.logoFileId = v; }
    public String getAddress() { return address; }
    public void setAddress(String v) { this.address = v; }
    public String getWorkingDays() { return workingDays; }
    public void setWorkingDays(String v) { this.workingDays = v; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String v) { this.timezone = v; }
    public long getSchoolRev() { return schoolRev; }
    public void bumpSchoolRev() { this.schoolRev++; }

    public int getDefaultGraceMin() { return defaultGraceMin; }
    public void setDefaultGraceMin(int v) { this.defaultGraceMin = v; }
    public boolean isEarlyCheckoutNeedsReason() { return earlyCheckoutNeedsReason; }
    public void setEarlyCheckoutNeedsReason(boolean v) { this.earlyCheckoutNeedsReason = v; }
    public boolean isManualCheckinAllowed() { return manualCheckinAllowed; }
    public void setManualCheckinAllowed(boolean v) { this.manualCheckinAllowed = v; }
    public boolean isOfflineCheckinAllowed() { return offlineCheckinAllowed; }
    public void setOfflineCheckinAllowed(boolean v) { this.offlineCheckinAllowed = v; }
    public String getMissingCheckoutRule() { return missingCheckoutRule; }
    public void setMissingCheckoutRule(String v) { this.missingCheckoutRule = v; }
    public long getAttendanceRev() { return attendanceRev; }
    public void bumpAttendanceRev() { this.attendanceRev++; }

    public boolean isCheckinReminder() { return checkinReminder; }
    public void setCheckinReminder(boolean v) { this.checkinReminder = v; }
    public int getCheckinReminderMin() { return checkinReminderMin; }
    public void setCheckinReminderMin(int v) { this.checkinReminderMin = v; }
    public boolean isCheckoutReminder() { return checkoutReminder; }
    public void setCheckoutReminder(boolean v) { this.checkoutReminder = v; }
    public int getCheckoutReminderMin() { return checkoutReminderMin; }
    public void setCheckoutReminderMin(int v) { this.checkoutReminderMin = v; }
    public boolean isShift2Reminder() { return shift2Reminder; }
    public void setShift2Reminder(boolean v) { this.shift2Reminder = v; }
    public int getShift2ReminderMin() { return shift2ReminderMin; }
    public void setShift2ReminderMin(int v) { this.shift2ReminderMin = v; }
    public long getNotificationsRev() { return notificationsRev; }
    public void bumpNotificationsRev() { this.notificationsRev++; }

    public String getNumberingPrefix() { return numberingPrefix; }
    public void setNumberingPrefix(String v) { this.numberingPrefix = v; }
    public String getNumberingSeparator() { return numberingSeparator; }
    public void setNumberingSeparator(String v) { this.numberingSeparator = v; }
    public int getNumberingLength() { return numberingLength; }
    public void setNumberingLength(int v) { this.numberingLength = v; }
    public boolean isNumberingRolePrefix() { return numberingRolePrefix; }
    public void setNumberingRolePrefix(boolean v) { this.numberingRolePrefix = v; }
    public boolean isNumberingYear() { return numberingYear; }
    public void setNumberingYear(boolean v) { this.numberingYear = v; }
    public long getNumberingRev() { return numberingRev; }
    public void bumpNumberingRev() { this.numberingRev++; }

    public String getDefaultLanguage() { return defaultLanguage; }
    public void setDefaultLanguage(String v) { this.defaultLanguage = v; }
    public String getDefaultTheme() { return defaultTheme; }
    public void setDefaultTheme(String v) { this.defaultTheme = v; }
    public long getAppearanceRev() { return appearanceRev; }
    public void bumpAppearanceRev() { this.appearanceRev++; }
}
