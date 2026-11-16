package kh.cmt.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.shared.security.SchoolOverride;
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

/**
 * Step 7.4: "Send reason to admin" after a late check-in creates a LATE_REASON request in the approvals inbox; sending
 * again while it waits changes it; once decided it cannot change.
 */
@ApiTest
class LateReasonTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired AttendanceRecordRepository records;
    @Autowired Approvals approvals;

    private UUID school;
    private UUID person;
    private Caller app;

    @BeforeEach
    void aPersonWithAnAppLogin() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Late school', now(), now())")
                .param("id", school).param("c", "L" + school.toString().substring(0, 8)).update();
        person = newPerson("Navy Chhun");
        app = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), person);
    }

    @Test
    void theReasonGoesToTheInbox_changesWhileItWaits_andIsFixedOnceDecided() throws Exception {
        UUID late = record(person, LocalTime.of(7, 57));   // "Late 12 min · Check-in 07:57"

        JsonNode sent = body(send(app, late, "Traffic on Russian Blvd.").andExpect(status().isCreated()));
        assertThat(sent.get("type").asText()).isEqualTo("LATE_REASON");
        assertThat(sent.get("status").asText()).isEqualTo("PENDING");
        assertThat(sent.get("reason").asText()).isEqualTo("Traffic on Russian Blvd.");
        assertThat(sent.has("decidedAt")).isFalse();
        UUID approval = UUID.fromString(sent.get("id").asText());
        Map<String, Object> row = jdbc.sql("""
                select subject_type, person_id, details->>'lateMinutes' as late, details->>'checkInAt' as check_in, details->>'shiftStart' as start
                from approval where id = :id""").param("id", approval).query().singleRow();
        assertThat(row).containsEntry("subject_type", "ATTENDANCE_RECORD").containsEntry("person_id", person).containsEntry("late", "12")
                .containsEntry("start", "07:45");
        assertThat(row.get("check_in").toString()).isEqualTo(MONDAY.atTime(7, 57).atZone(PP).toInstant().toString());
        assertThat(lateReasonOn(late)).isEqualTo("Traffic on Russian Blvd.");

        // sent again while it waits: the same request, the new words
        JsonNode changed = body(send(app, late, "  Flat tyre on the way.  ").andExpect(status().isOk()));
        assertThat(changed.get("id").asText()).isEqualTo(approval.toString());
        assertThat(changed.get("reason").asText()).isEqualTo("Flat tyre on the way.");
        assertThat(jdbc.sql("select count(*) from approval where subject_id = :r").param("r", late).query(Long.class).single()).isEqualTo(1);
        assertThat(lateReasonOn(late)).isEqualTo("Flat tyre on the way.");

        // an admin rejected it and the decision took effect: it cannot change any more
        jdbc.sql("""
                update approval set status = 'REJECTED', decided_at = now(), effective_at = now(), applied_at = now(), comment = 'Not enough reason'
                where id = :id""").param("id", approval).update();
        JsonNode decided = body(send(app, late, "Really, a flat tyre").andExpect(status().isConflict()));
        assertThat(decided.get("code").asText()).isEqualTo("ALREADY_DECIDED");
        assertThat(decided.get("decision").asText()).isEqualTo("REJECTED");
        assertThat(decided.get("status").asInt()).isEqualTo(409);
        Approvals.View latest = SchoolOverride.callAs(school, () -> approvals.latest(Approvals.Type.LATE_REASON, late)).orElseThrow();
        assertThat(latest.status()).isEqualTo(Approvals.Status.REJECTED);
        assertThat(latest.comment()).isEqualTo("Not enough reason");
        assertThat(latest.reason()).isEqualTo("Flat tyre on the way.");
    }

    @Test
    void onlyOwnLateRecords_andAReason() throws Exception {
        UUID onTime = record(person, LocalTime.of(7, 48));
        assertThat(body(send(app, onTime, "Traffic").andExpect(status().isConflict())).get("code").asText()).isEqualTo("NOT_LATE");

        UUID late = record(person, MONDAY.plusDays(1), LocalTime.of(8, 5));
        send(app, late, "   ").andExpect(status().isBadRequest());
        send(app, late, "x".repeat(301)).andExpect(status().isBadRequest());
        send(app, UUID.randomUUID(), "Traffic").andExpect(status().isNotFound());

        UUID someoneElses = record(newPerson("Phalla Yim"), LocalTime.of(8, 5));
        send(app, someoneElses, "Traffic").andExpect(status().isNotFound());
        // an app login without a person, and a web user
        send(TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), null), late, "Traffic").andExpect(status().isNotFound());
        send(TestJwt.webUser("super_admin", school), late, "Traffic").andExpect(status().isForbidden());
        assertThat(jdbc.sql("select count(*) from approval where person_id = :p").param("p", person).query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** A check-in on Monday at {@code in} for a 07:45 – 17:00 shift with 5 min grace. */
    private UUID record(UUID who, LocalTime in) {
        return record(who, MONDAY, in);
    }

    private UUID record(UUID who, LocalDate day, LocalTime in) {
        AttendanceRecord.Plan plan = new AttendanceRecord.Plan(1, null, LocalTime.of(7, 45), LocalTime.of(17, 0), 5, true);
        return SchoolOverride.callAs(school, () -> records.saveAndFlush(AttendanceRecord.checkedIn(who, day, plan,
                day.atTime(in).atZone(PP).toInstant(), null, null, AttendanceRecord.Source.SCAN, PP))).getId();
    }

    private UUID newPerson(String name) throws Exception {
        String created = mvc.perform(post("/api/v1/admin/people").header("Authorization", TestJwt.webUser("super_admin", school).bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":{\"km\":\"ក\",\"en\":\"" + name + "\"},\"role\":\"staff\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(created).get("id").asText());
    }

    private String lateReasonOn(UUID record) {
        return jdbc.sql("select late_reason from attendance_record where id = :id").param("id", record).query(String.class).single();
    }

    private ResultActions send(Caller caller, UUID record, String reason) throws Exception {
        return mvc.perform(post("/api/v1/app/attendance/records/" + record + "/late-reason").header("Authorization", caller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("reason", reason))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
