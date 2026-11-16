package kh.cmt.org.application;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.files.api.FilePurpose;
import kh.cmt.files.api.Files;
import kh.cmt.org.api.AppearanceSettings;
import kh.cmt.org.api.AttendanceRules;
import kh.cmt.org.api.Language;
import kh.cmt.org.api.MissingCheckOutRule;
import kh.cmt.org.api.NotificationSettings;
import kh.cmt.org.api.PublicSettings;
import kh.cmt.org.api.SchoolInfo;
import kh.cmt.org.api.SchoolSettingsQueries;
import kh.cmt.org.api.Theme;
import kh.cmt.org.domain.SchoolSettings;
import kh.cmt.org.infrastructure.SchoolSettingsRepository;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads and saves the five sections of the school settings.
 * <p>
 * Saving replaces the whole section. Every section carries a {@code version}: the revision the user was looking
 * at. If someone saved the same section in the meantime the save is refused ({@code VERSION_CONFLICT}); sections
 * do not interfere with each other. Who may save is not decided here yet (permission matrix, step 3.2).
 */
@Service
@Transactional
public class SettingsService implements SchoolSettingsQueries {

    private final SchoolSettingsRepository repository;
    private final SchoolContext schoolContext;
    private final Files files;
    private final TransactionTemplate newTransaction;

