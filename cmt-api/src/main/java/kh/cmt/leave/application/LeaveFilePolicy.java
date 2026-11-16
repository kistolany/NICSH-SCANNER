package kh.cmt.leave.application;

import java.util.UUID;
import kh.cmt.files.api.FileReadPolicy;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionGuard;
import kh.cmt.shared.security.PermissionModule;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who may download a leave request's attachment (a doctor's note): the person who sent it, and approvers (Approvals
 * view) who may see that person.
 */
@Component
@RequiredArgsConstructor
class LeaveFilePolicy implements FileReadPolicy {

    private final JdbcClient jdbc;
    private final PermissionGuard guard;
    private final PeopleQueries people;

    @Override
    public Decision decide(UUID fileId, CurrentUser caller) {
        UUID person = jdbc.sql("select person_id from leave_request where attachment_file_id = :f").param("f", fileId)
                .query(UUID.class).optional().orElse(null);
        if (person == null) {
            return Decision.ABSTAIN;
        }
        if (person.equals(caller.personId())) {
            return Decision.ALLOW;
        }
        return guard.allows(caller, PermissionModule.APPROVALS, PermissionAction.VIEW) && people.visible(person).isPresent()
                ? Decision.ALLOW : Decision.DENY;
    }
}
