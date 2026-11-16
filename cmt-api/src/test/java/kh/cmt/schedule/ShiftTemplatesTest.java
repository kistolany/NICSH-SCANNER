package kh.cmt.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Step 5.1: shift templates with 1 or 2 shifts that never overlap. */
@ApiTest
class ShiftTemplatesTest {

    private static final String TEMPLATES = "/api/v1/admin/shift-templates";
    private static final String SPLIT = "{\"name\":\"Split day\",\"shifts\":[{\"name\":\"Morning\",\"start\":\"07:45\",\"end\":\"11:30\"},"
            + "{\"name\":\"Afternoon\",\"start\":\"13:30\",\"end\":\"17:00\"}]";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private UUID school;
    private Caller admin;

    @BeforeEach
    void schoolOfItsOwn() {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Shift school', now(), now())")
                .param("id", school).param("c", "S" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
    }

    @Test
    void aSplitDayTemplateWithTwoShifts() throws Exception {
        JsonNode split = body(create(SPLIT + ",\"color\":\"#2563eb\"}").andExpect(status().isCreated()));
        assertThat(split.get("shifts")).hasSize(2);
        assertThat(split.get("shifts").get(0).get("name").asText()).isEqualTo("Morning");
        assertThat(split.get("shifts").get(0).get("start").asText()).isEqualTo("07:45");
        assertThat(split.get("shifts").get(1).get("end").asText()).isEqualTo("17:00");
        assertThat(split.get("shifts").get(0).get("minutes").asInt()).isEqualTo(225);
        assertThat(split.get("totalMinutes").asInt()).isEqualTo(435);
        // grace and the early check-out rule come from the school's attendance settings when left out
        assertThat(split.get("graceMinutes").asInt()).isEqualTo(5);
        assertThat(split.get("earlyCheckOutNeedsReason").asBoolean()).isTrue();
        assertThat(split.get("color").asText()).isEqualTo("#2563EB");

        JsonNode saturday = body(create("{\"name\":\"Saturday morning\",\"shifts\":[{\"start\":\"07:30\",\"end\":\"11:30\"}],"
                + "\"graceMinutes\":10,\"earlyCheckOutNeedsReason\":false}").andExpect(status().isCreated()));
        assertThat(saturday.get("shifts").get(0).has("name")).isFalse();   // "Shift 1" on the page
        assertThat(saturday.get("graceMinutes").asInt()).isEqualTo(10);
        assertThat(saturday.get("color").asText()).isEqualTo("#EA580C");

        JsonNode list = body(mvc.perform(get(TEMPLATES).header("Authorization", admin.bearer())));
        assertThat(names(list)).containsExactly("Saturday morning", "Split day");
    }

    @Test
    void shiftsMustMakeSense() throws Exception {
        expect(create("{\"name\":\"\",\"shifts\":[]}"), "name:NotBlank", "shifts:Size");
        expect(create("{\"name\":\"Three\",\"shifts\":[{\"start\":\"07:00\",\"end\":\"08:00\"},{\"start\":\"09:00\",\"end\":\"10:00\"},"
                + "{\"start\":\"11:00\",\"end\":\"12:00\"}]}"), "shifts:Size");
        expect(create("{\"name\":\"Backwards\",\"shifts\":[{\"start\":\"17:00\",\"end\":\"07:45\"}]}"), "shifts[0].end:EndBeforeStart");
        expect(create("{\"name\":\"Overlap\",\"shifts\":[{\"start\":\"07:45\",\"end\":\"12:00\"},{\"start\":\"11:30\",\"end\":\"17:00\"}]}"),
                "shifts[1].start:Overlap");
        expect(create("{\"name\":\"No end\",\"shifts\":[{\"start\":\"07:45\"}]}"), "shifts[0]:NotNull");
        expect(create(SPLIT + ",\"graceMinutes\":121}"), "graceMinutes:Range");
        expect(create(SPLIT + ",\"color\":\"orange\"}"), "color:Pattern");
        // touching shifts are fine (the second starts when the first ends)
        create("{\"name\":\"Back to back\",\"shifts\":[{\"start\":\"07:00\",\"end\":\"12:00\"},{\"start\":\"12:00\",\"end\":\"16:00\"}]}")
                .andExpect(status().isCreated());
    }

    @Test
    void namesChangesInactiveAndForever() throws Exception {
        JsonNode split = body(create(SPLIT + "}"));
        create("{\"name\":\"split DAY\",\"shifts\":[{\"start\":\"07:00\",\"end\":\"12:00\"}]}").andExpect(jsonPath("$.code").value("NAME_TAKEN"));

        // the whole template with the version that was read: here it becomes one shift
        JsonNode one = body(update(split.get("id").asText(), "{\"name\":\"Split day\",\"shifts\":[{\"start\":\"08:00\",\"end\":\"16:00\"}],"
                + "\"version\":" + split.get("version") + "}").andExpect(status().isOk()));
        assertThat(one.get("shifts")).hasSize(1);
        assertThat(one.get("totalMinutes").asInt()).isEqualTo(480);
        update(split.get("id").asText(), SPLIT + ",\"version\":" + split.get("version") + "}")
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // inactive: hidden, its name free again; restore refused while the name is used
        mvc.perform(delete(TEMPLATES + "/" + one.get("id").asText()).param("reason", "Old timetable").header("Authorization", admin.bearer()))
                .andExpect(status().isNoContent());
        assertThat(names(body(mvc.perform(get(TEMPLATES).header("Authorization", admin.bearer()))))).isEmpty();
        JsonNode inactive = body(mvc.perform(get(TEMPLATES).param("status", "inactive").header("Authorization", admin.bearer())));
        assertThat(inactive.get(0).get("deleteReason").asText()).isEqualTo("Old timetable");
        JsonNode again = body(create(SPLIT + "}").andExpect(status().isCreated()));
        mvc.perform(post(TEMPLATES + "/" + one.get("id").asText() + "/restore").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        update(one.get("id").asText(), SPLIT + ",\"version\":" + (one.get("version").asLong() + 1) + "}")
                .andExpect(jsonPath("$.code").value("CONFLICT"));   // inactive: restore first

        // forever: Super Admin, inactive only
        mvc.perform(delete(TEMPLATES + "/" + again.get("id").asText() + "/forever").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.code").value("NOT_INACTIVE"));
        mvc.perform(delete(TEMPLATES + "/" + one.get("id").asText() + "/forever").header("Authorization", TestJwt.webUser("hr", school).bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(TEMPLATES + "/" + one.get("id").asText() + "/forever").header("Authorization", admin.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get(TEMPLATES + "/" + one.get("id").asText()).header("Authorization", admin.bearer())).andExpect(status().isNotFound());
    }

    @Test
    void whoMayManageTemplates() throws Exception {
        JsonNode split = body(create(SPLIT + "}"));
        // HR has Shifts; QR Manager does not; the Viewer only looks
        mvc.perform(post(TEMPLATES).header("Authorization", TestJwt.webUser("hr", school).bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Full day\",\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}")).andExpect(status().isCreated());
        mvc.perform(get(TEMPLATES).header("Authorization", TestJwt.webUser("qr_manager", school).bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.module").value("shifts"));
        mvc.perform(get(TEMPLATES + "/" + split.get("id").asText()).header("Authorization", TestJwt.webUser("viewer", school).bearer()))
                .andExpect(status().isOk());
        // another school sees nothing of it
        mvc.perform(get(TEMPLATES + "/" + split.get("id").asText()).header("Authorization", TestJwt.webUser("super_admin").bearer()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post(TEMPLATES).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(String id, String body) throws Exception {
        return mvc.perform(put(TEMPLATES + "/" + id).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void expect(ResultActions result, String... fieldRules) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        List<String> found = new ArrayList<>();
        problem.get("errors").forEach(e -> found.add(e.get("field").asText() + ":" + e.get("rule").asText()));
        assertThat(found).contains(fieldRules);
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.forEach(t -> names.add(t.get("name").asText()));
        return names;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
