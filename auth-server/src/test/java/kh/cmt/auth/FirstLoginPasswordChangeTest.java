package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import kh.cmt.auth.OAuthFlow.Attempt;
import kh.cmt.auth.support.AuthServerTest;
import kh.cmt.auth.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Step 1.3 "done when": the first login with a generated password forces a change. */
@AuthServerTest
class FirstLoginPasswordChangeTest {

    static final String TEMP = "Temp#1234";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestAccounts accounts;

    OAuthFlow flow;

    @BeforeEach
    void setUp() {
        flow = new OAuthFlow(mvc, json);
    }

    @Test
    void firstLoginIsSentToTheChangePageAndNoCodeIsIssuedUntilItIsDone() throws Exception {
        String user = accounts.create("first", TEMP, true);
        Attempt attempt = flow.begin();

        flow.submitLogin(attempt, user, TEMP);

        // instead of an authorization code, the user is sent to choose a new password
        String next = flow.authorize(attempt).getResponse().getRedirectedUrl();
        assertThat(next).endsWith("/password/change");

        String page = mvc.perform(get("/password/change").session(attempt.session())).andReturn()
                .getResponse().getContentAsString();
        assertThat(page).contains("Choose a new password").contains("name=\"newPassword\"");

        // a weak password is refused with codes, and nothing changes
        String weak = submitChange(attempt, TEMP, "abc", "abc").getResponse().getContentAsString();
        assertThat(weak).contains("data-code=\"MIN_LENGTH\"").contains("data-code=\"UPPERCASE\"")
                .contains("data-code=\"DIGIT\"");
        assertThat(mustChange(user)).isTrue();

        // mismatching confirmation
        assertThat(submitChange(attempt, TEMP, "Brand#New9x", "Different#9x").getResponse().getContentAsString())
                .contains("PASSWORDS_DO_NOT_MATCH");

        // wrong current password
        assertThat(submitChange(attempt, "Wrong#1234", "Brand#New9x", "Brand#New9x").getResponse().getContentAsString())
                .contains("WRONG_PASSWORD");

        // a good one: flag cleared and the user continues straight into the login they started
        MvcResult done = submitChange(attempt, TEMP, "Brand#New9x", "Brand#New9x");
        assertThat(done.getResponse().getRedirectedUrl()).contains("/oauth2/authorize");
        assertThat(mustChange(user)).isFalse();

        String location = flow.authorize(attempt).getResponse().getRedirectedUrl();
        assertThat(flow.exchange(attempt, location).hasNonNull("access_token")).isTrue();

        // from now on only the new password works
        assertThat(flow.login(user, "Brand#New9x").hasNonNull("access_token")).isTrue();
        assertThat(flow.submitLogin(flow.begin(), user, TEMP).getResponse().getRedirectedUrl()).contains("/login?error");
    }

    @Test
    void anAccountWithoutTheFlagIsNotSentToTheChangePage() throws Exception {
        String user = accounts.create("normal", TEMP, false);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, TEMP);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).startsWith(OAuthFlow.REDIRECT);
    }

    private MvcResult submitChange(Attempt attempt, String current, String next, String confirm) throws Exception {
        return mvc.perform(post("/password/change").session(attempt.session()).with(csrf())
                .param("currentPassword", current).param("newPassword", next).param("confirmPassword", confirm))
                .andReturn();
    }

    private boolean mustChange(String user) {
        return jdbc.sql("select must_change_password from account where username = :u").param("u", user)
                .query(Boolean.class).single();
    }
}
