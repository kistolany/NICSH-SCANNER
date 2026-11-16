package kh.cmt.iam.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kh.cmt.iam.api.ApprovalRequest;
import kh.cmt.iam.api.ApprovalRouting;
import kh.cmt.iam.domain.ApprovalLimits;
import kh.cmt.iam.domain.SystemRole;
import kh.cmt.iam.infrastructure.RoleAccessStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Approval limits of the roles, applied to requests (see {@link ApprovalRouting}). */
@Service
@Transactional(readOnly = true)
public class ApprovalRoutingService implements ApprovalRouting {

    private static final String TOP = SystemRole.SUPER_ADMIN.code();

    private final RoleAccessStore access;
    private final Clock clock;

    ApprovalRoutingService(RoleAccessStore access, Clock clock) {
        this.access = access;
        this.clock = clock;
    }

    @Override
    public String inboxFor(UUID schoolId, String startRole, ApprovalRequest request) {
        Map<String, ApprovalLimits> limits = access.activeApprovalLimitsByCode(schoolId);
        Set<String> seen = new HashSet<>();
        String role = startRole;
        // nobody is the approver yet, so "own request" does not apply here; it is checked when somebody decides
        while (role != null && limits.containsKey(role) && seen.add(role)) {
            ApprovalLimits rules = limits.get(role);
            if (problem(rules, request, false) == null) {
                return role;
            }
            role = rules.escalateTo();
        }
        return TOP;   // the chain ended (no escalation role, a deleted one, or a circle): Super Admin decides
    }

    @Override
    public Decision check(UUID schoolId, String approverRole, UUID approverPersonId, ApprovalRequest request) {
        Map<String, ApprovalLimits> limits = access.activeApprovalLimitsByCode(schoolId);
        ApprovalLimits rules = limits.get(approverRole);
        if (rules == null) {
            return new Decision(false, TOP, null);   // a role that no longer exists approves nothing
        }
        boolean own = approverPersonId != null && approverPersonId.equals(request.requesterId());
        Decision.Reason problem = problem(rules, request, own);
        if (problem == null) {
            return Decision.allow();
        }
        // where it goes: the first role up the chain that covers it (own requests are not the next role's own)
        String next = rules.escalateTo() == null || !limits.containsKey(rules.escalateTo()) ? TOP
                : inboxFor(schoolId, rules.escalateTo(), request);
        if (next.equals(approverRole)) {
            next = TOP;
        }
        return new Decision(false, next, problem);
    }

    @Override
    public String appAdminRole(UUID schoolId, UUID personId) {
        return personId == null ? null : access.roleOfPerson(schoolId, personId).orElse(null);
    }

    private Decision.Reason problem(ApprovalLimits rules, ApprovalRequest request, boolean ownRequest) {
        if (ownRequest && !rules.ownRequests()) {
            return Decision.Reason.OWN_REQUEST;
        }
        return switch (request.kind()) {
            case LEAVE -> rules.leaveMaxDays() != null && request.leaveDays() > rules.leaveMaxDays()
                    ? Decision.Reason.LEAVE_TOO_LONG : null;
            case CORRECTION -> !rules.oldCorrections() && request.day() != null
                    && request.day().isBefore(LocalDate.now(clock).minusDays(ApprovalLimits.CORRECTION_DAYS))
                    ? Decision.Reason.CORRECTION_TOO_OLD : null;
            case OTHER -> null;
        };
    }
}
