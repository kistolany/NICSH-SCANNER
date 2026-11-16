package kh.cmt.shared.security;

import java.util.Set;

/**
 * Which groups of sensitive data the caller may see. Implemented by the iam module:
 * <ul>
 *   <li>office users: the sensitive-field settings of their role</li>
 *   <li>app users with role {@code admin} (the admin app): phone numbers only</li>
 *   <li>other app users: everything (they only ever get their own records, see {@link RecordScopes})</li>
 *   <li>integrations and work without a caller (scheduled jobs, payroll export): everything</li>
 * </ul>
 */
public interface FieldAccess {

    Set<SensitiveField> visibleFields(CurrentUser user);
}
