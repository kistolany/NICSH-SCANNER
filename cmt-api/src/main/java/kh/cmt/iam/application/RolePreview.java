package kh.cmt.iam.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * "View as this role" (Roles page): what a user of the role may do (permission table) and see (record scope,
 * sensitive fields). Always {@code readOnly}: the Admin Web shows its pages as that role would, without saving.
 */
public record RolePreview(UUID roleId, String code, LocalizedName name, Map<String, List<String>> permissions,
        RoleView.ScopeView scope, RoleView.SensitiveFieldsView sensitiveFields, boolean readOnly) {
}
