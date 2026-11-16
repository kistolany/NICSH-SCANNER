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
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.schedule.api.WeeklySchedules;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Step 5.2: weekly schedules for everyone, a department or a person; the most specific one wins. */
@ApiTest
class WeeklySchedulesTest {

    private static final String WEEKLY = "/api/v1/admin/schedules/weekly";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;
    @Autowired WeeklySchedules weeklySchedules;

    private UUID school;
    private Caller admin;
    private LocalDate today;
    private String split;
    private String saturday;
    private String full;
    private String exam;
    private String primary;
    private String office;
    private String sokha;   // primary
    private String vanna;   // primary, will get a week of her own
    private String dara;    // office

    @BeforeEach
    void schoolWithTemplatesAndPeople() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Week school', now(), now())")
                .param("id", school).param("c", "W" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        today = LocalDate.now(clock);
        split = id(post("/api/v1/admin/shift-templates", "{\"name\":\"Split day\",\"shifts\":[{\"name\":\"Morning\",\"start\":\"07:45\",\"end\":\"11:30\"},"
                + "{\"name\":\"Afternoon\",\"start\":\"13:30\",\"end\":\"17:00\"}]}"));
        saturday = id(post("/api/v1/admin/shift-templates", "{\"name\":\"Saturday morning\",\"shifts\":[{\"start\":\"07:30\",\"end\":\"11:30\"}]}"));
        full = id(post("/api/v1/admin/shift-templates", "{\"name\":\"Full day\",\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}"));
        exam = id(post("/api/v1/admin/shift-templates", "{\"name\":\"Exam day\",\"shifts\":[{\"start\":\"07:00\",\"end\":\"12:00\"}]}"));
        primary = id(post("/api/v1/admin/departments", "{\"name\":{\"en\":\"Primary school\"}}"));
        office = id(post("/api/v1/admin/departments", "{\"name\":{\"en\":\"Front office\"}}"));
        sokha = person("Sokha Lim", primary);
        vanna = person("Vanna Kim", primary);
        dara = person("Dara Chan", office);
    }

