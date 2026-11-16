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
import java.util.List;
import java.util.UUID;
import kh.cmt.schedule.api.SpecialDays;
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

/** Step 5.3: date changes ("Exam day") and holidays; a holiday wins over a date change, the most specific change wins. */
@ApiTest
class DateChangesHolidaysTest {

    private static final String CHANGES = "/api/v1/admin/schedules/date-changes";
    private static final String HOLIDAYS = "/api/v1/admin/holidays";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;
    @Autowired SpecialDays specialDays;

    private UUID school;
    private Caller admin;
    private LocalDate today;
    private String exam;
    private String full;
    private String primary;
    private String office;
    private String sokha;   // primary
    private String dara;    // office

    @BeforeEach
    void schoolWithTemplatesAndPeople() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Dates school', now(), now())")
                .param("id", school).param("c", "D" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        today = LocalDate.now(clock);
        exam = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Exam day\",\"shifts\":[{\"start\":\"07:00\",\"end\":\"12:00\"}]}"));
        full = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Full day\",\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}"));
        primary = id(send(post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"Primary school\"}}"));
        office = id(send(post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"Front office\"}}"));
        sokha = person("Sokha Lim", primary);
        dara = person("Dara Chan", office);
    }

    @Test
    void dateChangesForEveryoneADepartmentAndAPerson_mostSpecificWins() throws Exception {
        LocalDate d1 = today.plusDays(10);
        LocalDate d2 = today.plusDays(11);
        JsonNode saved = body(changes("{\"dates\":[\"" + d2 + "\",\"" + d1 + "\",\"" + d1 + "\"],\"templateId\":\"" + exam
                + "\",\"label\":\"  Exam day \"}").andExpect(status().isCreated()));
        assertThat(saved.get("changes")).hasSize(2);   // the date picked twice counts once
        assertThat(saved.get("changes").get(0).get("date").asText()).isEqualTo(d1.toString());
        assertThat(saved.get("changes").get(0).get("label").asText()).isEqualTo("Exam day");
        assertThat(saved.get("changes").get(0).get("template").get("name").asText()).isEqualTo("Exam day");
        assertThat(saved.get("changes").get(0).get("who").get("type").asText()).isEqualTo("EVERYONE");
        assertThat(saved.get("replaced").asInt()).isZero();
        assertThat(saved.get("affectedPeople").asInt()).isEqualTo(2);

        // the office is off on d1; Sokha works a full day on d1
        JsonNode dept = body(changes("{\"dates\":[\"" + d1 + "\"],\"target\":\"department\",\"targetId\":\"" + office + "\"}"));
        assertThat(dept.get("changes").get(0).get("off").asBoolean()).isTrue();
        assertThat(dept.get("changes").get(0).get("who").get("name").get("en").asText()).isEqualTo("Front office");
        assertThat(dept.get("affectedPeople").asInt()).isEqualTo(1);
        JsonNode own = body(changes("{\"dates\":[\"" + d1 + "\"],\"target\":\"person\",\"targetId\":\"" + sokha + "\",\"templateId\":\"" + full + "\"}"));
        assertThat(own.get("changes").get(0).get("who").get("staffId").asText()).isNotBlank();

        asNobody(() -> {
            assertThat(specialDays.dateChangeOn(UUID.fromString(sokha), UUID.fromString(primary), d1).orElseThrow())
                    .satisfies(c -> {
                        assertThat(c.source()).isEqualTo("PERSON");
                        assertThat(c.templateId()).isEqualTo(UUID.fromString(full));
                    });
            assertThat(specialDays.dateChangeOn(UUID.fromString(dara), UUID.fromString(office), d1).orElseThrow())
                    .satisfies(c -> {
                        assertThat(c.source()).isEqualTo("DEPARTMENT");
                        assertThat(c.templateId()).isNull();   // off
                    });
            assertThat(specialDays.dateChangeOn(UUID.fromString(dara), UUID.fromString(office), d2).orElseThrow().label())
                    .isEqualTo("Exam day");
            assertThat(specialDays.dateChangeOn(UUID.fromString(dara), UUID.fromString(office), d2.plusDays(1))).isEmpty();
        });

        // saving the same date and target again replaces it
        JsonNode again = body(changes("{\"dates\":[\"" + d2 + "\"],\"templateId\":\"" + full + "\",\"label\":\"Full day\"}"));
        assertThat(again.get("replaced").asInt()).isEqualTo(1);
        JsonNode list = body(mvc.perform(get(CHANGES).header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(list).hasSize(4);
        assertThat(list.get(3).get("date").asText()).isEqualTo(d2.toString());
        assertThat(list.get(3).get("label").asText()).isEqualTo("Full day");

        // removed: the weekly schedule applies again that day
        String id = list.get(3).get("id").asText();
        mvc.perform(delete(CHANGES + "/" + id).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        assertThat(body(mvc.perform(get(CHANGES).header("Authorization", admin.bearer())))).hasSize(3);
    }

    @Test
    void dateChangeChecks_andThePastStays() throws Exception {
        expect(changes("{\"dates\":[]}"), "dates:NotEmpty");
        expect(changes("{\"dates\":[\"" + today.minusDays(1) + "\"]}"), "dates:Past");
        expect(changes("{\"dates\":[\"" + today + "\"],\"templateId\":\"" + UUID.randomUUID() + "\"}"), "templateId:UnknownTemplate");
        expect(changes("{\"dates\":[\"" + today + "\"],\"label\":\"" + "x".repeat(41) + "\"}"), "label:Size");
        expect(changes("{\"dates\":[\"" + today + "\"],\"target\":\"team\"}"), "target:OneOf");
        expect(changes("{\"dates\":[\"" + today + "\"],\"target\":\"person\"}"), "targetId:NotNull");
        changes("{\"dates\":[\"" + today + "\"],\"target\":\"person\",\"targetId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isNotFound());

        // today is still allowed; once it is in the past it can no longer be removed
        String id = body(changes("{\"dates\":[\"" + today + "\"],\"templateId\":\"" + exam + "\"}")).get("changes").get(0).get("id").asText();
        jdbc.sql("update schedule_date_change set change_date = :d where id = :id").param("d", today.minusDays(2))
                .param("id", UUID.fromString(id)).update();
        mvc.perform(delete(CHANGES + "/" + id).header("Authorization", admin.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_THE_PAST"));
        // a template on a planned date change cannot be retired
        String planned = body(changes("{\"dates\":[\"" + today.plusDays(3) + "\"],\"templateId\":\"" + full + "\"}"))
                .get("changes").get(0).get("id").asText();
        mvc.perform(delete("/api/v1/admin/shift-templates/" + full).header("Authorization", admin.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_USE"));
        mvc.perform(delete(CHANGES + "/" + planned).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/admin/shift-templates/" + full).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
    }

    @Test
    void holidaysWinOverDateChanges() throws Exception {
        LocalDate from = today.plusDays(20);
        JsonNode water = body(holiday("{\"name\":{\"km\":\"បុណ្យអុំទូក\",\"en\":\"Water Festival\"},\"kind\":\"national\",\"startDate\":\""
                + from + "\",\"endDate\":\"" + from.plusDays(2) + "\"}").andExpect(status().isCreated()));
        assertThat(water.get("kind").asText()).isEqualTo("NATIONAL");
        assertThat(water.get("days").asInt()).isEqualTo(3);
        assertThat(water.get("who").get("type").asText()).isEqualTo("EVERYONE");
        assertThat(water.get("started").asBoolean()).isFalse();
        JsonNode sports = body(holiday("{\"name\":{\"en\":\"Sports day\"},\"kind\":\"SCHOOL\",\"startDate\":\"" + from.plusDays(5)
                + "\",\"departmentId\":\"" + primary + "\"}").andExpect(status().isCreated()));
        assertThat(sports.get("endDate").asText()).isEqualTo(from.plusDays(5).toString());   // one day when left out
        assertThat(sports.get("who").get("name").get("en").asText()).isEqualTo("Primary school");

        // a date change on a holiday is kept, but flagged: it does not apply that day
        JsonNode saved = body(changes("{\"dates\":[\"" + from.plusDays(1) + "\",\"" + from.plusDays(5) + "\",\"" + from.plusDays(6)
                + "\"],\"templateId\":\"" + exam + "\",\"label\":\"Exam day\"}"));
        assertThat(saved.get("holidayDates")).hasSize(1);   // Sports day is only Primary's, not everyone's
        assertThat(saved.get("holidayDates").get(0).asText()).isEqualTo(from.plusDays(1).toString());
        assertThat(saved.get("changes").get(0).get("holiday").asBoolean()).isTrue();
        assertThat(saved.get("changes").get(1).get("holiday").asBoolean()).isFalse();
        JsonNode forPrimary = body(changes("{\"dates\":[\"" + from.plusDays(5) + "\"],\"target\":\"department\",\"targetId\":\""
                + primary + "\",\"templateId\":\"" + full + "\"}"));
        assertThat(forPrimary.get("holidayDates")).hasSize(1);

        asNobody(() -> {
            assertThat(specialDays.holidayOn(UUID.fromString(office), from.plusDays(2)).orElseThrow().name().en()).isEqualTo("Water Festival");
            assertThat(specialDays.holidayOn(UUID.fromString(primary), from.plusDays(5)).orElseThrow().kind()).isEqualTo("SCHOOL");
            assertThat(specialDays.holidayOn(UUID.fromString(office), from.plusDays(5))).isEmpty();   // not the office's
            assertThat(specialDays.holidayOn(null, from.plusDays(3))).isEmpty();
        });

        JsonNode year = body(mvc.perform(get(HOLIDAYS).param("year", String.valueOf(from.getYear())).header("Authorization", admin.bearer())));
        JsonNode first = null;
        for (JsonNode h : year) {
            if (h.get("id").asText().equals(water.get("id").asText())) {
                first = h;
            }
        }
        assertThat(first).isNotNull();
        assertThat(first.get("dateChanges").asInt()).isEqualTo(1);   // the Exam day it overrides

        // change and remove before it starts
        JsonNode moved = body(mvc.perform(put(HOLIDAYS + "/" + water.get("id").asText()).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":{\"en\":\"Water Festival\"},\"kind\":\"NATIONAL\",\"startDate\":\""
                        + from.plusDays(1) + "\",\"endDate\":\"" + from.plusDays(3) + "\",\"version\":0}")).andExpect(status().isOk()));
        assertThat(moved.get("startDate").asText()).isEqualTo(from.plusDays(1).toString());
        mvc.perform(put(HOLIDAYS + "/" + water.get("id").asText()).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":{\"en\":\"W\"},\"kind\":\"NATIONAL\",\"startDate\":\""
                        + from + "\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(delete(HOLIDAYS + "/" + sports.get("id").asText()).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        mvc.perform(get(HOLIDAYS + "/" + sports.get("id").asText()).header("Authorization", admin.bearer())).andExpect(status().isNotFound());
    }

    @Test
    void holidayChecks_andStartedOnesStay() throws Exception {
        expect(holiday("{\"kind\":\"NATIONAL\",\"startDate\":\"" + today.plusDays(1) + "\"}"), "name:NotBlank");
        expect(holiday("{\"name\":{\"en\":\"X\"},\"kind\":\"PUBLIC\",\"startDate\":\"" + today.plusDays(1) + "\"}"), "kind:OneOf");
        expect(holiday("{\"name\":{\"en\":\"X\"},\"kind\":\"SCHOOL\"}"), "startDate:NotNull");
        expect(holiday("{\"name\":{\"en\":\"X\"},\"kind\":\"SCHOOL\",\"startDate\":\"" + today.minusDays(1) + "\"}"), "startDate:Past");
        expect(holiday("{\"name\":{\"en\":\"X\"},\"kind\":\"SCHOOL\",\"startDate\":\"" + today.plusDays(5) + "\",\"endDate\":\""
                + today.plusDays(4) + "\"}"), "endDate:EndBeforeStart");
        expect(holiday("{\"name\":{\"en\":\"X\"},\"kind\":\"SCHOOL\",\"startDate\":\"" + today.plusDays(1) + "\",\"endDate\":\""
                + today.plusDays(400) + "\"}"), "endDate:Range");
        expect(holiday("{\"name\":{\"en\":\"X\"},\"kind\":\"SCHOOL\",\"startDate\":\"" + today.plusDays(1) + "\",\"departmentId\":\""
                + UUID.randomUUID() + "\"}"), "departmentId:UnknownDepartment");

        String id = body(holiday("{\"name\":{\"en\":\"Today off\"},\"kind\":\"SCHOOL\",\"startDate\":\"" + today + "\"}")
                .andExpect(status().isCreated())).get("id").asText();
        jdbc.sql("update holiday set start_date = :d, end_date = :d where id = :id").param("d", today.minusDays(1))
                .param("id", UUID.fromString(id)).update();
        JsonNode started = body(mvc.perform(get(HOLIDAYS + "/" + id).header("Authorization", admin.bearer())));
        assertThat(started.get("started").asBoolean()).isTrue();
        mvc.perform(delete(HOLIDAYS + "/" + id).header("Authorization", admin.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_THE_PAST"));
    }

    @Test
    void whoMay() throws Exception {
        Caller viewer = TestJwt.webUser("viewer", school);
        Caller hr = TestJwt.webUser("hr", school);
        Caller academic = TestJwt.webUser("qr_manager", school);
        mvc.perform(get(CHANGES).header("Authorization", viewer.bearer())).andExpect(status().isOk());
        mvc.perform(get(HOLIDAYS).header("Authorization", viewer.bearer())).andExpect(status().isOk());
        mvc.perform(get(HOLIDAYS).header("Authorization", academic.bearer())).andExpect(status().isForbidden());
        mvc.perform(post(HOLIDAYS).header("Authorization", hr.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"en\":\"HR day\"},\"kind\":\"SCHOOL\",\"startDate\":\"" + today.plusDays(2) + "\"}"))
                .andExpect(status().isCreated());
        String id = body(changes("{\"dates\":[\"" + today.plusDays(2) + "\"]}")).get("changes").get(0).get("id").asText();
        // another school sees neither
        Caller other = TestJwt.webUser("super_admin");
        mvc.perform(delete(CHANGES + "/" + id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        assertThat(body(mvc.perform(get(CHANGES).header("Authorization", other.bearer())))).noneMatch(c -> c.get("id").asText().equals(id));
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions changes(String body) throws Exception {
        return send(post(CHANGES), body);
    }

    private ResultActions holiday(String body) throws Exception {
        return send(post(HOLIDAYS), body);
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String person(String name, String department) throws Exception {
        return id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"" + name + "\"},\"role\":\"staff\",\"departmentId\":\""
                + department + "\"}"));
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andExpect(status().isCreated())).get("id").asText();
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
