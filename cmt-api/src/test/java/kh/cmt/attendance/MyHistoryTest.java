package kh.cmt.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.shared.security.SchoolOverride;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestClock;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Step 7.5 "done when filters change the summary like in the design" (SPEC §4.7): today is Thursday 15 Oct 2026; the
 * person works Monday – Friday 07:45 – 17:00 from Monday 28 Sep.
 * <pre>
 * Sep 28 on time · Sep 29 early · Sep 30 absent
 * Oct 1 on time · Oct 2 no check-out · Oct 5–9 on time · Oct 12 on time · Oct 13 late (reason sent) · Oct 14 absent · Oct 15 today, not yet
 * </pre>
 */
@ApiTest
class MyHistoryTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    static final LocalDate START = LocalDate.of(2026, 9, 28);
    static final int FULL_DAY = 560;   // 07:40 – 17:00

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestClock clock;
    @Autowired AttendanceRecordRepository records;

    private UUID school;
    private Caller admin;
    private Caller app;
    private UUID person;
    private UUID gate;
    private UUID late;

    @BeforeEach
    void threeWeeks() throws Exception {
        clock.set(START.minusDays(1).atTime(8, 0).atZone(PP).toInstant());
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'History school', now(), now())")
                .param("id", school).param("c", "H" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        String area = id(send(post("/api/v1/admin/areas"), "{\"name\":\"CMT School\",\"latitude\":11.5564,\"longitude\":104.9282,\"radiusMeters\":120}"));
        gate = UUID.fromString(id(send(post("/api/v1/admin/gates"), "{\"name\":\"Main gate\",\"areaId\":\"" + area + "\"}")));
        String template = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Full day\",\"graceMinutes\":5,\"earlyCheckOutNeedsReason\":true,"
                + "\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}"));
        person = UUID.fromString(id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ណាវី\",\"en\":\"Navy Chhun\"},\"role\":\"staff\"}")));
        StringBuilder days = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            days.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":")
                    .append(wd <= 5 ? "\"" + template + "\"" : "null").append("}");
        }
        send(put("/api/v1/admin/schedules/weekly?target=person&id=" + person), "{\"effectiveFrom\":\"" + START + "\",\"days\":[" + days + "]}")
                .andExpect(status().isOk());
        app = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), person);

        record("2026-09-28", "07:40", "17:00");
        record("2026-09-29", "07:40", "15:30");
        record("2026-10-01", "07:40", "17:00");
        record("2026-10-02", "07:40", null);
        for (String d : List.of("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-12")) {
            record(d, "07:40", "17:00");
        }
        late = record("2026-10-13", "07:57", "17:00");
        clock.set(TODAY.atTime(9, 0).atZone(PP).toInstant());
        mvc.perform(post("/api/v1/app/attendance/records/" + late + "/late-reason").header("Authorization", app.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Traffic on Russian Blvd.\"}")).andExpect(status().isCreated());
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @Test
    void theRangeChipsChangeTheSummary_theStatusChipsOnlyTheList() throws Exception {
        JsonNode week = page("range=week");   // Mon 12 – Thu 15
        assertThat(week.get("from").asText()).isEqualTo("2026-10-12");
        assertThat(week.get("to").asText()).isEqualTo("2026-10-15");
        assertSummary(week, 1, 1, 0, 1, 0, FULL_DAY + 543);
        assertThat(dates(week)).containsExactly("2026-10-14", "2026-10-13", "2026-10-12");   // newest first, today not yet
        assertThat(statuses(week)).containsExactly("ABSENT", "LATE", "ON_TIME");

        JsonNode month = page("range=month");   // 1 – 15 Oct
        assertSummary(month, 7, 1, 0, 2, 0, 7 * FULL_DAY + 543 + 0);   // the day without a check-out counts 0 minutes
        assertThat(dates(month)).hasSize(10).doesNotContain("2026-10-03", "2026-10-04", "2026-10-10", "2026-10-11");

        JsonNode last = page("range=last");   // September: the schedule starts on the 28th
        assertThat(last.get("from").asText()).isEqualTo("2026-09-01");
        assertThat(last.get("to").asText()).isEqualTo("2026-09-30");
        assertSummary(last, 1, 0, 1, 1, 0, FULL_DAY + 470);
        assertThat(statuses(last)).containsExactly("ABSENT", "EARLY", "ON_TIME");

        JsonNode custom = page("range=custom&from=2026-09-29&to=2026-10-02");
        assertSummary(custom, 1, 0, 1, 2, 0, FULL_DAY + 470);
        assertThat(statuses(custom)).containsExactly("MISSING", "ON_TIME", "ABSENT", "EARLY");

        // a status chip narrows the list, the summary stays the range's
        JsonNode lateOnly = page("range=month&status=LATE");
        assertThat(lateOnly.get("status").asText()).isEqualTo("LATE");
        assertThat(dates(lateOnly)).containsExactly("2026-10-13");
        assertSummary(lateOnly, 7, 1, 0, 2, 0, 7 * FULL_DAY + 543);
        assertThat(dates(page("range=month&status=absent"))).containsExactly("2026-10-14", "2026-10-02");   // absent + missing check-out
        assertThat(dates(page("range=month&status=LEAVE"))).isEmpty();
        assertThat(page("").get("from").asText()).isEqualTo("2026-10-01");   // this month by default
    }

    @Test
    void aDayInDetail_andTheCalendar() throws Exception {
        JsonNode day = body(ask("/api/v1/app/attendance/2026-10-13").andExpect(status().isOk()));
        assertThat(day.get("status").asText()).isEqualTo("LATE");
        assertThat(day.get("gate").get("name").asText()).isEqualTo("Main gate");
        assertThat(day.get("editedByAdmin").asBoolean()).isFalse();
        JsonNode shift = day.get("shifts").get(0);
        assertThat(shift.get("start").asText()).isEqualTo("07:45");
        assertThat(Instant.parse(shift.get("checkIn").asText())).isEqualTo(LocalDate.of(2026, 10, 13).atTime(7, 57).atZone(PP).toInstant());
        assertThat(shift.get("lateMinutes").asInt()).isEqualTo(12);
        assertThat(shift.get("lateReason").get("text").asText()).isEqualTo("Traffic on Russian Blvd.");
        assertThat(shift.get("lateReason").get("status").asText()).isEqualTo("PENDING");
        assertThat(shift.get("recordId").asText()).isEqualTo(late.toString());

        JsonNode absent = body(ask("/api/v1/app/attendance/2026-10-14").andExpect(status().isOk()));
        assertThat(absent.get("shifts").get(0).get("status").asText()).isEqualTo("ABSENT");
        assertThat(absent.get("shifts").get(0).has("checkIn")).isFalse();
        ask("/api/v1/app/attendance/2026-10-10").andExpect(status().isNotFound());   // Saturday
        ask("/api/v1/app/attendance/2026-10-15").andExpect(status().isNotFound());   // today, nothing yet
        ask("/api/v1/app/attendance/2026-10-20").andExpect(status().isNotFound());   // still to come

        JsonNode calendar = body(ask("/api/v1/app/attendance/calendar?month=2026-10").andExpect(status().isOk()));
        assertThat(calendar.get("month").asText()).isEqualTo("2026-10");
        List<String> dots = new ArrayList<>();
        calendar.get("days").forEach(d -> dots.add(d.get("date").asText().substring(8) + " " + d.get("status").asText()));
        assertThat(dots).containsExactly("01 ON_TIME", "02 MISSING", "05 ON_TIME", "06 ON_TIME", "07 ON_TIME", "08 ON_TIME", "09 ON_TIME",
                "12 ON_TIME", "13 LATE", "14 ABSENT");
    }

    @Test
    void wrongQueries_andWhoMayAsk() throws Exception {
        ask("/api/v1/app/attendance?range=year").andExpect(status().isBadRequest());
        ask("/api/v1/app/attendance?range=custom&from=2026-10-01").andExpect(status().isBadRequest());
        ask("/api/v1/app/attendance?range=custom&from=2026-10-05&to=2026-10-01").andExpect(status().isBadRequest());
        ask("/api/v1/app/attendance?range=custom&from=2025-01-01&to=2026-10-01").andExpect(status().isBadRequest());   // over a year
        ask("/api/v1/app/attendance?status=LOST").andExpect(status().isBadRequest());
        // another person sees only their own (nothing here)
        Caller other = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(),
                UUID.fromString(id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ផ\",\"en\":\"Phalla Yim\"},\"role\":\"staff\"}"))));
        JsonNode theirs = json.readTree(mvc.perform(get("/api/v1/app/attendance?range=month").header("Authorization", other.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(theirs.get("days")).isEmpty();
        mvc.perform(get("/api/v1/app/attendance").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private UUID record(String day, String in, String out) {
        LocalDate d = LocalDate.parse(day);
        AttendanceRecord.Plan plan = new AttendanceRecord.Plan(1, null, LocalTime.of(7, 45), LocalTime.of(17, 0), 5, true);
        return SchoolOverride.callAs(school, () -> {
            AttendanceRecord r = AttendanceRecord.checkedIn(person, d, plan, d.atTime(LocalTime.parse(in)).atZone(PP).toInstant(), gate, null,
                    AttendanceRecord.Source.SCAN, PP);
            if (out != null) {
                r.checkOut(d.atTime(LocalTime.parse(out)).atZone(PP).toInstant(), gate, null, null, PP);
            }
            return records.saveAndFlush(r);
        }).getId();
    }

    private void assertSummary(JsonNode page, int present, int late, int early, int absent, int leave, int minutes) {
        JsonNode s = page.get("summary");
        assertThat(List.of(s.get("present").asInt(), s.get("late").asInt(), s.get("early").asInt(), s.get("absent").asInt(), s.get("leave").asInt(),
                s.get("totalMinutes").asInt())).containsExactly(present, late, early, absent, leave, minutes);
    }

    private static List<String> dates(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("days").forEach(d -> out.add(d.get("date").asText()));
        return out;
    }

    private static List<String> statuses(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("days").forEach(d -> out.add(d.get("status").asText()));
        return out;
    }

    private JsonNode page(String query) throws Exception {
        return body(ask("/api/v1/app/attendance?" + query).andExpect(status().isOk()));
    }

    private ResultActions ask(String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", app.bearer()));
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
