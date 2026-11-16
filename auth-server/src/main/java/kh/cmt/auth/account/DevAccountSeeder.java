package kh.cmt.auth.account;

import java.time.Clock;
import java.util.UUID;
import kh.cmt.auth.AuthProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Dev profile only: creates the logins {@code test.staff} (app) and {@code test.admin} (web) if missing. */
@Component
@Profile("dev")
@Order(3)
class DevAccountSeeder implements ApplicationRunner {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuthProperties props;
    private final UUID schoolId;

    DevAccountSeeder(JdbcClient jdbc, PasswordEncoder passwordEncoder, Clock clock, AuthProperties props,
            @Value("${cmt.school.default-id}") UUID schoolId) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.props = props;
        this.schoolId = schoolId;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.dev() == null || !props.dev().seedTestAccounts()) {
            return;
        }
        seed("test.staff", "app", "staff");
        seed("test.admin", "web", "super_admin");
    }

    private void seed(String username, String type, String role) {
        boolean exists = jdbc.sql("select count(*) from account where lower(username) = lower(:u)")
                .param("u", username).query(Integer.class).single() > 0;
        if (exists) {
            // dev rows created before the role column existed
            jdbc.sql("update account set role = :role where lower(username) = lower(:u)")
                    .param("role", role).param("u", username).update();
            return;
        }
        var now = clock.instant().atOffset(java.time.ZoneOffset.UTC);
        jdbc.sql("""
                insert into account (id, school_id, username, password_hash, type, role, created_at, updated_at)
                values (:id, :school, :username, :hash, :type, :role, :now, :now)
                """)
                .param("role", role)
                .param("id", UUID.randomUUID())
                .param("school", schoolId)
                .param("username", username)
                .param("hash", passwordEncoder.encode(props.dev().testPassword()))
                .param("type", type)
                .param("now", now)
                .update();
    }
}
