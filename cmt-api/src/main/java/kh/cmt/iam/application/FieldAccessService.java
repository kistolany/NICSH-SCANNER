package kh.cmt.iam.application;

import java.util.EnumSet;
import java.util.Set;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.FieldAccess;
import kh.cmt.shared.security.SensitiveField;
import org.springframework.stereotype.Service;

/**
 * Which sensitive data the caller may see (see {@link FieldAccess} for who gets what). Office users get the settings
 * of their role from {@link RoleAccessCache}, so a change applies on the next request. Fails closed: a role that
 * does not exist sees none.
 */
@Service
public class FieldAccessService implements FieldAccess {

    private static final String APP_ADMIN = "admin";

    private final RoleAccessCache roles;

    FieldAccessService(RoleAccessCache roles) {
        this.roles = roles;
    }

    @Override
    public Set<SensitiveField> visibleFields(CurrentUser user) {
        if (user.isWeb()) {
            return roles.of(user.schoolId(), user.role())
                    .map(stored -> stored.sensitive().isEmpty() ? EnumSet.noneOf(SensitiveField.class) : EnumSet.copyOf(stored.sensitive()))
                    .orElseGet(() -> EnumSet.noneOf(SensitiveField.class));
        }
        if (user.isApp()) {
            // the admin app shows other people: phone numbers only. Staff and teachers only ever get their own records.
            return APP_ADMIN.equals(user.role()) ? EnumSet.of(SensitiveField.PHONE) : EnumSet.allOf(SensitiveField.class);
        }
        if (CurrentUser.JOB.equals(user.type())) {
            return EnumSet.allOf(SensitiveField.class);   // integrations, e.g. the payroll system
        }
        return EnumSet.noneOf(SensitiveField.class);
    }
}
