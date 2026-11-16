package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kh.cmt.auth.OAuthFlow.Attempt;
import kh.cmt.auth.support.AuthServerTest;
import kh.cmt.auth.support.CapturingSmsSender;
import kh.cmt.auth.support.TestAccounts;
import kh.cmt.auth.twofactor.Totp;
import kh.cmt.auth.twofactor.TwoFactorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Step 1.4 "done when": a role with two-step verification on cannot finish login without the 6-digit code. */
@AuthServerTest
class TwoFactorTest {

    static final String PASSWORD = "Office#Pass1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestAccounts accounts;
    @Autowired CapturingSmsSender smsGateway;
    @Autowired TwoFactorService twoFactor;

    OAuthFlow flow;
    String role;

    @BeforeEach
    void setUp() {
        flow = new OAuthFlow(mvc, json);
        role = "role_" + UUID.randomUUID().toString().substring(0, 8);   // each test has its own role and rule
    }

    @Test
    void aRoleWithoutTheRuleLogsInWithJustThePassword() throws Exception {
        String user = accounts.createWeb("plain", PASSWORD, role, null);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).startsWith(OAuthFlow.REDIRECT);
    }

    @Test
    void appAccountsNeverGetASecondStep() throws Exception {
        accounts.requireTwoFactor("staff", true);   // even a rule for the role name "staff" does not touch app accounts
        try {
            JsonNode tokens = flow.login("test.staff", "Test@12345");
            assertThat(claims(tokens).getStringListClaim("amr")).containsExactly("pwd");
        } finally {
            accounts.requireTwoFactor("staff", false);
        }
    }

    @Test
    void firstLoginEnrolsAnAuthenticatorThenEveryLoginNeedsTheCode() throws Exception {
        accounts.requireTwoFactor(role, true);
        String user = accounts.createWeb("admin", PASSWORD, role, null);

        // 1. password OK -> no authorization code, sent to enrolment
        Attempt first = flow.begin();
        flow.submitLogin(first, user, PASSWORD);
        assertThat(flow.authorize(first).getResponse().getRedirectedUrl()).endsWith("/login/otp/enroll");

        String page = mvc.perform(get("/login/otp/enroll").session(first.session())).andReturn()
                .getResponse().getContentAsString();
        assertThat(page).contains("<svg").contains("data-secret=");   // QR code plus the key for manual entry
        String secret = secretFrom(page);

        // 2. wrong code: still no authorization code
        assertThat(postCode("/login/otp/enroll", first, "000000", null).getResponse().getContentAsString())
                .contains("data-code=\"INVALID_CODE\"");
        assertThat(flow.authorize(first).getResponse().getRedirectedUrl()).endsWith("/login/otp/enroll");
        assertThat(twoFactor.isEnrolled(idOf(user))).isFalse();

        // 3. right code: enrolled, login continues, token says the second step happened
        String code = Totp.code(secret, Totp.stepAt(Instant.now().getEpochSecond()));
        MvcResult done = postCode("/login/otp/enroll", first, code, null);
        assertThat(done.getResponse().getRedirectedUrl()).contains("/oauth2/authorize");
        JsonNode tokens = flow.exchange(first, flow.authorize(first).getResponse().getRedirectedUrl());
        assertThat(claims(tokens).getStringListClaim("amr")).containsExactly("pwd", "otp");
        assertThat(claims(tokens).getStringClaim("typ")).isEqualTo("web");

        // the secret is stored encrypted, never in clear
        String stored = jdbc.sql("select totp_secret_enc from account where username = :u").param("u", user)
                .query(String.class).single();
        assertThat(stored).isNotBlank().doesNotContain(secret);

        // 4. next login: password alone is not enough, a code is asked for
        Attempt second = flow.begin();
        flow.submitLogin(second, user, PASSWORD);
        assertThat(flow.authorize(second).getResponse().getRedirectedUrl()).endsWith("/login/otp");

        // the code that was just used cannot be used again (replay)
        assertThat(postCode("/login/otp", second, code, "totp").getResponse().getContentAsString())
                .contains("data-code=\"INVALID_CODE\"");
        // the next code is fine
        String next = Totp.code(secret, Totp.stepAt(Instant.now().getEpochSecond()) + 1);
        postCode("/login/otp", second, next, "totp");
        JsonNode again = flow.exchange(second, flow.authorize(second).getResponse().getRedirectedUrl());
        assertThat(claims(again).getStringListClaim("amr")).containsExactly("pwd", "otp");

        // 5. the refresh token keeps the second step in its tokens
        JsonNode refreshed = flow.body(flow.refresh(again.get("refresh_token").asText()));
        assertThat(claims(refreshed).getStringListClaim("amr")).containsExactly("pwd", "otp");
    }

    @Test
    void fiveWrongCodesLockTheAccountAndEndTheSession() throws Exception {
        String user = enrolled(role);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login/otp");

        for (int i = 0; i < 4; i++) {
            assertThat(postCode("/login/otp", attempt, "11111" + i, "totp").getResponse().getContentAsString())
                    .contains("INVALID_CODE");
        }
        MvcResult fifth = postCode("/login/otp", attempt, "111115", "totp");
        assertThat(fifth.getResponse().getRedirectedUrl()).contains("/login?error");
        assertThat(jdbc.sql("select locked_until > now() from account where username = :u").param("u", user)
                .query(Boolean.class).single()).isTrue();
        // session is gone: the authorize endpoint wants a new login
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void aCorrectPasswordDoesNotResetTheCodeFailureCount() throws Exception {
        String user = enrolled(role);
        for (int round = 0; round < 5; round++) {   // 5 rounds of: password, one wrong code
            Attempt attempt = flow.begin();
            flow.submitLogin(attempt, user, PASSWORD);
            postCode("/login/otp", attempt, "99999" + round, "totp");
        }
        assertThat(jdbc.sql("select locked_until is not null from account where username = :u").param("u", user)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void smsIsAFallbackForUsersWhoHaveAPhone() throws Exception {
        String phone = "+855" + (long) (Math.random() * 1_000_000_000L);
        String user = enrolled(role, phone);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        flow.authorize(attempt);

        // the code page offers the SMS option
        assertThat(mvc.perform(get("/login/otp").session(attempt.session())).andReturn().getResponse().getContentAsString())
                .contains("/login/otp/sms");

        assertThat(postCode("/login/otp/sms", attempt, null, null).getResponse().getContentAsString())
                .contains("data-code=\"SMS_SENT\"");
        String smsCode = smsGateway.lastCodeFor(phone);
        assertThat(smsCode).matches("\\d{6}");

        // asking again straight away is refused (cooldown)
        assertThat(postCode("/login/otp/sms", attempt, null, null).getResponse().getContentAsString())
                .contains("data-code=\"TOO_SOON\"");

        // a wrong code, then the right one
        assertThat(postCode("/login/otp", attempt, smsCode.equals("000000") ? "000001" : "000000", "sms")
                .getResponse().getContentAsString()).contains("INVALID_CODE");
        postCode("/login/otp", attempt, smsCode, "sms");
        JsonNode tokens = flow.exchange(attempt, flow.authorize(attempt).getResponse().getRedirectedUrl());
        assertThat(claims(tokens).getStringListClaim("amr")).containsExactly("pwd", "otp", "sms");

        // an SMS code works once
        Attempt again = flow.begin();
        flow.submitLogin(again, user, PASSWORD);
        assertThat(postCode("/login/otp", again, smsCode, "sms").getResponse().getContentAsString()).contains("INVALID_CODE");
    }

    @Test
    void withoutAPhoneThereIsNoSmsOption() throws Exception {
        String user = enrolled(role);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        flow.authorize(attempt);
        assertThat(mvc.perform(get("/login/otp").session(attempt.session())).andReturn().getResponse().getContentAsString())
                .doesNotContain("/login/otp/sms");
        assertThat(postCode("/login/otp/sms", attempt, null, null).getResponse().getContentAsString())
                .contains("data-code=\"NO_PHONE\"");
    }

    @Test
    void turningTheRuleOnLaterEndsOldSessionsAtTheNextRefresh() throws Exception {
        String user = accounts.createWeb("late", PASSWORD, role, null);
        String refresh = flow.login(user, PASSWORD).get("refresh_token").asText();   // no rule yet: password only

        accounts.requireTwoFactor(role, true);
        assertThat(flow.refresh(refresh).getResponse().getStatus()).isEqualTo(400);

        // and a fresh login now needs the second step
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login/otp/enroll");
    }

    @Test
    void resettingTwoStepVerificationLetsTheUserEnrolAgain() throws Exception {
        String user = enrolled(role);
        twoFactor.reset(idOf(user));
        assertThat(twoFactor.isEnrolled(idOf(user))).isFalse();

        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login/otp/enroll");
    }

    // ------------------------------------------------------------------ helpers

    /** A web account for {@code role} (rule on) that already finished enrolment. Returns the username. */
    private String enrolled(String role) throws Exception {
        return enrolled(role, null);
    }

    private String enrolled(String role, String phone) throws Exception {
        accounts.requireTwoFactor(role, true);
        String user = accounts.createWeb("enrolled", PASSWORD, role, phone);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        flow.authorize(attempt);
        String secret = secretFrom(mvc.perform(get("/login/otp/enroll").session(attempt.session())).andReturn()
                .getResponse().getContentAsString());
        postCode("/login/otp/enroll", attempt, Totp.code(secret, Totp.stepAt(Instant.now().getEpochSecond())), null);
        assertThat(twoFactor.isEnrolled(idOf(user))).isTrue();
        // the enrolment used the current step: later tests in this method need codes from the next step
        secrets.put(user, secret);
        return user;
    }

    private final java.util.Map<String, String> secrets = new java.util.HashMap<>();

    private MvcResult postCode(String path, Attempt attempt, String code, String method) throws Exception {
        var request = post(path).session(attempt.session()).with(csrf());
        if (code != null) {
            request = request.param("code", code);
        }
        if (method != null) {
            request = request.param("method", method);
        }
        return mvc.perform(request).andReturn();
    }

    private static String secretFrom(String page) {
        Matcher m = Pattern.compile("data-secret=\"([A-Z2-7]+)\"").matcher(page);
        assertThat(m.find()).as("secret on the enrolment page").isTrue();
        return m.group(1);
    }

    private UUID idOf(String user) {
        return jdbc.sql("select id from account where username = :u").param("u", user).query(UUID.class).single();
    }

    private static JWTClaimsSet claims(JsonNode tokens) throws Exception {
        return SignedJWT.parse(tokens.get("access_token").asText()).getJWTClaimsSet();
    }
}
