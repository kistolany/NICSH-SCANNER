package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import kh.cmt.auth.password.PasswordPolicyService;
import kh.cmt.auth.support.AuthServerTest;
import kh.cmt.auth.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** /auth/password/change, the password rules, Argon2id; no forgot / reset (an admin gives new passwords). */
@AuthServerTest
class PasswordApiTest {

    static final String PASSWORD = "Valid#Pass1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestAccounts accounts;
    @Autowired PasswordPolicyService policy;

    OAuthFlow flow;

    @BeforeEach
    void setUp() {
        flow = new OAuthFlow(mvc, json);
    }

    // ------------------------------------------------------------------ rules

    @Test
    void policyReportsEveryBrokenRuleAsACode() {
        assertThat(policy.violations("someone", "abc")).containsExactlyInAnyOrder("MIN_LENGTH", "UPPERCASE", "DIGIT");
        assertThat(policy.violations("someone", "Abcdefg1")).isEmpty();
        assertThat(policy.violations("sokha", "Sokha#2025")).containsExactly("CONTAINS_USERNAME");
        assertThat(policy.violations("someone", "A1" + "x".repeat(200))).contains("TOO_LONG");
    }

    // ------------------------------------------------------------------ hashing

    @Test
    void newPasswordsAreArgon2idAndOldBcryptHashesUpgradeAtLogin() throws Exception {
        String fresh = accounts.create("hash", PASSWORD, false);
        assertThat(hash(fresh)).startsWith("{argon2}$argon2id$");

        String old = accounts.createWithHash("legacy", "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD), false);
        assertThat(hash(old)).startsWith("{bcrypt}");
        assertThat(flow.login(old, PASSWORD).hasNonNull("access_token")).isTrue();
        assertThat(hash(old)).startsWith("{argon2}$argon2id$");
        assertThat(flow.login(old, PASSWORD).hasNonNull("access_token")).isTrue();   // still works after the upgrade
    }

    // ------------------------------------------------------------------ change

    @Test
    void changePasswordNeedsAToken() throws Exception {
        MvcResult result = mvc.perform(post("/auth/password/change").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void changePasswordChecksCurrentThenRulesThenChanges() throws Exception {
        String user = accounts.create("change", PASSWORD, false);
        String token = flow.login(user, PASSWORD).get("access_token").asText();

        MvcResult wrong = change(token, "Not#Right9", "Another#Pass2");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(flow.body(wrong).get("code").asText()).isEqualTo("WRONG_PASSWORD");

        MvcResult weak = change(token, PASSWORD, "short");
        assertThat(flow.body(weak).get("code").asText()).isEqualTo("PASSWORD_POLICY");
        assertThat(flow.body(weak).get("violations")).extracting(JsonNode::asText)
                .contains("MIN_LENGTH", "UPPERCASE", "DIGIT");

        MvcResult same = change(token, PASSWORD, PASSWORD);
        assertThat(flow.body(same).get("violations")).extracting(JsonNode::asText).contains("SAME_AS_CURRENT");

        MvcResult ok = change(token, PASSWORD, "Another#Pass2");
        assertThat(ok.getResponse().getStatus()).isEqualTo(204);
        assertThat(flow.login(user, "Another#Pass2").hasNonNull("access_token")).isTrue();
        assertThat(flow.submitLogin(flow.begin(), user, PASSWORD).getResponse().getRedirectedUrl())
                .contains("/login?error");
    }

    @Test
    void thePolicyIsReadWithAToken() throws Exception {
        assertThat(mvc.perform(get("/auth/password/policy")).andReturn().getResponse().getStatus()).isEqualTo(401);

        String user = accounts.create("rules", PASSWORD, false);
        String token = flow.login(user, PASSWORD).get("access_token").asText();
        JsonNode rules = flow.body(mvc.perform(get("/auth/password/policy").header("Authorization", "Bearer " + token)).andReturn());
        PasswordPolicyService.Policy p = policy.current();
        assertThat(rules.get("minLength").asInt()).isEqualTo(p.minLength());
        assertThat(rules.get("upper").asBoolean()).isEqualTo(p.requireUpper());
        assertThat(rules.get("digit").asBoolean()).isEqualTo(p.requireDigit());
        assertThat(rules.has("lower") && rules.has("symbol")).isTrue();
    }

    @Test
    void guessingTheCurrentPasswordWithAStolenTokenLocksTheAccount() throws Exception {
        String user = accounts.create("stolen", PASSWORD, false);
        String token = flow.login(user, PASSWORD).get("access_token").asText();
        for (int i = 0; i < 5; i++) {
            change(token, "guess" + i, "Another#Pass2");
        }
        assertThat(jdbc.sql("select locked_until is not null from account where username = :u").param("u", user)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void aJobTokenCannotChangePasswords() throws Exception {
        MvcResult jobToken = mvc.perform(post("/oauth2/token")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("cmt-jobs:dev-jobs-secret".getBytes(StandardCharsets.UTF_8)))
                .param("grant_type", "client_credentials").param("scope", "jobs")).andReturn();
        String token = flow.body(jobToken).get("access_token").asText();
        assertThat(change(token, "x", "y").getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ no forgot / reset

    @Test
    void thereIsNoForgotOrResetByEmail() throws Exception {
        assertThat(mvc.perform(post("/auth/password/forgot").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    /** Login for an account that is flagged/locked in the test: clear the flags first, log in, put them back. */
    private JsonNode loginIgnoringFlags(String user) throws Exception {
        jdbc.sql("update account set locked_until = null, must_change_password = false where username = :u")
                .param("u", user).update();
        JsonNode tokens = flow.login(user, PASSWORD);
        jdbc.sql("update account set locked_until = now() + interval '10 minutes', must_change_password = true "
                + "where username = :u").param("u", user).update();
        return tokens;
    }

    private String hash(String user) {
        return jdbc.sql("select password_hash from account where username = :u").param("u", user)
                .query(String.class).single();
    }

    private MvcResult change(String token, String current, String next) throws Exception {
        return mvc.perform(post("/auth/password/change").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("currentPassword", current, "newPassword", next))))
                .andReturn();
    }


}
