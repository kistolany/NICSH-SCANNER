package kh.cmt.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Step 5.4: the app's Today card and My schedule, from the weekly schedule, date changes and holidays. */
@ApiTest
class MyScheduleTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    private UUID school;
    private Caller admin;
    private Caller staff;
    private LocalDate today;
    private LocalDate nextMonday;
    private String exam;
    private String primary;

    @BeforeEach
    void aSchoolWeek() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'My week school', now(), now())")
                .param("id", school).param("c", "M" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        today = LocalDate.now(clock);
        nextMonday = today.with(DayOfWeek.MONDAY).plusWeeks(1);
        String split = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Split day\",\"shifts\":[{\"name\":\"Morning\",\"start\":\"07:45\","
                + "\"end\":\"11:30\"},{\"name\":\"Afternoon\",\"start\":\"13:30\",\"end\":\"17:00\"}]}"));
        String saturday = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Saturday morning\",\"shifts\":[{\"start\":\"07:30\",\"end\":\"11:30\"}]}"));
        exam = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Exam day\",\"shifts\":[{\"start\":\"07:00\",\"end\":\"12:00\"}]}"));
        primary = id(send(post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"Primary school\"}}"));
        String sokha = id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"Sokha Lim\"},\"role\":\"staff\",\"departmentId\":\"" + primary + "\"}"));
        StringBuilder days = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            String t = wd <= 5 ? "\"" + split + "\"" : wd == 6 ? "\"" + saturday + "\"" : "null";
            days.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":").append(t).append("}");
        }
        send(put("/api/v1/admin/schedules/weekly"), "{\"effectiveFrom\":\"" + today + "\",\"days\":[" + days + "]}").andExpect(status().isOk());
        staff = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), UUID.fromString(sokha));
    }

    @Test
    void theTodayCardFollowsTheWeekday() throws Exception {
        JsonNode day = body(mvc.perform(get("/api/v1/app/me/schedule/today").header("Authorization", staff.bearer())).andExpect(status().isOk()));
        assertThat(day.get("date").asText()).isEqualTo(today.toString());
        assertThat(OffsetDateTime.parse(day.get("serverTime").asText()).toLocalDate()).isEqualTo(today);
        int wd = today.getDayOfWeek().getValue();
        assertThat(day.get("kind").asText()).isEqualTo(wd == 7 ? "OFF" : "WORK");
        assertThat(day.get("shifts")).hasSize(wd == 7 ? 0 : wd == 6 ? 1 : 2);
        if (wd <= 5) {
            assertThat(day.get("shifts").get(0).get("name").asText()).isEqualTo("Morning");
            assertThat(day.get("shifts").get(0).get("start").asText()).isEqualTo("07:45");
            assertThat(day.get("shifts").get(0).get("graceMinutes").asInt()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void myWeekShowsChangesHolidaysAndWhatIsComing() throws Exception {
        // next week: an exam day on Wednesday for Primary, a school holiday on Thursday; three weeks out another exam day
        send(post("/api/v1/admin/schedules/date-changes"), "{\"dates\":[\"" + nextMonday.plusDays(2) + "\"],\"target\":\"department\",\"targetId\":\""
                + primary + "\",\"templateId\":\"" + exam + "\",\"label\":\"Exam day\"}").andExpect(status().isCreated());
        send(post("/api/v1/admin/holidays"), "{\"name\":{\"en\":\"Sports day\"},\"kind\":\"SCHOOL\",\"startDate\":\"" + nextMonday.plusDays(3)
                + "\"}").andExpect(status().isCreated());
        LocalDate later = nextMonday.plusWeeks(2).plusDays(1);
        send(post("/api/v1/admin/schedules/date-changes"), "{\"dates\":[\"" + later + "\"],\"templateId\":\"" + exam
                + "\",\"label\":\"Mid-term\"}").andExpect(status().isCreated());

        JsonNode week = body(mvc.perform(get("/api/v1/app/me/schedule/week").param("date", nextMonday.plusDays(4).toString())
                .header("Authorization", staff.bearer())).andExpect(status().isOk()));
        assertThat(week.get("weekStart").asText()).isEqualTo(nextMonday.toString());
        assertThat(week.get("weekEnd").asText()).isEqualTo(nextMonday.plusDays(6).toString());
        JsonNode days = week.get("days");
        assertThat(days).hasSize(7);
        assertThat(days.get(0).get("kind").asText()).isEqualTo("WORK");
        assertThat(days.get(0).get("shifts")).hasSize(2);
        assertThat(days.get(2).get("changed").asBoolean()).isTrue();
        assertThat(days.get(2).get("label").asText()).isEqualTo("Exam day");
        assertThat(days.get(2).get("level").asText()).isEqualTo("DEPARTMENT");
        assertThat(days.get(2).get("shifts")).hasSize(1);
        assertThat(days.get(3).get("kind").asText()).isEqualTo("HOLIDAY");
        assertThat(days.get(3).get("holiday").get("name").get("en").asText()).isEqualTo("Sports day");
        assertThat(days.get(5).get("shifts")).hasSize(1);   // Saturday
        assertThat(days.get(6).get("kind").asText()).isEqualTo("OFF");

        // the usual week (the grouped rows) ignores the exam day and the holiday
        JsonNode usual = week.get("usual");
        assertThat(usual).hasSize(7);
        assertThat(usual.get(2).get("changed").asBoolean()).isFalse();
        assertThat(usual.get(2).get("shifts")).hasSize(2);
        assertThat(usual.get(3).get("kind").asText()).isEqualTo("WORK");
        assertThat(usual.get(5).get("shifts")).hasSize(1);
        assertThat(usual.get(6).get("kind").asText()).isEqualTo("OFF");

        // upcoming changes run from today, whatever week is asked for
        JsonNode upcoming = week.get("upcoming");
        assertThat(upcoming).extracting(d -> d.get("date").asText())
                .containsExactly(nextMonday.plusDays(2).toString(), nextMonday.plusDays(3).toString(), later.toString());
        assertThat(upcoming.get(1).get("kind").asText()).isEqualTo("HOLIDAY");
        assertThat(upcoming.get(2).get("label").asText()).isEqualTo("Mid-term");
        JsonNode now = body(mvc.perform(get("/api/v1/app/me/schedule/week").header("Authorization", staff.bearer())));
        assertThat(now.get("weekStart").asText()).isEqualTo(today.with(DayOfWeek.MONDAY).toString());
        assertThat(now.get("upcoming")).hasSize(3);
    }

    @Test
    void onlyAppUsersWithAPerson() throws Exception {
        Caller noPerson = TestJwt.appUser("staff");
        mvc.perform(get("/api/v1/app/me/schedule/today").header("Authorization", noPerson.bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/app/me/schedule/week").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
