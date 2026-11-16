package kh.cmt.iam.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One saved version of a role (Roles page, "Change history").
 *
 * @param change        CREATED, RENAMED, PERMISSIONS, SCOPE, SENSITIVE_FIELDS, APPROVAL_LIMITS, LOGIN_RULES, TWO_FACTOR
 *                      or RESTORED
 * @param restoredFrom  for RESTORED: the version that was brought back
 * @param changedBy     account id of who saved it; null = the system (system roles, roles from before the history)
 * @param changedByName the name of that web user, when known
 * @param differences   the parts that differ from the version before: name, permissions, scope, sensitiveFields,
 *                      approvalLimits, loginRules
 * @param snapshot      the whole role as it was saved
 * @param restorable    may be restored (custom roles, not the current version)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleVersionView(int version, String change, Integer restoredFrom, UUID changedBy, String changedByName,
        Instant changedAt, List<String> differences, RoleSnapshot snapshot, boolean restorable) {
}
