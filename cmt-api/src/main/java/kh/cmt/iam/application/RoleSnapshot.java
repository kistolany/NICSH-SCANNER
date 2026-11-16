package kh.cmt.iam.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kh.cmt.iam.domain.ApprovalLimits;
import kh.cmt.iam.domain.LoginRules;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * Everything a role defines, as saved in its change history (and restored from it). The same shapes as in
 * {@link RoleView}.
 */
public record RoleSnapshot(LocalizedName name, LocalizedName description, Map<String, List<String>> permissions,
        RoleView.ScopeView scope, RoleView.SensitiveFieldsView sensitiveFields, ApprovalLimits approvalLimits,
        LoginRules loginRules) {

    /** The parts that differ from {@code before}: name, permissions, scope, sensitiveFields, approvalLimits, loginRules. */
    public List<String> differencesFrom(RoleSnapshot before) {
        List<String> parts = new ArrayList<>();
        if (before == null) {
            return parts;
        }
        if (!Objects.equals(name, before.name) || !Objects.equals(description, before.description)) {
            parts.add("name");
        }
        if (!Objects.equals(permissions, before.permissions)) {
            parts.add("permissions");
        }
        if (!Objects.equals(scope, before.scope)) {
            parts.add("scope");
        }
        if (!Objects.equals(sensitiveFields, before.sensitiveFields)) {
            parts.add("sensitiveFields");
        }
        if (!Objects.equals(approvalLimits, before.approvalLimits)) {
            parts.add("approvalLimits");
        }
        if (!Objects.equals(loginRules, before.loginRules)) {
            parts.add("loginRules");
        }
        return parts;
    }
}
