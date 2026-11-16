package kh.cmt.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.iam.infrastructure.AuthLoginRuleSync;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import kh.cmt.support.TestRoles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Login rules per role (Roles page, "Security"; Security tab for two-step verification): saved, checked, and copied
 * to the auth server's {@code auth.role_login_rule} (where the auth server and the BFF read them).
 */
@ApiTest
class LoginRulesTest {

    private static final String ROLES = "/api/v1/admin/roles";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AuthLoginRuleSync sync;

    private final Caller superAdmin = TestJwt.webUser("super_admin");

    @Test
    void rulesAreSavedAndCopiedToTheAuthServer() throws Exception {
        JsonNode role = role("Front desk " + suffix());
        String code = role.get("code").asText();
        assertThat(authRow(code)).containsEntry("session_timeout_minutes", 480).containsEntry("days", "{1,2,3,4,5,6,7}");   // new role: copied too

        JsonNode saved = body(save(role.get("id").asText(), "{\"sessionTimeoutMinutes\":60,\"allowedFrom\":\"07:00\",\"allowedTo\":\"17:30\","
                + "\"allowedDays\":[5,1,3,1],\"allowedIps\":[\"192.168.1.0/24\",\" 10.0.0.5 \",\"\",\"10.0.0.5\"],\"version\":" + role.get("version") + "}")
                .andExpect(status().isOk()));
        JsonNode rules = saved.get("loginRules");
        assertThat(rules.get("sessionTimeoutMinutes").asInt()).isEqualTo(60);
        assertThat(rules.get("allowedFrom").asText()).isEqualTo("07:00");
        assertThat(rules.get("allowedTo").asText()).isEqualTo("17:30");
        assertThat(rules.get("allowedDays").toString()).isEqualTo("[1,3,5]");
        assertThat(rules.get("allowedIps").toString()).isEqualTo("[\"192.168.1.0/24\",\"10.0.0.5\"]");
        assertThat(saved.get("permVersion").asInt()).isEqualTo(role.get("permVersion").asInt() + 1);

        assertThat(authRow(code))
                .containsEntry("session_timeout_minutes", 60)
                .containsEntry("allowed_from", "07:00:00").containsEntry("allowed_to", "17:30:00")
                .containsEntry("days", "{1,3,5}").containsEntry("ips", "{192.168.1.0/24,10.0.0.5}")
                .containsEntry("two_factor_required", false);

        // a deleted role has no rules on the auth server any more
        mvc.perform(delete(ROLES + "/" + role.get("id").asText()).header("Authorization", superAdmin.bearer())).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from auth.role_login_rule where role = :r").param("r", code).query(Integer.class).single()).isZero();
    }

    @Test
    void twoStepVerificationCanBeRequiredForAnyRole() throws Exception {
        // in a school of its own, so the system roles other tests look at stay as they are
        UUID school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Two-step School', now(), now())")
                .param("id", school).param("c", "T" + school.toString().substring(0, 8)).update();
        Caller admin = TestJwt.webUser("super_admin", school);
        try {
            JsonNode hr = roleByCode("hr", admin);
            // the system roles of a new school are copied to the auth server when they are created
            assertThat(authRow(school, "super_admin")).containsEntry("session_timeout_minutes", 120);
            assertThat(authRow(school, "hr")).containsEntry("two_factor_required", false);
            JsonNode saved = body(mvc.perform(put(ROLES + "/" + hr.get("id").asText() + "/two-factor").header("Authorization", admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"required\":true,\"version\":" + hr.get("version") + "}"))
                    .andExpect(status().isOk()));
            assertThat(saved.get("loginRules").get("twoFactorRequired").asBoolean()).isTrue();
            assertThat(authRow(school, "hr")).containsEntry("two_factor_required", true);

            mvc.perform(put(ROLES + "/" + hr.get("id").asText() + "/two-factor").header("Authorization", admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"required\":false,\"version\":" + saved.get("version") + "}"))
                    .andExpect(status().isOk());
            assertThat(authRow(school, "hr")).containsEntry("two_factor_required", false);

            // not for your own role (you could lock yourself out), and not without Users edit
            JsonNode top = roleByCode("super_admin", admin);
            mvc.perform(put(ROLES + "/" + top.get("id").asText() + "/two-factor").header("Authorization", admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"required\":true,\"version\":" + top.get("version") + "}"))
                    .andExpect(jsonPath("$.code").value("OWN_ROLE"));
            mvc.perform(put(ROLES + "/" + top.get("id").asText() + "/two-factor").header("Authorization", TestJwt.webUser("viewer", school).bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"required\":true,\"version\":" + top.get("version") + "}"))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.sql("delete from auth.role_login_rule where school_id = :s").param("s", school).update();
            jdbc.sql("delete from role where school_id = :s").param("s", school).update();
            jdbc.sql("delete from school where id = :s").param("s", school).update();
        }
    }

