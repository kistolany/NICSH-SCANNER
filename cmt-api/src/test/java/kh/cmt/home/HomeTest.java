package kh.cmt.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.domain.ScanEvent;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.attendance.infrastructure.ScanEventRepository;
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
 * Step 7.9 "done when the app Home needs only this one call": today is Thursday 15 Oct 2026; the person works Monday –
 * Friday 07:45 – 17:00 from Thursday 1 Oct (22 working days in October).
 * <pre>
 * Oct 1, 2 on time · Oct 5 late · Oct 6 early · Oct 7 absent · Oct 8, 9, 12, 13 on time · Oct 14 checked in, never out
 * </pre>
 */
@ApiTest
class HomeTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestClock clock;
    @Autowired AttendanceRecordRepository records;
    @Autowired ScanEventRepository scans;

    private UUID school;
    private Caller admin;
    private Caller app;
    private UUID person;
    private UUID gate;
    private UUID yesterday;

    @BeforeEach
    void october() throws Exception {
        clock.set(LocalDate.of(2026, 9, 30).atTime(8, 0).atZone(PP).toInstant());
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Home school', now(), now())")
                .param("id", school).param("c", "M" + school.toString().substring(0, 8)).update();
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
        send(put("/api/v1/admin/schedules/weekly?target=person&id=" + person), "{\"effectiveFrom\":\"2026-10-01\",\"days\":[" + days + "]}")
                .andExpect(status().isOk());
        app = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), person);

        for (String d : List.of("2026-10-01", "2026-10-02", "2026-10-08", "2026-10-09", "2026-10-12", "2026-10-13")) {
            record(LocalDate.parse(d), "07:40", "17:00");
        }
        record(LocalDate.of(2026, 10, 5), "07:57", "17:00");
        record(LocalDate.of(2026, 10, 6), "07:40", "15:30");
        yesterday = record(YESTERDAY, "07:40", null);
        clock.set(TODAY.atTime(7, 30).atZone(PP).toInstant());
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @Test
    void beforeCheckIn_theMissingCheckOutOfYesterday_untilACorrectionIsAsked() throws Exception {
        JsonNode home = home();
        JsonNode today = home.get("today");
        assertThat(today.get("date").asText()).isEqualTo("2026-10-15");
        assertThat(today.get("serverTime").asText()).startsWith("2026-10-15T07:30:00+07:00");
        assertThat(today.get("kind").asText()).isEqualTo("WORK");
        assertThat(today.get("state").asText()).isEqualTo("MISSING_CHECK_OUT");
        assertThat(today.get("current").asInt()).isEqualTo(1);
        JsonNode missing = today.get("missingCheckOut");
        assertThat(missing.get("date").asText()).isEqualTo("2026-10-14");
        assertThat(missing.get("recordId").asText()).isEqualTo(yesterday.toString());
        assertThat(Instant.parse(missing.get("checkIn").asText())).isEqualTo(at(YESTERDAY, "07:40"));
        JsonNode shift = today.get("shifts").get(0);
        assertThat(shift.get("phase").asText()).isEqualTo("UPCOMING");
        assertThat(shift.get("status").asText()).isEqualTo("NOT_YET");
        assertThat(shift.get("start").asText()).isEqualTo("07:45");
        assertThat(shift.has("checkIn")).isFalse();

        // this month: Oct 1 – 14 (today has no row yet); 22 working days in October
        JsonNode month = home.get("month");
        assertThat(month.get("month").asText()).isEqualTo("2026-10");
        assertThat(List.of(month.get("present").asInt(), month.get("late").asInt(), month.get("absent").asInt(), month.get("leave").asInt(),
                month.get("elapsedDays").asInt(), month.get("workingDays").asInt())).containsExactly(7, 1, 2, 0, 10, 22);
        assertThat(home.get("leaveBalances")).isEmpty();   // this school has no leave types (LeaveBalancesTest has some)

        mvc.perform(post("/api/v1/app/attendance/corrections").header("Authorization", app.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-10-14\",\"field\":\"CHECK_OUT\",\"time\":\"17:05\",\"reason\":\"Forgot to scan\"}"))
                .andExpect(status().isCreated());
        JsonNode after = home().get("today");
        assertThat(after.get("state").asText()).isEqualTo("NOT_CHECKED_IN");
        assertThat(after.has("missingCheckOut")).isFalse();
    }

    @Test
    void outsideTheArea_thenCheckedInLate() throws Exception {
        clock.set(TODAY.atTime(7, 50).atZone(PP).toInstant());
        refusedOutside(TODAY.atTime(7, 50).atZone(PP).toInstant());
        JsonNode outside = home().get("today");
        assertThat(outside.get("state").asText()).isEqualTo("OUTSIDE_AREA");
        assertThat(outside.get("outside").get("distanceKm").asDouble()).isEqualTo(1.2);
        assertThat(outside.get("outside").get("areaName").asText()).isEqualTo("CMT School");

        clock.set(TODAY.atTime(10, 0).atZone(PP).toInstant());
        UUID late = record(TODAY, "07:57", null);
        mvc.perform(post("/api/v1/app/attendance/records/" + late + "/late-reason").header("Authorization", app.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Traffic on Russian Blvd.\"}")).andExpect(status().isCreated());
        JsonNode home = home();
        JsonNode working = home.get("today");
        assertThat(working.get("state").asText()).isEqualTo("WORKING");   // the refused scan came before the check-in
        assertThat(working.has("outside")).isFalse();
        assertThat(working.has("missingCheckOut")).isFalse();
        assertThat(working.get("gate").get("name").asText()).isEqualTo("Main gate");
        JsonNode shift = working.get("shifts").get(0);
        assertThat(shift.get("phase").asText()).isEqualTo("WORKING");
        assertThat(shift.get("status").asText()).isEqualTo("LATE");
        assertThat(shift.get("lateMinutes").asInt()).isEqualTo(12);
        assertThat(shift.get("recordId").asText()).isEqualTo(late.toString());
        assertThat(shift.get("lateReason").get("status").asText()).isEqualTo("PENDING");
        assertThat(Instant.parse(shift.get("checkIn").asText())).isEqualTo(at(TODAY, "07:57"));
        // today counts in the month now
        assertThat(home.get("month").get("late").asInt()).isEqualTo(2);
        assertThat(home.get("month").get("elapsedDays").asInt()).isEqualTo(11);
    }

    @Test
    void theThreeNewestNotifications_inTheAppsLanguage_andTheUnreadCount() throws Exception {
        for (int i = 1; i <= 4; i++) {
            jdbc.sql("insert into notification (id, school_id, person_id, kind, code, params, read_at, created_at, updated_at) values "
                            + "(:id, :s, :p, 'ATTENDANCE', 'MISSING_CHECK_OUT', cast(:params as jsonb), :read, :at, :at)")
                    .param("id", UUID.randomUUID()).param("s", school).param("p", person)
                    .param("params", "{\"date\":\"2026-10-0" + i + "\",\"times\":\"07:40\"}")
                    .param("read", i == 1 ? java.sql.Timestamp.from(Instant.now()) : null)
                    .param("at", java.sql.Timestamp.from(at(LocalDate.of(2026, 10, i), "23:30"))).update();
        }
        JsonNode notifications = body(mvc.perform(get("/api/v1/app/home").header("Authorization", app.bearer()).header("Accept-Language", "en"))
                .andExpect(status().isOk())).get("notifications");
        assertThat(notifications.get("unread").asInt()).isEqualTo(3);
        List<String> bodies = new ArrayList<>();
        notifications.get("items").forEach(n -> bodies.add(n.get("title").asText() + " · " + n.get("params").get("date").asText()));
        assertThat(bodies).containsExactly("You forgot to check out · 2026-10-04", "You forgot to check out · 2026-10-03",
                "You forgot to check out · 2026-10-02");
    }

    @Test
    void aHoliday_andWhoMayAsk() throws Exception {
        send(post("/api/v1/admin/holidays"), "{\"name\":{\"km\":\"ថ្ងៃឈប់\",\"en\":\"King's Birthday\"},\"kind\":\"NATIONAL\",\"startDate\":\"2026-10-15\",\"endDate\":\"2026-10-15\"}")
                .andExpect(status().isCreated());
        JsonNode today = home().get("today");
        assertThat(today.get("state").asText()).isEqualTo("HOLIDAY");
        assertThat(today.get("holiday").get("name").get("en").asText()).isEqualTo("King's Birthday");
        assertThat(today.get("shifts")).isEmpty();
        assertThat(today.has("missingCheckOut")).isFalse();   // only on a working day
        mvc.perform(get("/api/v1/app/home").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private UUID record(LocalDate d, String in, String out) {
        AttendanceRecord.Plan plan = new AttendanceRecord.Plan(1, null, LocalTime.of(7, 45), LocalTime.of(17, 0), 5, true);
        return SchoolOverride.callAs(school, () -> {
            AttendanceRecord r = AttendanceRecord.checkedIn(person, d, plan, at(d, in), gate, null, AttendanceRecord.Source.SCAN, PP);
            if (out != null) {
                r.checkOut(at(d, out), gate, null, null, PP);
            }
            return records.saveAndFlush(r);
        }).getId();
    }

    private void refusedOutside(Instant when) {
        ScanEvent.Facts facts = new ScanEvent.Facts(person, UUID.randomUUID(), null, UUID.randomUUID().toString(), ScanEvent.Source.ONLINE,
                when, when, null, gate, 1, new BigDecimal("11.5664"), new BigDecimal("104.9282"), BigDecimal.TEN, new BigDecimal("1100.0"),
                null, false, false, true, ScanEvent.Kind.CHECK_IN, "OUTSIDE_AREA", null, null, null);
        SchoolOverride.runAs(school, () -> scans.saveAndFlush(new ScanEvent(facts,
                Map.of("distanceKm", 1.2, "radiusKm", 0.12, "areaName", "CMT School"))));
    }

    private JsonNode home() throws Exception {
        return body(mvc.perform(get("/api/v1/app/home").header("Authorization", app.bearer())).andExpect(status().isOk()));
    }

    private static Instant at(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(PP).toInstant();
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
