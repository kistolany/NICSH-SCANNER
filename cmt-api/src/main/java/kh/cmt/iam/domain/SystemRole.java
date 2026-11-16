package kh.cmt.iam.domain;

import static kh.cmt.shared.security.PermissionModule.ANNOUNCEMENTS;
import static kh.cmt.shared.security.PermissionModule.APPROVALS;
import static kh.cmt.shared.security.PermissionModule.ATTENDANCE;
import static kh.cmt.shared.security.PermissionModule.DASHBOARD;
import static kh.cmt.shared.security.PermissionModule.LEAVE;
import static kh.cmt.shared.security.PermissionModule.LOG;
import static kh.cmt.shared.security.PermissionModule.PEOPLE;
import static kh.cmt.shared.security.PermissionModule.QR;
import static kh.cmt.shared.security.PermissionModule.REPORTS;
import static kh.cmt.shared.security.PermissionModule.SETTINGS;
import static kh.cmt.shared.security.PermissionModule.SHIFTS;
import static kh.cmt.shared.security.PermissionModule.USERS;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.SensitiveField;
import kh.cmt.shared.i18n.LocalizedText;

/** The roles every school starts with (from the design). They are locked: nobody can edit or delete them. */
public enum SystemRole {

    SUPER_ADMIN("super_admin",
            new LocalizedText("អ្នកគ្រប់គ្រងកំពូល", "Super Admin", null),
            new LocalizedText("គ្រប់យ៉ាង", "Everything", null),
            PermissionMatrix.everything(),
            EnumSet.allOf(SensitiveField.class)),

    HR("hr",
            new LocalizedText("ធនធានមនុស្ស", "HR", null),
            new LocalizedText("បុគ្គលិក វត្តមាន ច្បាប់ របាយការណ៍", "People, attendance, leave, reports", null),
            PermissionMatrix.fullAccessTo(DASHBOARD, APPROVALS, ATTENDANCE, PEOPLE, SHIFTS, LEAVE, ANNOUNCEMENTS, REPORTS),
            EnumSet.of(SensitiveField.SALARY_BANK, SensitiveField.NATIONAL_ID, SensitiveField.PHONE)),

    QR_MANAGER("qr_manager",
            new LocalizedText("អ្នកគ្រប់គ្រង QR", "QR Manager", null),
            new LocalizedText("បង្កើត និងបោះពុម្ព QR", "Create and print QR only", null),
            PermissionMatrix.only(EnumSet.of(PermissionAction.VIEW, PermissionAction.CREATE, PermissionAction.EDIT), QR),
            EnumSet.of(SensitiveField.NATIONAL_ID, SensitiveField.PHONE)),

    VIEWER("viewer",
            new LocalizedText("អ្នកមើល", "Viewer", null),
            new LocalizedText("មើលតែប៉ុណ្ណោះ", "Read-only", null),
            PermissionMatrix.only(Set.of(PermissionAction.VIEW), everyModuleExcept(USERS, SETTINGS)),
            EnumSet.of(SensitiveField.PHONE));

    /**
     * Sensitive fields of a new custom role (and of QR Manager), from the design: national ID and phone
     * numbers yes; salary, bank and ID scans no.
     */
    public static final Set<SensitiveField> CUSTOM_DEFAULT = Collections.unmodifiableSet(EnumSet.of(SensitiveField.NATIONAL_ID, SensitiveField.PHONE));

    private final String code;
    private final LocalizedText name;
    private final LocalizedText description;
    private final PermissionMatrix permissions;
    private final Set<SensitiveField> sensitive;

    SystemRole(String code, LocalizedText name, LocalizedText description, PermissionMatrix permissions, Set<SensitiveField> sensitive) {
        this.sensitive = sensitive;
        this.code = code;
        this.name = name;
        this.description = description;
        this.permissions = permissions;
    }

    public String code() {
        return code;
    }

    public LocalizedText displayName() {
        return name;
    }

    public LocalizedText description() {
        return description;
    }

    /** A copy: the definition itself never changes. */
    public PermissionMatrix permissions() {
        return new PermissionMatrix(permissions);
    }

    /** Super Admin has no limits; every other role approves leave up to 3 days and passes the rest to Super Admin. */
    public ApprovalLimits approvalLimits() {
        return this == SUPER_ADMIN ? ApprovalLimits.UNLIMITED : ApprovalLimits.standard();
    }

    /** Sign-in at any time from anywhere; sessions end after 2 h idle for Super Admin, 8 h for the others (design). */
    public LoginRules loginRules() {
        return LoginRules.open(this == SUPER_ADMIN ? 120 : 480);
    }

    /** Viewer is the role a new web user gets when none is chosen (design), until the school picks another. */
    public boolean isDefaultForNewUsers() {
        return this == VIEWER;
    }

    /** The sensitive data the role may see. */
    public Set<SensitiveField> sensitive() {
        return EnumSet.copyOf(sensitive);
    }

    private static PermissionModule[] everyModuleExcept(PermissionModule... excluded) {
        Set<PermissionModule> skip = Set.of(excluded);
        return java.util.Arrays.stream(PermissionModule.values()).filter(m -> !skip.contains(m))
                .toArray(PermissionModule[]::new);
    }
}