    @Test
    void wrongRulesAreRefused() throws Exception {
        JsonNode role = role("Night guard " + suffix());
        String id = role.get("id").asText();
        String v = ",\"version\":" + role.get("version") + "}";

        expectProblem(save(id, "{\"sessionTimeoutMinutes\":5,\"allowedDays\":[1]" + v), "loginRules.sessionTimeoutMinutes", "Range");
        expectProblem(save(id, "{\"allowedDays\":[1]" + v), "loginRules.sessionTimeoutMinutes", "NotNull");
        expectProblem(save(id, "{\"sessionTimeoutMinutes\":60,\"allowedFrom\":\"07:00\",\"allowedDays\":[1]" + v), "loginRules.allowedTo", "BothOrNeither");
        expectProblem(save(id, "{\"sessionTimeoutMinutes\":60,\"allowedFrom\":\"07:00\",\"allowedTo\":\"07:00\",\"allowedDays\":[1]" + v), "loginRules.allowedTo", "Empty");
        expectProblem(save(id, "{\"sessionTimeoutMinutes\":60,\"allowedDays\":[]" + v), "loginRules.allowedDays", "NotEmpty");
        expectProblem(save(id, "{\"sessionTimeoutMinutes\":60,\"allowedDays\":[8]" + v), "loginRules.allowedDays", "Range");
        expectProblem(save(id, "{\"sessionTimeoutMinutes\":60,\"allowedDays\":[1],\"allowedIps\":[\"school.example.com\"]" + v), "loginRules.allowedIps", "InvalidAddress");
        expectProblem(save(id, "{\"sessionTimeoutMinutes\":60,\"allowedDays\":[1],\"allowedIps\":[\"10.0.0.0/99\"]" + v), "loginRules.allowedIps", "InvalidAddress");
        save(id, "{\"sessionTimeoutMinutes\":60,\"allowedFrom\":\"25:00\",\"allowedTo\":\"07:00\",\"allowedDays\":[1]" + v).andExpect(status().isBadRequest());

        // hours past midnight are fine: 22:00 to 06:00
        save(id, "{\"sessionTimeoutMinutes\":60,\"allowedFrom\":\"22:00\",\"allowedTo\":\"06:00\",\"allowedDays\":[1,2,3,4,5,6,7]" + v)
                .andExpect(status().isOk());

        save(roleByCode("hr").get("id").asText(), "{\"sessionTimeoutMinutes\":60,\"allowedDays\":[1],\"version\":0}")
                .andExpect(jsonPath("$.code").value("ROLE_LOCKED"));
    }

    @Test
    void atStartUpEveryRoleIsCopiedAgain() throws Exception {
        roleByCode("hr");   // the school has its system roles
        jdbc.sql("delete from auth.role_login_rule").update();
        sync.copyAll();
        assertThat(authRow("super_admin")).containsEntry("session_timeout_minutes", 120);
        assertThat(authRow("viewer")).containsEntry("session_timeout_minutes", 480).containsEntry("ips", "{}");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode role(String name) throws Exception {
        Caller holder = TestRoles.callerWithRole(mvc, json, name, Map.of("people", List.of("view")));
        String code = body(mvc.perform(get("/api/v1/admin/me").header("Authorization", holder.bearer()))).get("role").asText();
        return roleByCode(code);
    }

    private Map<String, Object> authRow(String role) {
        return authRow(TestJwt.SCHOOL, role);
    }

    private Map<String, Object> authRow(UUID school, String role) {
        return jdbc.sql("""
                        select two_factor_required, session_timeout_minutes, allowed_from::text, allowed_to::text,
                               allowed_days::text as days, allowed_ips::text as ips
                        from auth.role_login_rule where school_id = :s and role = :r""")
                .param("s", school).param("r", role).query().singleRow();
    }

    private ResultActions save(String roleId, String body) throws Exception {
        return mvc.perform(put(ROLES + "/" + roleId + "/login-rules").header("Authorization", superAdmin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void expectProblem(ResultActions result, String field, String rule) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].rule").value(rule));
    }

    private JsonNode roleByCode(String code) throws Exception {
        return roleByCode(code, superAdmin);
    }

    private JsonNode roleByCode(String code, Caller caller) throws Exception {
        for (JsonNode role : body(mvc.perform(get(ROLES).header("Authorization", caller.bearer())))) {
            if (role.get("code").asText().equals(code)) {
                return role;
            }
        }
        throw new AssertionError("no role " + code);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
