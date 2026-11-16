package kh.cmt.org.application;

import java.util.LinkedHashMap;
import java.util.Map;
import kh.cmt.org.api.AppearanceSettings;
import kh.cmt.org.api.AttendanceRules;
import kh.cmt.org.api.NotificationSettings;
import kh.cmt.org.api.NumberingPreview;
import kh.cmt.org.api.NumberingSettings;
import kh.cmt.org.api.SchoolInfo;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import org.springframework.stereotype.Service;

/**
 * The Settings page of the Admin Web: the use cases behind its endpoints. Reading needs View and saving needs Edit
 * in the Settings module. (The plain {@link SettingsService} stays unguarded because other modules read the settings
 * through it; the mobile app has its own read-only view.)
 */
@Service
public class SettingsAdminService {

    private final SettingsService settings;
    private final NumberingService numbering;

    SettingsAdminService(SettingsService settings, NumberingService numbering) {
        this.settings = settings;
        this.numbering = numbering;
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.VIEW)
    public Map<String, Object> page() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("school", settings.school());
        page.put("attendance", settings.attendanceRules());
        page.put("notifications", settings.notifications());
        page.put("appearance", settings.appearance());
        page.put("numbering", numbering.numbering());
        return page;
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.VIEW)
    public SchoolInfo school() {
        return settings.school();
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.EDIT)
    public SchoolInfo saveSchool(SchoolInfo in) {
        return settings.saveSchool(in);
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.VIEW)
    public AttendanceRules attendance() {
        return settings.attendanceRules();
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.EDIT)
    public AttendanceRules saveAttendance(AttendanceRules in) {
        return settings.saveAttendance(in);
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.VIEW)
    public NotificationSettings notifications() {
        return settings.notifications();
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.EDIT)
    public NotificationSettings saveNotifications(NotificationSettings in) {
        return settings.saveNotifications(in);
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.VIEW)
    public AppearanceSettings appearance() {
        return settings.appearance();
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.EDIT)
    public AppearanceSettings saveAppearance(AppearanceSettings in) {
        return settings.saveAppearance(in);
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.VIEW)
    public NumberingSettings numbering() {
        return numbering.numbering();
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.EDIT)
    public NumberingSettings saveNumbering(NumberingSettings in) {
        return numbering.save(in);
    }

    /** Previews the next Staff IDs; the optional values are edits that are not saved yet. */
    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.VIEW)
    public NumberingPreview previewNumbering(String prefix, String separator, Integer length, Boolean rolePrefix,
            Boolean includeYear) {
        NumberingSettings saved = numbering.numbering();
        return numbering.preview(new NumberingSettings(prefix != null ? prefix : saved.prefix(),
                separator != null ? separator : saved.separator(), length != null ? length : saved.length(),
                rolePrefix != null ? rolePrefix : saved.rolePrefix(), includeYear != null ? includeYear : saved.includeYear(),
                null, saved.version()));
    }

    @RequirePermission(module = PermissionModule.SETTINGS, action = PermissionAction.EDIT)
    public NumberingSettings advanceNumbering(long nextNumber) {
        return numbering.advance(nextNumber);
    }
}
