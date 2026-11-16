package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import kh.cmt.auth.OAuthFlow.Attempt;
import kh.cmt.auth.support.AuthServerTest;
import kh.cmt.auth.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Step 1.3 "done when": 5 wrong passwords lock the account. */
@AuthServerTest
class LockoutTest {

    static final String PASSWORD = "Right#Pass1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestAccounts accounts;
    @Autowired StringRedisTemplate redis;

    OAuthFlow flow;

    @BeforeEach
    void setUp() {
        flow = new OAuthFlow(mvc, json);
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheRightPassword() throws Exception {
        String user = accounts.create("lock", PASSWORD, false);
        Attempt attempt = flow.begin();

        for (int i = 1; i <= 4; i++) {
            assertThat(flow.submitLogin(attempt, user, "wrong" + i).getResponse().getRedirectedUrl())
                    .contains("/login?error");
        }
        assertThat(lockedUntil(user)).isNull();   // 4 is still fine

        flow.submitLogin(attempt, user, "wrong5");
        assertThat(lockedUntil(user)).isAfter(OffsetDateTime.now().plusMinutes(14));   // 15 minutes
        assertThat(redis.hasKey("auth:login-failures:" + user)).isFalse();             // counter reset on lock

        // the right password does not get in while locked
        assertThat(flow.submitLogin(attempt, user, PASSWORD).getResponse().getRedirectedUrl())
                .contains("/login?error");
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login");

        // after the lock time has passed the right password works again
        jdbc.sql("update account set locked_until = now() - interval '1 minute' where username = :u")
                .param("u", user).update();
        assertThat(flow.login(user, PASSWORD).hasNonNull("access_token")).isTrue();
    }

    @Test
    void aCorrectLoginResetsTheCount() throws Exception {
        String user = accounts.create("reset", PASSWORD, false);
        Attempt attempt = flow.begin();
        for (int i = 1; i <= 4; i++) {
            flow.submitLogin(attempt, user, "wrong" + i);
        }
        assertThat(flow.login(user, PASSWORD).hasNonNull("access_token")).isTrue();

        // four more wrong ones would be 8 in total without the reset; with it, still not locked
        Attempt second = flow.begin();
        for (int i = 1; i <= 4; i++) {
            flow.submitLogin(second, user, "wrong" + i);
        }
        assertThat(lockedUntil(user)).isNull();
    }

    @Test
    void unknownUsernamesAreCountedToo_soTheAnswerDoesNotRevealWhoExists() throws Exception {
        Attempt attempt = flow.begin();
        for (int i = 1; i <= 3; i++) {
            flow.submitLogin(attempt, "nobody.here", "whatever" + i);
        }
        assertThat(Long.parseLong(redis.opsForValue().get("auth:login-failures:nobody.here"))).isEqualTo(3);
        assertThat(redis.getExpire("auth:login-failures:nobody.here")).isBetween(1L, Duration.ofMinutes(15).toSeconds());
    }

    private OffsetDateTime lockedUntil(String user) {
        return jdbc.sql("select locked_until from account where username = :u").param("u", user)
                .query(OffsetDateTime.class).optional().orElse(null);
    }
}
