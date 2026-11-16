package kh.cmt.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Step 3.6, roles: change history with restore, and "view as this role". */
@ApiTest
class RoleHistoryTest {

    private static final String ROLES = "/api/v1/admin/roles";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private final Caller superAdmin = TestJwt.webUser("super_admin");

    @Test
    void everySavedChangeIsAVersionThatCanBeRestored() throws Exception {
        JsonNode role = createRole("Exam office " + suffix());
        String id = role.get("id").asText();
        role = save(id, "/permissions", "{\"permissions\":{\"leave\":[\"view\",\"edit\"]},\"version\":" + role.get("version") + "}");
        role = save(id, "/scope", "{\"type\":\"OWN_TEAM\",\"version\":" + role.get("version") + "}");
        int permVersion = role.get("permVersion").asInt();

        JsonNode history = history(id);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("version").asInt()).isEqualTo(3);           // newest first
        assertThat(history.get(0).get("change").asText()).isEqualTo("SCOPE");
        assertThat(history.get(0).get("differences").toString()).isEqualTo("[\"scope\"]");
        assertThat(history.get(0).get("changedBy").asText()).isEqualTo(superAdmin.userId().toString());
        assertThat(history.get(1).get("change").asText()).isEqualTo("PERMISSIONS");
        assertThat(history.get(2).get("change").asText()).isEqualTo("CREATED");
        assertThat(history.get(2).get("snapshot").get("permissions").size()).isZero();
        assertThat(history.get(0).get("restorable").asBoolean()).isFalse();   // the current version
        assertThat(history.get(2).get("restorable").asBoolean()).isTrue();

        // back to version 1: no permissions, all school
        JsonNode restored = body(mvc.perform(post(ROLES + "/" + id + "/history/1/restore").header("Authorization", superAdmin.bearer()))
                .andExpect(status().isOk()));
        assertThat(restored.get("permissions").size()).isZero();
        assertThat(restored.get("scope").get("type").asText()).isEqualTo("ALL");
        assertThat(restored.get("permVersion").asInt()).isEqualTo(permVersion + 1);   // applies on the next request
        JsonNode after = history(id);
        assertThat(after.get(0).get("change").asText()).isEqualTo("RESTORED");
        assertThat(after.get(0).get("restoredFrom").asInt()).isEqualTo(1);
        assertThat(after.get(0).get("differences").toString()).isEqualTo("[\"permissions\",\"scope\"]");

        // and that can be undone the same way
        JsonNode again = body(mvc.perform(post(ROLES + "/" + id + "/history/3/restore").header("Authorization", superAdmin.bearer())));
        assertThat(again.get("permissions").get("leave").toString()).isEqualTo("[\"view\",\"edit\"]");
        assertThat(again.get("scope").get("type").asText()).isEqualTo("OWN_TEAM");

        mvc.perform(post(ROLES + "/" + id + "/history/99/restore").header("Authorization", superAdmin.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRoleFromBeforeTheHistoryGetsItsStateSavedFirst() throws Exception {
        JsonNode role = createRole("Old role " + suffix());
        String id = role.get("id").asText();
        jdbc.sql("delete from role_version where role_id = :r").param("r", UUID.fromString(id)).update();   // as before V9

        save(id, "/permissions", "{\"permissions\":{\"people\":[\"view\"]},\"version\":" + role.get("version") + "}");
        JsonNode history = history(id);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).get("change").asText()).isEqualTo("CREATED");
        assertThat(history.get(1).get("snapshot").get("permissions").size()).isZero();   // the state before the change
    }

    @Test
    void systemRolesHaveAHistoryButCannotBeRestored() throws Exception {
        UUID school = newSchool();
        Caller admin = TestJwt.webUser("super_admin", school);
        JsonNode hr = roleByCode(admin, "hr");
        JsonNode history = body(mvc.perform(get(ROLES + "/" + hr.get("id").asText() + "/history").header("Authorization", admin.bearer())));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("change").asText()).isEqualTo("CREATED");
        assertThat(history.get(0).has("changedBy")).isFalse();   // the system
        assertThat(history.get(0).get("snapshot").get("permissions").has("people")).isTrue();

