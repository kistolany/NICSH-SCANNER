package kh.cmt.iam.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalTime;
import java.util.List;

/**
 * When and from where the users of a role may sign in (Roles page, "Security"). Checked by the auth server at
 * login and token refresh, and by the BFF on every request.
 *
 * @param twoFactorRequired     the role must use two-step verification
 * @param sessionTimeoutMinutes idle time before the Admin Web session ends
 * @param allowedFrom           first minute of the day sign-in is allowed (school time); null with allowedTo = any time
 * @param allowedTo             end of the allowed hours (exclusive); before allowedFrom = the hours run past midnight
 * @param allowedDays           ISO weekdays sign-in is allowed (Monday = 1 ... Sunday = 7)
 * @param allowedIps            IP addresses or ranges (CIDR) sign-in is allowed from; empty = anywhere
 */
public record LoginRules(boolean twoFactorRequired, int sessionTimeoutMinutes,
        @JsonFormat(pattern = "HH:mm") LocalTime allowedFrom,
        @JsonFormat(pattern = "HH:mm") LocalTime allowedTo,
        List<Integer> allowedDays, List<String> allowedIps) {

    public static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

    public LoginRules {
        allowedDays = allowedDays == null ? EVERY_DAY : List.copyOf(allowedDays);
        allowedIps = allowedIps == null ? List.of() : List.copyOf(allowedIps);
    }

    public static LoginRules open(int sessionTimeoutMinutes) {
        return new LoginRules(false, sessionTimeoutMinutes, null, null, EVERY_DAY, List.of());
    }

    public LoginRules withTwoFactor(boolean required) {
        return new LoginRules(required, sessionTimeoutMinutes, allowedFrom, allowedTo, allowedDays, allowedIps);
    }
}