    SettingsService(SchoolSettingsRepository repository, SchoolContext schoolContext, Files files, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.schoolContext = schoolContext;
        this.files = files;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ reading

    @Override
    public SchoolInfo school() {
        return toSchool(current());
    }

    @Override
    public AttendanceRules attendanceRules() {
        return toAttendance(current());
    }

    @Override
    public NotificationSettings notifications() {
        return toNotifications(current());
    }

    @Override
    public AppearanceSettings appearance() {
        return toAppearance(current());
    }

    @Override
    public ZoneId zone() {
        return ZoneId.of(current().getTimezone());
    }

    public PublicSettings publicSettings() {
        SchoolSettings s = current();
        return new PublicSettings(toSchool(s), toAttendance(s), toNotifications(s), toAppearance(s));
    }

    // ------------------------------------------------------------------ saving

    public SchoolInfo saveSchool(SchoolInfo in) {
        SchoolSettings s = current();
        checkVersion(in.version(), s.getSchoolRev());
        List<Map<String, String>> errors = new java.util.ArrayList<>();
        if (in.name().en() == null || in.name().en().isBlank()) {
            errors.add(Map.of("field", "name.en", "rule", "NotBlank"));
        }
        String days = workingDays(in.workingDays(), errors);
        try {
            ZoneId.of(in.timezone());
        } catch (DateTimeException e) {
            errors.add(Map.of("field", "timezone", "rule", "Pattern"));
        }
        String phone = blankToNull(in.supportPhone());
        if (phone != null && !phone.matches("[+0-9 ()-]{6,30}")) {
            errors.add(Map.of("field", "supportPhone", "rule", "Pattern"));
        }
        String email = blankToNull(in.supportEmail());
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            errors.add(Map.of("field", "supportEmail", "rule", "Email"));
        }
        failIfAny(errors);

        s.setSupportPhone(phone);
        s.setSupportEmail(email == null ? null : email.toLowerCase());
        s.setNameEn(in.name().en().trim());
        s.setNameKm(blankToNull(in.name().km()));
        s.setNameAr(blankToNull(in.name().ar()));
        s.setAddress(blankToNull(in.address()));
        s.setWorkingDays(days);
        s.setTimezone(in.timezone());
        UUID oldLogo = s.getLogoFileId();
        UUID newLogo = in.logoFileId();
        if (newLogo != null && !newLogo.equals(oldLogo)) {
            try {
                files.attach(newLogo, FilePurpose.LOGO);   // an uploaded, confirmed image no other record uses
            } catch (ApiException notUsable) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(Map.of("field", "logoFileId",
                        "rule", notUsable.code() == ErrorCode.FILE_IN_USE ? "InUse" : "Exists")));
            }
        }
        s.setLogoFileId(newLogo);
        s.bumpSchoolRev();
        SchoolInfo saved;
        try {
            saved = toSchool(repository.saveAndFlush(s));
        } catch (DataIntegrityViolationException unknownFile) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR)
                    .with("errors", List.of(Map.of("field", "logoFileId", "rule", "Exists")));
        }
        if (oldLogo != null && !oldLogo.equals(newLogo)) {
            files.discard(oldLogo);   // the old logo is no longer used: removed after the commit
        }
        return saved;
    }

    public AttendanceRules saveAttendance(AttendanceRules in) {
        SchoolSettings s = current();
        checkVersion(in.version(), s.getAttendanceRev());
        s.setDefaultGraceMin(in.defaultGraceMinutes());
        s.setEarlyCheckoutNeedsReason(in.earlyCheckOutNeedsReason());
        s.setManualCheckinAllowed(in.manualCheckInAllowed());
        s.setOfflineCheckinAllowed(in.offlineCheckInAllowed());
        s.setMissingCheckoutRule(in.missingCheckOutRule().name());
        s.bumpAttendanceRev();
        return toAttendance(repository.saveAndFlush(s));
    }

    public NotificationSettings saveNotifications(NotificationSettings in) {
        SchoolSettings s = current();
        checkVersion(in.version(), s.getNotificationsRev());
        s.setCheckinReminder(in.checkInReminder());
        s.setCheckinReminderMin(in.checkInReminderMin());
        s.setCheckoutReminder(in.checkOutReminder());
        s.setCheckoutReminderMin(in.checkOutReminderMin());
        s.setShift2Reminder(in.shift2Reminder());
        s.setShift2ReminderMin(in.shift2ReminderMin());
        s.bumpNotificationsRev();
        return toNotifications(repository.saveAndFlush(s));
    }

    public AppearanceSettings saveAppearance(AppearanceSettings in) {
        SchoolSettings s = current();
        checkVersion(in.version(), s.getAppearanceRev());
        s.setDefaultLanguage(in.defaultLanguage().code());
        s.setDefaultTheme(in.defaultTheme().code());
        s.bumpAppearanceRev();
        return toAppearance(repository.saveAndFlush(s));
    }

    // ------------------------------------------------------------------ helpers

    /** The row of the caller school (also for the numbering service). */
    SchoolSettings currentRow() {
        return current();
    }

    /**
     * The caller school row; a school that has none yet gets one with the defaults, made in a transaction of its own so
     * that callers in read-only transactions (lists, printing…) can read settings too.
     */
    private SchoolSettings current() {
        var school = schoolContext.currentSchoolId();
        return repository.findBySchoolId(school).orElseGet(() -> {
            try {
                newTransaction.executeWithoutResult(s -> repository.saveAndFlush(SchoolSettings.withDefaults("School")));
            } catch (DataIntegrityViolationException createdInParallel) {
                // another request made it first
            }
            return repository.findBySchoolId(school).orElseThrow();
        });
    }

    static void checkVersion(Long sent, long current) {
        if (sent == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR)
                    .with("errors", List.of(Map.of("field", "version", "rule", "NotNull")));
        }
        if (sent != current) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private static String workingDays(List<Integer> days, List<Map<String, String>> errors) {
        if (days.isEmpty() || days.stream().anyMatch(d -> d == null || d < 1 || d > 7)
                || days.stream().distinct().count() != days.size()) {
            errors.add(Map.of("field", "workingDays", "rule", "Range"));
            return null;
        }
        return days.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static void failIfAny(List<Map<String, String>> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static SchoolInfo toSchool(SchoolSettings s) {
        List<Integer> days = Arrays.stream(s.getWorkingDays().split(",")).map(Integer::valueOf)
                .collect(Collectors.toCollection(TreeSet::new)).stream().toList();
        return new SchoolInfo(new LocalizedName(s.getNameKm(), s.getNameEn(), s.getNameAr()), s.getAddress(), days,
                s.getTimezone(), s.getLogoFileId(), s.getSupportPhone(), s.getSupportEmail(), s.getSchoolRev());
    }

    private static AttendanceRules toAttendance(SchoolSettings s) {
        return new AttendanceRules(s.getDefaultGraceMin(), s.isEarlyCheckoutNeedsReason(), s.isManualCheckinAllowed(),
                s.isOfflineCheckinAllowed(), MissingCheckOutRule.valueOf(s.getMissingCheckoutRule()), s.getAttendanceRev());
    }

    private static NotificationSettings toNotifications(SchoolSettings s) {
        return new NotificationSettings(s.isCheckinReminder(), s.getCheckinReminderMin(), s.isCheckoutReminder(),
                s.getCheckoutReminderMin(), s.isShift2Reminder(),
                s.getShift2ReminderMin(), s.getNotificationsRev());
    }

    private static AppearanceSettings toAppearance(SchoolSettings s) {
        return new AppearanceSettings(Language.fromCode(s.getDefaultLanguage()), Theme.fromCode(s.getDefaultTheme()),
                s.getAppearanceRev());
    }
}
