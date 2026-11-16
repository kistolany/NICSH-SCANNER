package kh.cmt.iam.application;

import java.util.UUID;

/** Published when a role's permissions changed or the role was deleted; the permission cache listens. */
record RoleChanged(UUID schoolId, String roleCode) {
}
