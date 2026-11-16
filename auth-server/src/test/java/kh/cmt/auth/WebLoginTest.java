package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kh.cmt.auth.OAuthFlow.Attempt;
import kh.cmt.auth.support.CapturingSmsSender;
import kh.cmt.auth.support.TestAccounts;
import kh.cmt.auth.support.TestcontainersConfig;
import kh.cmt.auth.twofactor.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The admin web's own sign-in screens (login, new password, 2-step code) in front of this server: the browser is sent
 * to {@code auth.web.login-url}, the screens talk JSON to /login and /login/api/** from the admin web's origin.
 */
@SpringBootTest(properties = {"auth.rate-limit.login.capacity=100000", "auth.web.login-url=http://admin.test/login",
        "auth.web.allowed-origins=http://admin.test"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, CapturingSmsSender.Config.class})
class WebLoginTest {

    static final String WEB = "http://admin.test/login";
    static final String PASSWORD = "Office#Pass1";

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
    void theBrowserIsSentToTheAdminWebAndItsOriginMayCallIn() throws Exception {
        // not signed in: the authorize request goes to the admin web's screen (and is kept for later)
        Attempt attempt = flow.begin();
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).isEqualTo(WEB);
        // this server's own address only points there
        assertThat(mvc.perform(get("/login")).andReturn().getResponse().getRedirectedUrl()).isEqualTo(WEB);

        // the screen first reads the state: a CSRF token and "login"
        JsonNode state = state(attempt);
        assertThat(state.get("step").asText()).isEqualTo("login");
        assertThat(state.at("/csrf/token").asText()).isNotBlank();
        assertThat(state.at("/csrf/headerName").asText()).isEqualTo("X-CSRF-TOKEN");

