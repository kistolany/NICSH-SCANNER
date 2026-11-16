package kh.cmt.shared.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The signed-in caller, read from the access token. Take it as a controller parameter, or call
 * {@link #require()} inside a use case. Permissions are not in the token: they are looked up by
 * {@link #role()} and {@link #permVersion()} (step 3.2).
 *
 * @param userId      account id ({@code sub})
 * @param type        {@code app} (mobile app user), {@code web} (office user) or {@code job} (integration)
 * @param personId    the person, for app users and linked web users (may be null)
 * @param webUserId   the office user record, for web users (may be null)
 * @param role        role code
 * @param permVersion version of that role's permissions the token was issued under
 * @param deviceId    the bound phone, app users only (may be null)
 * @param schoolId    tenant
 * @param locale      km, en or ar
 * @param amr         how the user signed in: pwd, otp, sms
 */
public record CurrentUser(UUID userId, String type, UUID personId, UUID webUserId, String role, int permVersion,
        UUID deviceId, UUID schoolId, String locale, List<String> amr) {

    public static final String APP = "app";
    public static final String WEB = "web";
    public static final String JOB = "job";

    public static CurrentUser from(Jwt jwt) {
        List<String> amr = jwt.getClaimAsStringList("amr");
        Long permVersion = jwt.getClaim("perm_ver");
        return new CurrentUser(
                uuid(jwt.getSubject()),
                jwt.getClaimAsString("typ"),
                uuid(jwt.getClaimAsString("person_id")),
                uuid(jwt.getClaimAsString("web_user_id")),
                jwt.getClaimAsString("role"),
                permVersion == null ? 0 : permVersion.intValue(),
                uuid(jwt.getClaimAsString("device_id")),
                uuid(jwt.getClaimAsString("school_id")),
                jwt.getClaimAsString("locale"),
                amr == null ? List.of() : List.copyOf(amr));
    }

    /** The caller of the current request, if there is a signed-in user. */
    public static Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return Optional.of(from(token.getToken()));
        }
        return Optional.empty();
    }

    /** The caller of the current request; {@code UNAUTHORIZED} if nobody is signed in. */
    public static CurrentUser require() {
        return current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    public boolean isApp() {
        return APP.equals(type);
    }

    public boolean isWeb() {
        return WEB.equals(type);
    }

    public boolean usedSecondFactor() {
        return amr.contains("otp");
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
