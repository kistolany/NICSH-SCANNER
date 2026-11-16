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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.attendance.application.DayCloseService;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.shared.events.OutboxRelay;
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
 * Step 7.8 "done when a test day without scans becomes ABSENT, not on holidays": everyone works Monday – Friday
 * 07:45 – 17:00 from Monday 28 Sep; Friday 9 Oct is a school holiday. On Tuesday 13 Oct Dara checked in and out, Sokha
 * checked in and never out, Bopha did not scan.
 */
@ApiTest
class DayCloseTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate TUESDAY = LocalDate.of(2026, 10, 13);
    static final int SHIFT = 555;   // 07:45 – 17:00

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestClock clock;
    @Autowired AttendanceRecordRepository records;
    @Autowired DayCloseService days;
    @Autowired OutboxRelay relay;

    private UUID school;
    private Caller admin;
    private UUID dara;
    private UUID sokha;
    private UUID bopha;

    @BeforeEach
    void aWeek() throws Exception {
        clock.set(LocalDate.of(2026, 9, 27).atTime(8, 0).atZone(PP).toInstant());
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Day close school', now(), now())")
                .param("id", school).param("c", "D" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        String full = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Full day\",\"graceMinutes\":5,\"earlyCheckOutNeedsReason\":true,"
                + "\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}"));
        dara = person("Dara Heng", full);
        sokha = person("Sokha Lim", full);
        bopha = person("Bopha Chan", full);
        send(post("/api/v1/admin/holidays"), "{\"name\":{\"en\":\"Sports day\"},\"kind\":\"SCHOOL\",\"startDate\":\"2026-10-09\",\"endDate\":\"2026-10-09\"}")
                .andExpect(status().isCreated());
        record(dara, TUESDAY, "07:40", "17:00");
        record(sokha, TUESDAY, "07:50", null);
        relay.drain();   // the schedule notices of the setup
        clock.set(TUESDAY.atTime(23, 40).atZone(PP).toInstant());
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @Test
    void aDayWithoutScansBecomesAbsent_aMissingCheckOutIsMarkedAndTold_andClosingAgainChangesNothing() throws Exception {
        DayCloseService.Result r = close(TUESDAY);
        assertThat(List.of(r.people(), r.absent(), r.missing())).containsExactly(3, 1, 1);

        assertThat(statusOf(bopha, TUESDAY)).containsExactly("ABSENT");
        assertThat(statusOf(sokha, TUESDAY)).containsExactly("MISSING");
        assertThat(statusOf(dara, TUESDAY)).containsExactly("ON_TIME");
        assertThat(summary(bopha, TUESDAY)).containsEntry("status", "ABSENT").containsEntry("absent", true).containsEntry("required_min", SHIFT)
                .containsEntry("worked_min", 0).containsEntry("closed", true);
        assertThat(summary(dara, TUESDAY)).containsEntry("status", "ON_TIME").containsEntry("worked_min", 560).containsEntry("absent", false);
        assertThat(summary(sokha, TUESDAY)).containsEntry("status", "MISSING");

        // "You forgot to check out", in the person's notifications
        relay.drain();
        JsonNode mine = body(mvc.perform(get("/api/v1/app/notifications").header("Accept-Language", "en")
                .header("Authorization", TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), sokha).bearer())).andExpect(status().isOk()));
        JsonNode first = mine.get("items").get(0);
        assertThat(first.get("kind").asText()).isEqualTo("ATTENDANCE");
        assertThat(first.get("code").asText()).isEqualTo("MISSING_CHECK_OUT");
        assertThat(first.get("title").asText()).isEqualTo("You forgot to check out");
        assertThat(first.get("body").asText()).isEqualTo("You did not check out on Tue 13 Oct (07:45 – 17:00). Request a correction in the app.");

        // closed again: no second absence, no second message
        DayCloseService.Result again = close(TUESDAY);
        assertThat(List.of(again.absent(), again.missing())).containsExactly(0, 0);
        assertThat(statusOf(bopha, TUESDAY)).hasSize(1);
        assertThat(jdbc.sql("select count(*) from outbox where type = 'attendance.check-out-missing' and payload->>'personId' = :p")
                .param("p", sokha.toString()).query(Long.class).single()).isEqualTo(1);

        // the staff app's history says the same as before (now from stored records)
        JsonNode day = body(mvc.perform(get("/api/v1/app/attendance/" + TUESDAY)
                .header("Authorization", TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), bopha).bearer())).andExpect(status().isOk()));
        assertThat(day.get("status").asText()).isEqualTo("ABSENT");
    }

    @Test
    void notOnHolidaysOrDaysOff() {
        LocalDate friday = LocalDate.of(2026, 10, 9);
        clock.set(friday.atTime(23, 40).atZone(PP).toInstant());
        assertThat(close(friday).absent()).isZero();
        assertThat(statusOf(bopha, friday)).isEmpty();
        assertThat(summary(bopha, friday)).containsEntry("status", "HOLIDAY").containsEntry("required_min", 0).containsEntry("absent", false);

        LocalDate saturday = friday.plusDays(1);
        clock.set(saturday.atTime(23, 40).atZone(PP).toInstant());
        assertThat(close(saturday).absent()).isZero();
        assertThat(jdbc.sql("select count(*) from attendance_daily_summary where work_date = :d and school_id = :s").param("d", saturday)
                .param("s", school).query(Long.class).single()).isZero();
    }

    @Test
    void theSchoolsMissingCheckOutRule() throws Exception {
        rule("AUTO_CLOSE_AT_SHIFT_END");
        assertThat(close(TUESDAY).missing()).isEqualTo(1);
        Map<String, Object> closed = jdbc.sql("select status, worked_minutes, edit_reason, out_at from attendance_record where person_id = :p")
                .param("p", sokha).query().singleRow();
        assertThat(closed).containsEntry("status", "ON_TIME").containsEntry("worked_minutes", 550)   // 07:50 is just within the grace
                .containsEntry("edit_reason", AttendanceRecord.AUTO_CLOSED);
        assertThat(eventsFor(sokha)).isZero();

        UUID late = person("Vanna Ouk", jdbc.sql("select id::text from shift_template where school_id = :s").param("s", school).query(String.class).single());
        LocalDate wednesday = TUESDAY.plusDays(1);
        record(late, wednesday, "07:40", null);
        rule("FLAG_ONLY");
        clock.set(wednesday.atTime(23, 40).atZone(PP).toInstant());
        close(wednesday);
        assertThat(statusOf(late, wednesday)).containsExactly("MISSING");
        assertThat(eventsFor(late)).isZero();   // flagged for the office, nobody told
    }

    @Test
    void whichDaysAreDue_andAChangeAfterTheCloseReachesTheSummary() throws Exception {
        // a school never closed starts with today, once past 23:30
        clock.set(TUESDAY.atTime(10, 0).atZone(PP).toInstant());
        assertThat(due()).isEmpty();
        clock.set(TUESDAY.atTime(23, 40).atZone(PP).toInstant());
        assertThat(due()).containsExactly(TUESDAY);
        close(TUESDAY);
        assertThat(due()).isEmpty();
        // the server was off from Wednesday to Sunday evening: the missed days are caught up
        clock.set(LocalDate.of(2026, 10, 18).atTime(23, 45).atZone(PP).toInstant());
        assertThat(due()).containsExactly(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16),
                LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18));
        // but never more than a week back
        clock.set(LocalDate.of(2026, 11, 30).atTime(9, 0).atZone(PP).toInstant());
        assertThat(due()).hasSize(7).startsWith(LocalDate.of(2026, 11, 23)).endsWith(LocalDate.of(2026, 11, 29));

        // the admin fixes Bopha's absent Tuesday after the close: the summary follows (outbox event)
        clock.set(LocalDate.of(2026, 10, 14).atTime(9, 0).atZone(PP).toInstant());
        Map<String, Object> rec = jdbc.sql("select id, version from attendance_record where person_id = :p").param("p", bopha).query().singleRow();
        send(put("/api/v1/admin/attendance/records/" + rec.get("id")), "{\"checkIn\":\"07:45\",\"checkOut\":\"17:00\",\"reason\":\"Paper register\","
                + "\"version\":" + rec.get("version") + "}").andExpect(status().isOk());
        relay.drain();
        assertThat(summary(bopha, TUESDAY)).containsEntry("status", "ON_TIME").containsEntry("absent", false).containsEntry("worked_min", SHIFT)
                .containsEntry("closed", true);
    }

    // ------------------------------------------------------------------ helpers

    private DayCloseService.Result close(LocalDate day) {
        return SchoolOverride.callAs(school, () -> days.close(day));
    }

    private List<LocalDate> due() {
        return SchoolOverride.callAs(school, () -> days.due());
    }

    private void rule(String rule) throws Exception {
        JsonNode now = body(mvc.perform(get("/api/v1/admin/settings/attendance").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) now).put("missingCheckOutRule", rule);
        send(put("/api/v1/admin/settings/attendance"), json.writeValueAsString(now)).andExpect(status().isOk());
    }

    private List<String> statusOf(UUID person, LocalDate day) {
        return jdbc.sql("select status from attendance_record where person_id = :p and work_date = :d order by shift_idx").param("p", person)
                .param("d", day).query(String.class).list();
    }

    private Map<String, Object> summary(UUID person, LocalDate day) {
        return jdbc.sql("select status, required_min, worked_min, absent, closed from attendance_daily_summary where person_id = :p and work_date = :d")
                .param("p", person).param("d", day).query().singleRow();
    }

    private long eventsFor(UUID person) {
        return jdbc.sql("select count(*) from outbox where type = 'attendance.check-out-missing' and payload->>'personId' = :p")
                .param("p", person.toString()).query(Long.class).single();
    }

    private UUID person(String name, String template) throws Exception {
        Instant before = clock.instant();
        clock.set(LocalDate.of(2026, 9, 27).atTime(8, 0).atZone(PP).toInstant());
        UUID p = UUID.fromString(id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"" + name + "\"},\"role\":\"staff\"}")));
        StringBuilder week = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            week.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":")
                    .append(wd <= 5 ? "\"" + template + "\"" : "null").append("}");
        }
        send(put("/api/v1/admin/schedules/weekly?target=person&id=" + p), "{\"effectiveFrom\":\"2026-09-28\",\"days\":[" + week + "]}")
                .andExpect(status().isOk());
        clock.set(before);
        return p;
    }

    private void record(UUID who, LocalDate day, String in, String out) {
        AttendanceRecord.Plan plan = new AttendanceRecord.Plan(1, null, LocalTime.of(7, 45), LocalTime.of(17, 0), 5, true);
        SchoolOverride.runAs(school, () -> {
            AttendanceRecord r = AttendanceRecord.checkedIn(who, day, plan, day.atTime(LocalTime.parse(in)).atZone(PP).toInstant(), null, null,
                    AttendanceRecord.Source.SCAN, PP);
            if (out != null) {
                r.checkOut(day.atTime(LocalTime.parse(out)).atZone(PP).toInstant(), null, null, null, PP);
            }
            records.saveAndFlush(r);
        });
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
