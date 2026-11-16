/**
 * What other modules may use from the iam module: {@link kh.cmt.iam.api.ApprovalRouting} (approval limits) and
 * {@link kh.cmt.iam.api.AccountNames} (who did something, by name).
 * Permissions, record scope and sensitive fields are reached through the shared kernel ({@code kh.cmt.shared.security}).
 */
@NamedInterface("api")
package kh.cmt.iam.api;

import org.springframework.modulith.NamedInterface;
