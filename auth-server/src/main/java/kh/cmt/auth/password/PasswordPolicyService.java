package kh.cmt.auth.password;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The school's rules for new passwords (table {@code password_policy}). Violations are returned as stable
 * codes, never sentences: MIN_LENGTH, TOO_LONG, UPPERCASE, LOWERCASE, DIGIT, SYMBOL, CONTAINS_USERNAME.
 */
@Service
public class PasswordPolicyService {

    static final int MAX_LENGTH = 128;

    public record Policy(int minLength, boolean requireUpper, boolean requireLower, boolean requireDigit,
            boolean requireSymbol) {
    }

    private final JdbcClient jdbc;
    private final UUID schoolId;

    public PasswordPolicyService(JdbcClient jdbc, @Value("${cmt.school.default-id}") UUID schoolId) {
        this.jdbc = jdbc;
        this.schoolId = schoolId;
    }

    public Policy current() {
        return jdbc.sql("""
                select min_length, require_upper, require_lower, require_digit, require_symbol
                from password_policy where school_id = :school
                """)
                .param("school", schoolId)
                .query((rs, n) -> new Policy(rs.getInt("min_length"), rs.getBoolean("require_upper"),
                        rs.getBoolean("require_lower"), rs.getBoolean("require_digit"), rs.getBoolean("require_symbol")))
                .optional()
                .orElse(new Policy(8, true, false, true, false));
    }

    /** Empty list = the password is acceptable. */
    public List<String> violations(String username, String password) {
        Policy policy = current();
        List<String> found = new ArrayList<>();
        if (password.length() < policy.minLength()) {
            found.add("MIN_LENGTH");
        }
        if (password.length() > MAX_LENGTH) {
            found.add("TOO_LONG");
        }
        if (policy.requireUpper() && password.chars().noneMatch(Character::isUpperCase)) {
            found.add("UPPERCASE");
        }
        if (policy.requireLower() && password.chars().noneMatch(Character::isLowerCase)) {
            found.add("LOWERCASE");
        }
        if (policy.requireDigit() && password.chars().noneMatch(Character::isDigit)) {
            found.add("DIGIT");
        }
        if (policy.requireSymbol() && password.chars().allMatch(Character::isLetterOrDigit)) {
            found.add("SYMBOL");
        }
        if (username != null && !username.isBlank()
                && password.toLowerCase().contains(username.toLowerCase())) {
            found.add("CONTAINS_USERNAME");
        }
        return found;
    }
}
