package kh.cmt.org.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import kh.cmt.org.api.AppearanceSettings;
import kh.cmt.org.api.AttendanceRules;
import kh.cmt.org.api.NotificationSettings;
import kh.cmt.org.api.NumberingPreview;
import kh.cmt.org.api.NumberingSettings;
import kh.cmt.org.api.SchoolInfo;
import kh.cmt.org.application.SettingsAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, Settings page. One GET for the whole page and one GET / PUT per section:
 * {@code school}, {@code attendance}, {@code notifications}, {@code appearance}, {@code numbering}
 * (Staff ID format, with a preview and an advance call).
 * A PUT replaces the section and must carry the {@code version} that was read.
 * Reading needs View and saving needs Edit in the Settings module.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
class SettingsController {

    private final SettingsAdminService service;

    SettingsController(SettingsAdminService service) {
        this.service = service;
    }

    @GetMapping
    Map<String, Object> all() {
        return service.page();
    }

    @GetMapping("/school")
    SchoolInfo school() {
        return service.school();
    }

    @PutMapping("/school")
    SchoolInfo saveSchool(@Valid @RequestBody SchoolInfo body) {
        return service.saveSchool(body);
    }

    @GetMapping("/attendance")
    AttendanceRules attendance() {
        return service.attendance();
    }

    @PutMapping("/attendance")
    AttendanceRules saveAttendance(@Valid @RequestBody AttendanceRules body) {
        return service.saveAttendance(body);
    }

    @GetMapping("/notifications")
    NotificationSettings notifications() {
        return service.notifications();
    }

    @PutMapping("/notifications")
    NotificationSettings saveNotifications(@Valid @RequestBody NotificationSettings body) {
        return service.saveNotifications(body);
    }

    @GetMapping("/numbering")
    NumberingSettings numbering() {
        return service.numbering();
    }

    @PutMapping("/numbering")
    NumberingSettings saveNumbering(@Valid @RequestBody NumberingSettings body) {
        return service.saveNumbering(body);
    }

    /**
     * What the next Staff IDs would look like. Optional parameters preview edits that are not saved yet; the
     * ones left out are taken from the saved settings. Nothing is used up.
     */
    @GetMapping("/numbering/preview")
    NumberingPreview previewNumbering(@RequestParam(required = false) String prefix,
            @RequestParam(required = false) String separator, @RequestParam(required = false) Integer length,
            @RequestParam(required = false) Boolean rolePrefix, @RequestParam(required = false) Boolean includeYear) {
        return service.previewNumbering(prefix, separator, length, rolePrefix, includeYear);
    }

    record AdvanceRequest(@NotNull @Min(1) Long nextNumber) {
    }

    /** Makes {@code nextNumber} the number the next person gets (for example after importing existing staff). Forward only. */
    @PostMapping("/numbering/advance")
    NumberingSettings advanceNumbering(@Valid @RequestBody AdvanceRequest body) {
        return service.advanceNumbering(body.nextNumber());
    }

    @GetMapping("/appearance")
    AppearanceSettings appearance() {
        return service.appearance();
    }

    @PutMapping("/appearance")
    AppearanceSettings saveAppearance(@Valid @RequestBody AppearanceSettings body) {
        return service.saveAppearance(body);
    }
}
