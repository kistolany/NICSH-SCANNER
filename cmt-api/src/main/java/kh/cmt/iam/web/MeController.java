package kh.cmt.iam.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.iam.application.PermissionService;
import kh.cmt.iam.application.WebUserService;
import kh.cmt.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who am I, as the API sees the caller. The apps call it right after login (and the web, through the BFF)
 * to learn the role, language and what the token says about them. The Admin Web also gets {@code permissions}
 * (module key -> action keys) to build its menu and buttons.
 */
@RestController
@RequestMapping("/api/v1")
class MeController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MeResponse(UUID userId, String type, UUID personId, UUID webUserId, String role, int permVersion,
            UUID deviceId, UUID schoolId, String locale, List<String> amr, String name, String username,
            Map<String, List<String>> permissions) {

        static MeResponse of(CurrentUser user, String name, String username, Map<String, List<String>> permissions) {
            return new MeResponse(user.userId(), user.type(), user.personId(), user.webUserId(), user.role(),
                    user.permVersion(), user.deviceId(), user.schoolId(), user.locale(), user.amr(), name, username,
                    permissions);
        }
    }

    private final PermissionService permissions;
    private final WebUserService webUsers;

    MeController(PermissionService permissions, WebUserService webUsers) {
        this.permissions = permissions;
        this.webUsers = webUsers;
    }

    /** Mobile app (Staff, Teacher, Admin). */
    @GetMapping("/app/me")
    MeResponse appMe(CurrentUser user) {
        return MeResponse.of(user, null, null, null);
    }

    /** Admin web (office users): also their name and username (header) and permissions. */
    @GetMapping("/admin/me")
    MeResponse adminMe(CurrentUser user) {
        var own = webUsers.own(user.webUserId());
        return MeResponse.of(user, own.map(u -> u.getName()).orElse(null), own.map(u -> u.getUsername()).orElse(null),
                permissions.grantsOf(user));
    }
}