    @Test
    void theSchoolsWeekAndItsGroupedPreview() throws Exception {
        JsonNode saved = body(save("everyone", null, today, week(split, split, split, split, split, saturday, null)).andExpect(status().isOk()));
        assertThat(saved.get("affectedPeople").asInt()).isEqualTo(3);   // "Save & notify": everybody
        assertThat(saved.get("current").get("status").asText()).isEqualTo("CURRENT");
        assertThat(saved.get("current").get("days").get(0).get("template").get("name").asText()).isEqualTo("Split day");
        assertThat(saved.get("current").get("days").get(6).get("off").asBoolean()).isTrue();

        JsonNode preview = body(mvc.perform(get(WEEKLY + "/preview").header("Authorization", admin.bearer())));
        JsonNode groups = preview.get("groups");
        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).get("label").asText()).isEqualTo("Monday – Friday");
        assertThat(groups.get(0).get("lines").toString()).isEqualTo("[\"Morning · 07:45 – 11:30\",\"Afternoon · 13:30 – 17:00\"]");
        assertThat(groups.get(1).get("label").asText()).isEqualTo("Saturday");
        assertThat(groups.get(1).get("lines").get(0).asText()).isEqualTo("07:30 – 11:30");   // one shift: just the times
        assertThat(groups.get(2).get("label").asText()).isEqualTo("Sunday");
        assertThat(groups.get(2).get("off").asBoolean()).isTrue();

        // days apart with the same template: listed with commas
        save("everyone", null, today, week(split, full, split, full, full, saturday, null)).andExpect(status().isOk());
        JsonNode apart = body(mvc.perform(get(WEEKLY + "/preview").header("Authorization", admin.bearer()))).get("groups");
        assertThat(apart.get(0).get("label").asText()).isEqualTo("Monday, Wednesday");
        assertThat(apart.get(1).get("label").asText()).isEqualTo("Tuesday, Thursday, Friday");
    }

    @Test
    void theMostSpecificWeekWins() throws Exception {
        save("everyone", null, today, week(split, split, split, split, split, saturday, null)).andExpect(status().isOk());
        JsonNode department = body(save("department", primary, today, week(full, full, full, full, full, null, null)));
        assertThat(department.get("affectedPeople").asInt()).isEqualTo(2);   // Sokha and Vanna
        JsonNode own = body(save("person", vanna, today, week(exam, full, full, full, full, null, null)));
        assertThat(own.get("affectedPeople").asInt()).isEqualTo(1);

        assertThat(source("person", vanna, today)).isEqualTo("PERSON");
        assertThat(source("person", sokha, today)).isEqualTo("DEPARTMENT");
        assertThat(source("person", dara, today)).isEqualTo("EVERYONE");
        JsonNode sokhaPage = body(mvc.perform(get(WEEKLY).param("target", "person").param("id", sokha).header("Authorization", admin.bearer())));
        assertThat(sokhaPage.has("current")).isFalse();                       // no week of her own
        assertThat(sokhaPage.get("applies").get("source").asText()).isEqualTo("DEPARTMENT");

        // back to the level above from a date (planned): Vanna follows her department again tomorrow
        JsonNode back = body(save("person", vanna, today.plusDays(1), null, true).andExpect(status().isOk()));
        assertThat(back.get("upcoming").get(0).get("inherit").asBoolean()).isTrue();
        assertThat(source("person", vanna, today)).isEqualTo("PERSON");
        assertThat(source("person", vanna, today.plusDays(1))).isEqualTo("DEPARTMENT");
        save("department", primary, today.plusDays(2), null, true).andExpect(status().isOk());
        assertThat(source("person", sokha, today.plusDays(2))).isEqualTo("EVERYONE");

        // the same answer for the other modules (the resolver of step 5.4), whatever the caller's scope
        asNobody(() -> {
            WeeklySchedules.Week week = weeklySchedules.weekOf(UUID.fromString(vanna), today).orElseThrow();
            assertThat(week.source()).isEqualTo("PERSON");
            assertThat(week.templates().get(1)).isEqualTo(UUID.fromString(exam));
            assertThat(week.templates()).doesNotContainKey(6);   // Saturday off
        });
    }

    @Test
    void versionsGoForwardAndDaysMustBeComplete() throws Exception {
        save("everyone", null, today, week(split, split, split, split, split, saturday, null)).andExpect(status().isOk());
        JsonNode planned = body(save("everyone", null, today.plusDays(7), week(full, full, full, full, full, null, null)));
        assertThat(planned.get("upcoming")).hasSize(1);
        assertThat(planned.get("current").get("days").get(0).get("template").get("name").asText()).isEqualTo("Split day");
        // the same date again replaces the planned version
        JsonNode corrected = body(save("everyone", null, today.plusDays(7), week(exam, full, full, full, full, null, null)));
        assertThat(corrected.get("history")).hasSize(2);
        assertThat(corrected.get("upcoming").get(0).get("days").get(0).get("template").get("name").asText()).isEqualTo("Exam day");

        expect(save("everyone", null, today.minusDays(1), week(split, split, split, split, split, null, null)), "effectiveFrom:Past");
        expect(save("everyone", null, today, List.of(day(1, split), day(2, split))), "days:Weekdays");
        expect(save("everyone", null, today, List.of(day(1, split), day(1, split), day(2, null), day(3, null), day(4, null),
                day(5, null), day(6, null))), "days:Weekdays");
        expect(save("everyone", null, today, week(UUID.randomUUID().toString(), null, null, null, null, null, null)), "days[1].templateId:UnknownTemplate");
        expect(save("everyone", null, today, null, true), "inherit:NotForEveryone");
        expect(save("department", primary, today, week(split, null, null, null, null, null, null), true), "days:NotWithInherit");
        save("department", UUID.randomUUID().toString(), today, week(split, null, null, null, null, null, null)).andExpect(status().isNotFound());
        save("person", null, today, week(split, null, null, null, null, null, null)).andExpect(status().isBadRequest());
        mvc.perform(get(WEEKLY).param("target", "team").header("Authorization", admin.bearer())).andExpect(status().isBadRequest());
    }

    @Test
    void templatesKnowWhoUsesThem() throws Exception {
        save("everyone", null, today, week(split, split, split, split, split, saturday, null)).andExpect(status().isOk());
        save("person", vanna, today, week(full, full, full, full, full, null, null)).andExpect(status().isOk());

        Map<String, Integer> usedBy = new HashMap<>();
        body(mvc.perform(get("/api/v1/admin/shift-templates").header("Authorization", admin.bearer())))
                .forEach(t -> usedBy.put(t.get("name").asText(), t.get("usedBy").asInt()));
        assertThat(usedBy).containsEntry("Split day", 2).containsEntry("Saturday morning", 2).containsEntry("Full day", 1)
                .containsEntry("Exam day", 0);

        // a template in a week in force (or planned) cannot be retired; an unused one can
        mvc.perform(delete("/api/v1/admin/shift-templates/" + split).header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.code").value("IN_USE"));
        save("everyone", null, today.plusDays(3), week(exam, exam, exam, exam, exam, null, null)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/shift-templates/" + exam).header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.code").value("IN_USE"));   // planned
        String unused = id(post("/api/v1/admin/shift-templates", "{\"name\":\"Unused\",\"shifts\":[{\"start\":\"08:00\",\"end\":\"12:00\"}]}"));
        mvc.perform(delete("/api/v1/admin/shift-templates/" + unused).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        // and a retired template cannot be picked
        expect(save("everyone", null, today.plusDays(4), week(unused, null, null, null, null, null, null)), "days[1].templateId:UnknownTemplate");
    }

    @Test
    void whoMayManageWeeks() throws Exception {
        save("everyone", null, today, week(split, split, split, split, split, null, null)).andExpect(status().isOk());
        mvc.perform(get(WEEKLY).header("Authorization", TestJwt.webUser("viewer", school).bearer())).andExpect(status().isOk());
        mvc.perform(put(WEEKLY).header("Authorization", TestJwt.webUser("viewer", school).bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("effectiveFrom", today.toString(), "days", week(split, null, null, null, null, null, null)))))
                .andExpect(status().isForbidden());
        mvc.perform(get(WEEKLY).header("Authorization", TestJwt.webUser("qr_manager", school).bearer())).andExpect(status().isForbidden());
        mvc.perform(get(WEEKLY).param("target", "person").param("id", vanna).header("Authorization", TestJwt.webUser("super_admin").bearer()))
                .andExpect(status().isNotFound());   // another school
    }

    // ------------------------------------------------------------------ helpers

    private static List<Map<String, Object>> week(String... templatesMondayToSunday) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            days.add(day(i + 1, templatesMondayToSunday[i]));
        }
        return days;
    }

    private static Map<String, Object> day(int weekday, String template) {
        Map<String, Object> day = new HashMap<>();
        day.put("weekday", weekday);
        day.put("templateId", template);
        return day;
    }

    private ResultActions save(String target, String id, LocalDate from, List<Map<String, Object>> days) throws Exception {
        return save(target, id, from, days, false);
    }

    private ResultActions save(String target, String id, LocalDate from, List<Map<String, Object>> days, boolean inherit) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("effectiveFrom", from.toString());
        body.put("days", days);
        body.put("inherit", inherit);
        var request = put(WEEKLY).param("target", target).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (id != null) {
            request = request.param("id", id);
        }
        return mvc.perform(request);
    }

    private String source(String target, String id, LocalDate date) throws Exception {
        return body(mvc.perform(get(WEEKLY + "/preview").param("target", target).param("id", id).param("date", date.toString())
                .header("Authorization", admin.bearer()))).get("source").asText();
    }

    private String person(String name, String department) throws Exception {
        return id(post("/api/v1/admin/people", "{\"name\":{\"km\":\"ក\",\"en\":\"" + name + "\"},\"role\":\"staff\",\"departmentId\":\"" + department + "\"}"));
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andExpect(status().isCreated())).get("id").asText();
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Code that runs with the school but no signed-in user (as a job would). */
    private void asNobody(Runnable work) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("typ", "job").claim("school_id", school.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            work.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void expect(ResultActions result, String... fieldRules) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        List<String> found = new ArrayList<>();
        problem.get("errors").forEach(e -> found.add(e.get("field").asText() + ":" + e.get("rule").asText()));
        assertThat(found).contains(fieldRules);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
