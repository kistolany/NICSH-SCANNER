package kh.cmt.iam.application;

import java.util.List;
import java.util.UUID;
import kh.cmt.iam.domain.Role;
import kh.cmt.iam.domain.SystemRole;
import kh.cmt.iam.infrastructure.AuthLoginRuleSync;
import kh.cmt.iam.infrastructure.RoleAccessStore;
import kh.cmt.iam.infrastructure.RolePermissionStore;
import kh.cmt.iam.infrastructure.RoleRepository;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.i18n.LocalizedText;
import kh.cmt.shared.security.ScopeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the four system roles (Super Admin, HR, QR Manager, Viewer) for a school that has none yet,
 * with Viewer as the default role for new web users. Runs in its own transaction: if two requests try at the same
 * moment, the database unique index lets one win and the other simply fails and is ignored by the caller.
 */
@Component
class RoleSeeder {

    private final RoleRepository roles;
    private final RolePermissionStore permissions;
    private final RoleAccessStore access;
    private final AuthLoginRuleSync loginSync;
    private final RoleHistory history;

    RoleSeeder(RoleRepository roles, RolePermissionStore permissions, RoleAccessStore access, AuthLoginRuleSync loginSync,
            RoleHistory history) {
        this.access = access;
        this.loginSync = loginSync;
        this.roles = roles;
        this.permissions = permissions;
        this.history = history;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seed(UUID schoolId) {
        if (roles.existsBySchoolIdAndSystemTrue(schoolId)) {
            return;
        }
        for (SystemRole definition : SystemRole.values()) {
            Role role = new Role();
            role.setCode(definition.code());
            role.setName(definition.displayName());
            role.setDescription(definition.description());
            role.setSystem(true);
            role.setDefaultRole(definition.isDefaultForNewUsers());
            role = roles.saveAndFlush(role);
            permissions.replace(schoolId, role.getId(), definition.permissions());
            access.createSettings(schoolId, role.getId(), definition.sensitive(), definition.approvalLimits(), definition.loginRules());
            loginSync.copy(schoolId, definition.code(), definition.loginRules());
            history.recordSystemRole(role, new RoleSnapshot(name(definition.displayName()), name(definition.description()),
                    definition.permissions().toApi(), new RoleView.ScopeView(ScopeType.ALL, List.of()),
                    RoleView.SensitiveFieldsView.of(definition.sensitive()), definition.approvalLimits(), definition.loginRules()));
        }
    }

    private static LocalizedName name(LocalizedText text) {
        return new LocalizedName(text.getKm(), text.getEn(), text.getAr());
    }
}
