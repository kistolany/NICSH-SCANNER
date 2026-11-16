package kh.cmt.iam.domain;

import static kh.cmt.shared.security.PermissionAction.APPROVE;
import static kh.cmt.shared.security.PermissionAction.CREATE;
import static kh.cmt.shared.security.PermissionAction.EDIT;
import static kh.cmt.shared.security.PermissionAction.VIEW;
import static kh.cmt.shared.security.PermissionModule.ANNOUNCEMENTS;
import static kh.cmt.shared.security.PermissionModule.APPROVALS;
import static kh.cmt.shared.security.PermissionModule.ATTENDANCE;
import static kh.cmt.shared.security.PermissionModule.DASHBOARD;
import static kh.cmt.shared.security.PermissionModule.PEOPLE;

/**
 * What a person with the app role {@code admin} may do in the mobile admin app. Their access is not a web role:
 * it is this fixed set, taken from the screens of the admin app (admin home, approvals inbox with approve / reject /
 * bulk / undo, staff list and detail, sending announcements).
 * Only the approvals endpoints under {@code /api/v1/admin} are open to them (see the security configuration).
 */
public final class AppAdminAccess {

    private static final PermissionMatrix MATRIX = build();

    private AppAdminAccess() {
    }

    public static PermissionMatrix matrix() {
        return new PermissionMatrix(MATRIX);
    }

    private static PermissionMatrix build() {
        PermissionMatrix m = new PermissionMatrix();
        m.grant(DASHBOARD, VIEW);
        m.grant(APPROVALS, VIEW);
        m.grant(APPROVALS, EDIT);
        m.grant(APPROVALS, APPROVE);
        m.grant(PEOPLE, VIEW);
        m.grant(ATTENDANCE, VIEW);
        m.grant(ANNOUNCEMENTS, VIEW);
        m.grant(ANNOUNCEMENTS, CREATE);
        return m;
    }
}
