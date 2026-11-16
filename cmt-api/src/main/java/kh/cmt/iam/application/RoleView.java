package kh.cmt.iam.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kh.cmt.iam.domain.ApprovalLimits;
import kh.cmt.iam.domain.LoginRules;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.ScopeType;
import kh.cmt.shared.security.SensitiveField;

/**
 * A role as the apps see it.
 * <ul>
 *   <li>{@code code}: stable identity, the value of the token {@code role} claim</li>
 *   <li>{@code system}: true = locked (read-only), false = custom</li>
 *   <li>{@code permissions}: module key -> granted action keys; a module that is not listed grants nothing</li>
 *   <li>{@code scope}: whose records the role's users see (see {@link ScopeView})</li>
 *   <li>{@code sensitiveFields}: which personal data they see; what they may not see shows as "••••"</li>
 *   <li>{@code approvalLimits}: what they may approve and where the rest goes</li>
 *   <li>{@code loginRules}: two-step verification, session timeout, allowed hours / days / IP addresses</li>
 *   <li>{@code permVersion}: goes up when the permissions or the scope change (access is re-read on the next request)</li>
 *   <li>{@code version}: revision to send back when editing, so a change by somebody else is noticed</li>
 *   <li>{@code users}: number of active web users with the role</li>
 *   <li>{@code sees}: how many active people the role's record scope covers ("Sees: N people"); left out for own
 *       department / own team, which depend on the user</li>
 *   <li>{@code defaultRole}: the role a new web user gets when none is chosen</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleView(UUID id, String code, LocalizedName name, LocalizedName description, boolean system,
        int permVersion, long version, Map<String, List<String>> permissions, ScopeView scope, SensitiveFieldsView sensitiveFields, ApprovalLimits approvalLimits,
        LoginRules loginRules, long users, Long sees, boolean defaultRole, Instant updatedAt) {

    /**
     * Record scope: {@code type} ALL, SELECTED_DEPARTMENTS, OWN_DEPARTMENT or OWN_TEAM; {@code departmentIds} only for
     * SELECTED_DEPARTMENTS (empty otherwise).
     */
    public record ScopeView(ScopeType type, List<UUID> departmentIds) {
    }

    /** Salary and bank, national ID and documents, phone numbers, national ID scans. */
    public record SensitiveFieldsView(boolean salaryBank, boolean nationalId, boolean phone, boolean idScans) {

        public static SensitiveFieldsView of(Set<SensitiveField> visible) {
            return new SensitiveFieldsView(visible.contains(SensitiveField.SALARY_BANK), visible.contains(SensitiveField.NATIONAL_ID),
                    visible.contains(SensitiveField.PHONE), visible.contains(SensitiveField.ID_SCANS));
        }
    }
}
