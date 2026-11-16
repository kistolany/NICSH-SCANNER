package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kh.cmt.auth.support.AuthServerTest;
import kh.cmt.auth.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The mobile app's own login: JSON login with device binding, refresh, logout (contract: Mobile-App/docs/api.md). */
@AuthServerTest
class AppLoginApiTest {

    static final String PASSWORD = "Staff#Pass1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestAccounts accounts;
    @Autowired PasswordEncoder encoder;

    @Test
    void loginGivesTokensThatTheApiAccepts_andBindsThePhone() throws Exception {
        UUID person = UUID.randomUUID();
        String user = appAccount("teacher", person, false);
        String phone = UUID.randomUUID().toString();

        JsonNode tokens = body(login(user, PASSWORD, phone, "Galaxy A54").andExpect(status().isOk()));
        assertThat(tokens.get("role").asText()).isEqualTo("teacher");
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("expiresIn").asLong()).isBetween(800L, 900L);
        assertThat(tokens.get("mustChangePassword").asBoolean()).isFalse();
        JWTClaimsSet claims = SignedJWT.parse(tokens.get("accessToken").asText()).getJWTClaimsSet();
        assertThat(claims.getStringClaim("typ")).isEqualTo("app");
        assertThat(claims.getStringClaim("role")).isEqualTo("teacher");
        assertThat(claims.getStringClaim("person_id")).isEqualTo(person.toString());
        assertThat(claims.getStringClaim("device_id")).isEqualTo(phone);
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:9200");
        assertThat(claims.getStringListClaim("amr")).containsExactly("pwd");

        // the same phone again is fine; another phone is refused with the name of the linked one
        login(user, PASSWORD, phone, "Galaxy A54").andExpect(status().isOk());
        login(user, PASSWORD, UUID.randomUUID().toString(), "iPhone 15")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEVICE_MISMATCH"))
                .andExpect(jsonPath("$.linkedDeviceName").value("Galaxy A54"));
    }

    @Test
    void refreshRotatesTheTokens_andAReusedRefreshTokenEndsTheSession() throws Exception {
        String user = appAccount("staff", UUID.randomUUID(), false);
        String phone = UUID.randomUUID().toString();
        JsonNode first = body(login(user, PASSWORD, phone, "Pixel 9a"));

        JsonNode second = body(refresh(first.get("refreshToken").asText()).andExpect(status().isOk()));
        assertThat(second.get("refreshToken").asText()).isNotEqualTo(first.get("refreshToken").asText());
        assertThat(SignedJWT.parse(second.get("accessToken").asText()).getJWTClaimsSet().getStringClaim("typ")).isEqualTo("app");

        // the old one again: theft is assumed, the whole session ends (the newest token too)
        refresh(first.get("refreshToken").asText()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        refresh(second.get("refreshToken").asText()).andExpect(status().isUnauthorized());
        refresh("nonsense").andExpect(status().isUnauthorized());

        // an inactive account cannot refresh
        JsonNode again = body(login(user, PASSWORD, phone, "Pixel 9a"));
        jdbc.sql("update account set status = 'inactive' where username = :u").param("u", user).update();
        refresh(again.get("refreshToken").asText()).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String user = appAccount("staff", UUID.randomUUID(), false);
        JsonNode tokens = body(login(user, PASSWORD, UUID.randomUUID().toString(), "Pixel"));
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + tokens.get("accessToken").asText()))
                .andExpect(status().isNoContent());
        refresh(tokens.get("refreshToken").asText()).andExpect(status().isUnauthorized());
    }

    @Test
    void refusals() throws Exception {
        String user = appAccount("staff", UUID.randomUUID(), true);
        login(user, "wrong", null, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("nobody." + UUID.randomUUID(), PASSWORD, null, null).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        login(user, PASSWORD, "not-a-uuid", null).andExpect(jsonPath("$.errors[0].rule").value("Uuid"));

        // a first password: the login works and says it must be changed
        assertThat(body(login(user, PASSWORD, null, null)).get("mustChangePassword").asBoolean()).isTrue();

        // office users sign in on the Admin Web
        String web = accounts.createWeb("office", PASSWORD, "hr", null);
        login(web, PASSWORD, null, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_AN_APP_ACCOUNT"));

        // wrong passwords count towards the lockout, as at a browser login
        String locked = appAccount("staff", UUID.randomUUID(), false);
        for (int i = 0; i < 5; i++) {
            login(locked, "wrong-" + i, null, null);
        }
        login(locked, PASSWORD, null, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        jdbc.sql("update account set status = 'inactive' where username = :u").param("u", user).update();
        login(user, PASSWORD, null, null).andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
    }

    // ------------------------------------------------------------------ helpers

    /** An app account as cmt-api's people module creates it (step 4.2): role, linked person, password hash. */
    private String appAccount(String role, UUID person, boolean mustChange) {
        String username = "app." + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                insert into account (id, school_id, username, password_hash, type, person_id, status, role, must_change_password,
                                     created_at, updated_at)
                values (:id, '00000000-0000-0000-0000-000000000001', :u, :hash, 'app', :p, 'active', :role, :must, :now, :now)
                """)
                .param("id", UUID.randomUUID()).param("u", username).param("hash", encoder.encode(PASSWORD)).param("p", person)
                .param("role", role).param("must", mustChange).param("now", OffsetDateTime.now()).update();
        return username;
    }

    private ResultActions login(String username, String password, String deviceId, String deviceName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("deviceId", deviceId == null ? UUID.randomUUID().toString() : deviceId);
        body.put("deviceName", deviceName);
        body.put("platform", "android");
        body.put("appVersion", "0.1.0");
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", token))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