        // CORS: the admin web may call with the session cookie, another site may not
        MvcResult allowed = mvc.perform(options("/login").header("Origin", "http://admin.test")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "x-csrf-token")).andReturn();
        assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://admin.test");
        assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        MvcResult refused = mvc.perform(options("/login").header("Origin", "http://evil.test")
                .header("Access-Control-Request-Method", "POST")).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);

        // the JSON calls answer 401 (not a redirect) without a login session
        assertThat(mvc.perform(post("/login/api/otp").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void wrongPasswordsComeBackAsCodesAndTheLockIsNamed() throws Exception {
        String user = accounts.create("webwrong", PASSWORD, false);
        Attempt attempt = flow.begin();
        MvcResult wrong = jsonLogin(attempt, user, "Wrong#1234");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(wrong).get("error").asText()).isEqualTo("BAD_CREDENTIALS");
        // a plain form post (no JavaScript) goes back to the screen with the code
        assertThat(flow.submitLogin(attempt, user, "Wrong#1234").getResponse().getRedirectedUrl()).isEqualTo(WEB + "?error=BAD_CREDENTIALS");

        for (int i = 0; i < 3; i++) {
            jsonLogin(attempt, user, "Wrong#1234");
        }
        // 5 wrong passwords lock the account: even the right one is now refused as LOCKED
        assertThat(body(jsonLogin(attempt, user, PASSWORD)).get("error").asText()).isEqualTo("LOCKED");
    }

    @Test
    void firstLoginChoosesANewPasswordOnTheScreenThenGetsTheCode() throws Exception {
        String user = accounts.create("webfirst", "Temp#1234", true);
        Attempt attempt = flow.begin();

        // right password: the screen is told where to carry on (the authorize request it came from)
        MvcResult ok = jsonLogin(attempt, user, "Temp#1234");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("next").asText()).contains("/oauth2/authorize");

        // which sends it back to the screen's "set a new password" step
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).isEqualTo(WEB + "?step=new-password");
        JsonNode state = state(attempt);
        assertThat(state.get("step").asText()).isEqualTo("new-password");
        assertThat(state.at("/rules/minLength").asInt()).isEqualTo(8);
        assertThat(state.get("username").asText()).isEqualTo(user);

        // weak / mismatching passwords are refused with codes
        MvcResult weak = newPassword(attempt, "abc", "abc");
        assertThat(weak.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(weak).get("errors").toString()).contains("MIN_LENGTH").contains("UPPERCASE").contains("DIGIT");
        assertThat(body(newPassword(attempt, "Brand#New9x", "Other#New9x")).get("errors").toString()).contains("PASSWORDS_DO_NOT_MATCH");

        // a good one (no current password asked: the session just proved it)
        MvcResult done = newPassword(attempt, "Brand#New9x", "Brand#New9x");
        assertThat(body(done).get("next").asText()).contains("/oauth2/authorize");
        assertThat(jdbc.sql("select must_change_password from account where username = :u").param("u", user).query(Boolean.class).single()).isFalse();
        String location = flow.authorize(attempt).getResponse().getRedirectedUrl();
        assertThat(flow.exchange(attempt, location).hasNonNull("access_token")).isTrue();

        // the forced change is only for accounts that must change: it cannot be used to skip the current password
        assertThat(newPassword(attempt, "Again#New9x", "Again#New9x").getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void twoStepVerificationIsSetUpAndEnteredOnTheScreen() throws Exception {
        String role = "role_" + UUID.randomUUID().toString().substring(0, 8);
        accounts.requireTwoFactor(role, true);
        String user = accounts.createWeb("webotp", PASSWORD, role, null);

        Attempt attempt = flow.begin();
        jsonLogin(attempt, user, PASSWORD);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).isEqualTo(WEB + "?step=enroll");
        JsonNode state = state(attempt);
        assertThat(state.get("step").asText()).isEqualTo("enroll");
        assertThat(state.at("/enrolment/qrSvg").asText()).startsWith("<svg");
        String secret = state.at("/enrolment/secret").asText();

        MvcResult wrong = code(attempt, "/login/api/otp/enroll", "000000", null);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(wrong).get("errors").toString()).contains("INVALID_CODE");

        MvcResult done = code(attempt, "/login/api/otp/enroll", Totp.code(secret, Totp.stepAt(Instant.now().getEpochSecond())), null);
        assertThat(body(done).get("next").asText()).contains("/oauth2/authorize");
        JsonNode tokens = flow.exchange(attempt, flow.authorize(attempt).getResponse().getRedirectedUrl());
        assertThat(SignedJWT.parse(tokens.get("access_token").asText()).getJWTClaimsSet().getStringListClaim("amr")).containsExactly("pwd", "otp");

        // next time: the code step, and "Back" ends the half-done login
        Attempt again = flow.begin();
        jsonLogin(again, user, PASSWORD);
        assertThat(flow.authorize(again).getResponse().getRedirectedUrl()).isEqualTo(WEB + "?step=otp");
        assertThat(state(again).get("step").asText()).isEqualTo("otp");
        mvc.perform(post("/login/api/cancel").session(again.session()).with(csrf())).andReturn();
        assertThat(state(again).get("step").asText()).isEqualTo("login");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode state(Attempt attempt) throws Exception {
        return body(mvc.perform(get("/login/api/state").session(attempt.session())).andReturn());
    }

    private MvcResult jsonLogin(Attempt attempt, String username, String password) throws Exception {
        return mvc.perform(post("/login").session(attempt.session()).with(csrf()).accept(MediaType.APPLICATION_JSON)
                .param("username", username).param("password", password)).andReturn();
    }

    private MvcResult newPassword(Attempt attempt, String pw, String confirm) throws Exception {
        return mvc.perform(post("/login/api/password").session(attempt.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("newPassword", pw, "confirmPassword", confirm)))).andReturn();
    }

    private MvcResult code(Attempt attempt, String path, String code, String method) throws Exception {
        Map<String, String> body = method == null ? Map.of("code", code) : Map.of("code", code, "method", method);
        return mvc.perform(post(path).session(attempt.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