        // two-step verification is the one thing that changes on a system role: it is recorded too
        mvc.perform(put(ROLES + "/" + hr.get("id").asText() + "/two-factor").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"required\":true,\"version\":" + hr.get("version") + "}"))
                .andExpect(status().isOk());
        JsonNode after = body(mvc.perform(get(ROLES + "/" + hr.get("id").asText() + "/history").header("Authorization", admin.bearer())));
        assertThat(after.get(0).get("change").asText()).isEqualTo("TWO_FACTOR");
        assertThat(after.get(0).get("differences").toString()).isEqualTo("[\"loginRules\"]");
        assertThat(after.get(1).get("restorable").asBoolean()).isFalse();
        mvc.perform(post(ROLES + "/" + hr.get("id").asText() + "/history/1/restore").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.code").value("ROLE_LOCKED"));
    }

    @Test
    void aRetiredDepartmentIsLeftOutOfARestoredScope() throws Exception {
        String keep = department("Keep " + suffix());
        String retire = department("Retire " + suffix());
        JsonNode role = createRole("Scoped " + suffix());
        String id = role.get("id").asText();
        role = save(id, "/scope", "{\"type\":\"SELECTED_DEPARTMENTS\",\"departmentIds\":[\"" + keep + "\",\"" + retire + "\"],\"version\":"
                + role.get("version") + "}");
        save(id, "/scope", "{\"type\":\"ALL\",\"version\":" + role.get("version") + "}");
        mvc.perform(delete("/api/v1/admin/departments/" + retire).header("Authorization", superAdmin.bearer())).andExpect(status().isNoContent());

        JsonNode restored = body(mvc.perform(post(ROLES + "/" + id + "/history/2/restore").header("Authorization", superAdmin.bearer()))
                .andExpect(status().isOk()));
        assertThat(restored.get("scope").get("departmentIds").toString()).isEqualTo("[\"" + keep + "\"]");
    }

    @Test
    void viewAsARoleIsReadOnly() throws Exception {
        JsonNode qr = roleByCode(superAdmin, "qr_manager");
        JsonNode preview = body(mvc.perform(get(ROLES + "/" + qr.get("id").asText() + "/view-as").header("Authorization", superAdmin.bearer()))
                .andExpect(status().isOk()));
        assertThat(preview.get("readOnly").asBoolean()).isTrue();
        assertThat(preview.get("code").asText()).isEqualTo("qr_manager");
        assertThat(preview.get("permissions").get("qr").toString()).isEqualTo("[\"view\",\"create\",\"edit\"]");
        assertThat(preview.get("permissions").has("people")).isFalse();
        assertThat(preview.get("sensitiveFields").get("salaryBank").asBoolean()).isFalse();
        assertThat(preview.get("scope").get("type").asText()).isEqualTo("ALL");
        // nothing about the caller changed: still Super Admin
        mvc.perform(get(ROLES).header("Authorization", superAdmin.bearer())).andExpect(status().isOk());
    }

    @Test
    void nobodyRestoresTheirOwnRole() throws Exception {
        JsonNode role = createRole("Mine " + suffix());
        String id = role.get("id").asText();
        save(id, "/permissions", "{\"permissions\":{\"users\":[\"view\",\"edit\"]},\"version\":" + role.get("version") + "}");
        Caller holder = TestJwt.webUser(role.get("code").asText());
        mvc.perform(post(ROLES + "/" + id + "/history/1/restore").header("Authorization", holder.bearer()))
                .andExpect(jsonPath("$.code").value("OWN_ROLE"));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode createRole(String name) throws Exception {
        return body(mvc.perform(post(ROLES).header("Authorization", superAdmin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"en\":\"" + name + "\"}}")).andExpect(status().isCreated()));
    }

    private JsonNode save(String id, String part, String body) throws Exception {
        return body(mvc.perform(put(ROLES + "/" + id + part).header("Authorization", superAdmin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()));
    }

    private JsonNode history(String id) throws Exception {
        return body(mvc.perform(get(ROLES + "/" + id + "/history").header("Authorization", superAdmin.bearer())).andExpect(status().isOk()));
    }

    private String department(String name) throws Exception {
        return body(mvc.perform(post("/api/v1/admin/departments").header("Authorization", superAdmin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":{\"en\":\"" + name + "\"}}"))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode roleByCode(Caller caller, String code) throws Exception {
        for (JsonNode role : body(mvc.perform(get(ROLES).header("Authorization", caller.bearer())))) {
            if (role.get("code").asText().equals(code)) {
                return role;
            }
        }
        throw new AssertionError("no role " + code);
    }

    private UUID newSchool() {
        UUID school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'History school', now(), now())")
                .param("id", school).param("c", "H" + school.toString().substring(0, 8)).update();
        return school;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
