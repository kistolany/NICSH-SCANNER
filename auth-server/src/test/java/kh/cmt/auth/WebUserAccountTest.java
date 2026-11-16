package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.auth.OAuthFlow.Attempt;
import kh.cmt.auth.support.AuthServerTest;
import kh.cmt.auth.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Step 3.6, the auth server's part of web users (the account is written by cmt-api's iam module): access that ends
 * on a date, "sign out everywhere", two-step verification for one user, last login.
 */
@AuthServerTest
class WebUserAccountTest {

    static final String PASSWORD = "Office#Pass1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestAccounts accounts;
    @Autowired Clock clock;

    OAuthFlow flow;
    String role;

    @BeforeEach
    void setUp() {
        flow = new OAuthFlow(mvc, json);
        role = "role_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Step 3.6 "done when": an expired web user cannot log in the day after access_expires_on. */
    @Test
    void anExpiredWebUserCannotLogInTheDayAfterTheLastDay() throws Exception {
        String user = accounts.createWeb("auditor", PASSWORD, role, null);
        LocalDate today = LocalDate.now(clock);

        // the last day itself is still fine
        set(user, "access_expires_on", today);
        assertThat(flow.login(user, PASSWORD).get("access_token")).isNotNull();

        // the day after: the password is refused
        set(user, "access_expires_on", today.minusDays(1));
        Attempt attempt = flow.begin();
        assertThat(flow.submitLogin(attempt, user, PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login");   // no code
    }

    @Test
    void afterTheLastDayNeitherAnOpenLoginNorARefreshGetsNewTokens() throws Exception {
        String user = accounts.createWeb("contractor", PASSWORD, role, null);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        String refresh = flow.exchange(attempt, flow.authorize(attempt).getResponse().getRedirectedUrl())
                .get("refresh_token").asText();

        set(user, "access_expires_on", LocalDate.now(clock).minusDays(1));

        MvcResult refused = flow.refresh(refresh);
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(flow.body(refused).get("error_description").asText()).contains("ACCESS_EXPIRED");
        // the still-open login session asks for a new code: refused too
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).isEqualTo("/login/denied?reason=ACCESS_EXPIRED");
    }

    @Test
    void signOutEverywhereEndsTheOpenLoginSession() throws Exception {
        String user = accounts.createWeb("signout", PASSWORD, role, null);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).startsWith(OAuthFlow.REDIRECT);

        // what cmt-api does on "sign out everywhere" (besides revoking the refresh tokens)
        jdbc.sql("update account set sessions_revoked_at = now() where username = :u").param("u", user).update();

        // the browser's login session no longer hands out codes: the password is asked again
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login");
        // a new login works
        assertThat(flow.login(user, PASSWORD).get("access_token")).isNotNull();
    }

    @Test
    void anInactiveAccountIsSignedOutOfItsOpenLoginSession() throws Exception {
        String user = accounts.createWeb("leaver", PASSWORD, role, null);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        set(user, "status", "inactive");
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void twoStepVerificationCanBeRequiredForOneUser() throws Exception {
        String user = accounts.createWeb("careful", PASSWORD, role, null);   // the role does not require it
        set(user, "two_factor_required", true);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login/otp/enroll");
    }

    @Test
    void theLastLoginIsRecorded() throws Exception {
        String user = accounts.createWeb("seen", PASSWORD, role, null);
        assertThat(hasLastLogin(user)).isFalse();
        flow.login(user, PASSWORD);
        assertThat(hasLastLogin(user)).isTrue();
    }

    private void set(String user, String column, Object value) {
        jdbc.sql("update account set " + column + " = :v where username = :u").param("v", value).param("u", user).update();
    }

    private boolean hasLastLogin(String user) {
        return jdbc.sql("select last_login_at is not null from account where username = :u").param("u", user)
                .query(Boolean.class).single();
    }
}
