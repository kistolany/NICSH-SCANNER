package kh.cmt.auth.login;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kh.cmt.auth.account.AccountRepository.AccountClaims;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;

/**
 * When and from where the users of a role may sign in (table {@code role_login_rule}, kept by the iam module).
 * Web accounts only: app accounts never have these rules.
 * <ul>
 *   <li>at {@code /oauth2/authorize} (the browser is here, so its address is known): hours, days and address</li>
 *   <li>at every token issuance (login and refresh; the caller is the BFF, not the browser): hours and days</li>
 *   <li>the BFF checks hours, days and the browser's address on every request, from the token claims</li>
 * </ul>
 * Hours are school time (the {@link Clock}'s zone). The day is the calendar day of the moment of the check, also for
 * hours that run past midnight.
 */
@Service
public class LoginRuleService {

    /** Why a sign-in is refused. Shown on the denied page and in the token error. */
    public enum Refusal { ACCESS_EXPIRED, DAY_NOT_ALLOWED, OUTSIDE_HOURS, IP_NOT_ALLOWED }

    /**
     * A role's rules; null fields (or an empty list) mean no restriction.
     *
     * @param to end of the allowed hours (exclusive); before {@code from} = the hours run past midnight
     */
    public record Rules(Integer sessionTimeoutMinutes, LocalTime from, LocalTime to, List<Integer> days, List<String> ips) {

        /** The first rule broken at {@code now}, from {@code ip} (null = the address is not checked). */
        public Optional<Refusal> refusal(ZonedDateTime now, String ip) {
            if (days != null && !days.isEmpty() && !days.contains(now.getDayOfWeek().getValue())) {
                return Optional.of(Refusal.DAY_NOT_ALLOWED);
            }
            if (from != null && to != null && !withinHours(now.toLocalTime())) {
                return Optional.of(Refusal.OUTSIDE_HOURS);
            }
            if (ip != null && ips != null && !ips.isEmpty() && ips.stream().noneMatch(range -> matches(range, ip))) {
                return Optional.of(Refusal.IP_NOT_ALLOWED);
            }
            return Optional.empty();
        }

        private boolean withinHours(LocalTime time) {
            return from.isBefore(to)
                    ? !time.isBefore(from) && time.isBefore(to)
                    : !time.isBefore(from) || time.isBefore(to);   // past midnight, e.g. 22:00 to 06:00
        }

        private static boolean matches(String range, String ip) {
            try {
                return new IpAddressMatcher(range).matches(ip);
            } catch (IllegalArgumentException notAnAddress) {
                return false;
            }
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public LoginRuleService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The rules of the account's role; empty for app accounts and roles without a row. */
    public Optional<Rules> rulesFor(AccountClaims account) {
        if (!"web".equals(account.type()) || account.role() == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select session_timeout_minutes, allowed_from, allowed_to, allowed_days, allowed_ips
                from role_login_rule where school_id = :school and role = :role
                """)
                .param("school", account.schoolId()).param("role", account.role())
                .query((rs, n) -> new Rules(
                        rs.getObject("session_timeout_minutes", Integer.class),
                        rs.getObject("allowed_from", LocalTime.class),
                        rs.getObject("allowed_to", LocalTime.class),
                        numbers(rs.getArray("allowed_days")),
                        texts(rs.getArray("allowed_ips"))))
                .optional();
    }

    /**
     * The first rule the account breaks now, from {@code ip} (null = the address is not checked): its access ended
     * (web users with an end date), then the role's days, hours and addresses.
     */
    public Optional<Refusal> check(AccountClaims account, String ip) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (account.isExpiredOn(now.toLocalDate())) {
            return Optional.of(Refusal.ACCESS_EXPIRED);
        }
        return rulesFor(account).flatMap(rules -> rules.refusal(now, ip));
    }

    private static List<Integer> numbers(Array array) throws SQLException {
        List<Integer> values = new ArrayList<>();
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                values.add(((Number) value).intValue());
            }
        }
        return values;
    }

    private static List<String> texts(Array array) throws SQLException {
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                values.add((String) value);
            }
        }
        return values;
    }
}
