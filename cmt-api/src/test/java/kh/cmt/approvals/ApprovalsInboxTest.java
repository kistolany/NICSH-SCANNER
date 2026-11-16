package kh.cmt.approvals;

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
import kh.cmt.approvals.application.ApprovalEffects;
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
 * Step 10.1, the one inbox: counts per type and group, the detail sheet, a decision that takes effect 10 s later (Undo
 * until then), late / early reasons approved = excused, "Approve all", corrections applied to the record, the person told.
 * Navy works Monday – Friday 07:45 – 17:00 (5 min grace); today is Wednesday 4 Nov 2026, 09:00.
 */
@ApiTest
class ApprovalsInboxTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final String INBOX = "/api/v1/admin/approvals";
    static final LocalDate MON = LocalDate.of(2026, 11, 2);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestClock clock;
    @Autowired OutboxRelay relay;
    @Autowired ApprovalEffects effects;
    @Autowired AttendanceRecordRepository records;

    private UUID school;
    private Caller admin;
    private Caller navy;
    private UUID person;

    @BeforeEach
    void school() throws Exception {
        clock.set(LocalDate.of(2026, 10, 20).atTime(8, 0).atZone(PP).toInstant());
        relay.drain();
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Inbox school', now(), now())")
                .param("id", school).param("c", "I" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        String full = id(send(admin, post("/api/v1/admin/shift-templates"), "{\"name\":\"Full day\",\"graceMinutes\":5,\"earlyCheckOutNeedsReason\":true,"
                + "\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}"));
        person = UUID.fromString(id(send(admin, post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ណាវី\",\"en\":\"Navy Chhun\"},\"role\":\"staff\"}")));
        StringBuilder days = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            days.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":").append(wd <= 5 ? "\"" + full + "\"" : "null").append("}");
        }
        send(admin, put("/api/v1/admin/schedules/weekly?target=person&id=" + person), "{\"effectiveFrom\":\"2026-10-26\",\"days\":[" + days + "]}")
                .andExpect(status().isOk());
        navy = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), person);
        clock.set(LocalDate.of(2026, 11, 4).atTime(9, 0).atZone(PP).toInstant());
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @Test
    void anApprovedLateReason_isExcused_tenSecondsLater_andUndoBeforeThatPutsItBack() throws Exception {
        UUID late = checkIn(MON, LocalTime.of(7, 57), LocalTime.of(17, 2));   // late 12 min
        send(navy, post("/api/v1/app/attendance/records/" + late + "/late-reason"), "{\"reason\":\"Traffic on Russian Blvd.\"}")
                .andExpect(status().isCreated());

        JsonNode inbox = inbox("");
        assertThat(inbox.get("total").asInt()).isEqualTo(1);
        assertThat(inbox.get("counts").get("LATE_REASON").asInt()).isEqualTo(1);
        assertThat(inbox.get("groups").get("lateEarly").asInt()).isEqualTo(1);
        assertThat(inbox.get("groups").get("leave").asInt()).isZero();
        JsonNode item = inbox.get("items").get(0);
        assertThat(List.of(item.get("group").asText(), item.get("canDecide").asText(), item.get("details").get("lateMinutes").asText()))
                .containsExactly("lateEarly", "true", "12");
        String id = item.get("id").asText();
        // the mobile admin app sees the same inbox; without a web user it decides with the default role's limits
        Caller appAdmin = TestJwt.withPerson("app", "admin", school, UUID.randomUUID(), UUID.randomUUID());
        JsonNode phone = body(mvc.perform(get(INBOX).header("Authorization", appAdmin.bearer())).andExpect(status().isOk()));
        assertThat(List.of(phone.get("total").asText(), phone.get("items").get(0).get("canDecide").asText())).containsExactly("1", "true");
        assertThat(inbox("?group=lateEarly").get("items")).hasSize(1);
        assertThat(inbox("?group=leave").get("items")).isEmpty();
        mvc.perform(get(INBOX + "?group=teacherHours").header("Authorization", admin.bearer())).andExpect(status().isBadRequest());

        // the detail sheet: late once this month, one late reason sent
        JsonNode detail = body(mvc.perform(get(INBOX + "/" + id).header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(detail.get("facts").get("lateThisMonth").asInt()).isEqualTo(1);
        assertThat(detail.get("history").get("LATE_REASON").asInt()).isEqualTo(1);
        assertThat(detail.get("item").get("id").asText()).isEqualTo(id);

        // approved: shown at once, takes effect in 10 s — until then the record and the app are as they were
        JsonNode approved = body(mvc.perform(post(INBOX + "/" + id + "/approve").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(Instant.parse(approved.get("undoUntil").asText())).isEqualTo(clock.instant().plusSeconds(10));
        assertThat(inbox("").get("total").asInt()).isZero();
        assertThat(inbox("?status=done").get("items").get(0).has("undoUntil")).isTrue();
        assertThat(lateReasonStatus()).isEqualTo("PENDING");
        effects.applyDue();   // not yet due
        assertThat(excused(late)).isEqualTo("false LATE");

        // Undo: waiting again; nothing to undo then
        JsonNode undone = body(mvc.perform(post(INBOX + "/" + id + "/undo").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(undone.get("status").asText()).isEqualTo("PENDING");
        assertThat(undone.has("decidedAt")).isFalse();
        assertThat(inbox("").get("total").asInt()).isEqualTo(1);
        assertThat(code(mvc.perform(post(INBOX + "/" + id + "/undo").header("Authorization", admin.bearer())).andExpect(status().isConflict())))
                .isEqualTo("CONFLICT NOT_DECIDED");

        // approved again, and 10 s pass: excused — the minutes stay, the shift is not late any more
        mvc.perform(post(INBOX + "/" + id + "/approve").header("Authorization", admin.bearer())).andExpect(status().isOk());
        takeEffect();
        assertThat(excused(late)).isEqualTo("true ON_TIME");
        assertThat(jdbc.sql("select late_min || ' ' || status from attendance_daily_summary where person_id = :p and work_date = :d")
                .param("p", person).param("d", MON).query(String.class).single()).isEqualTo("0 ON_TIME");
        JsonNode shift = body(app(get("/api/v1/app/attendance/" + MON))).get("shifts").get(0);
        assertThat(List.of(shift.get("status").asText(), shift.get("lateMinutes").asText(), shift.get("lateExcused").asText(),
                shift.get("lateReason").get("status").asText())).containsExactly("ON_TIME", "12", "true", "APPROVED");
        assertThat(firstNotification()).isEqualTo("Late reason approved: Mon 2 Nov: being late 12 min is excused and does not count.");
        assertThat(code(mvc.perform(post(INBOX + "/" + id + "/undo").header("Authorization", admin.bearer())).andExpect(status().isConflict())))
                .isEqualTo("CONFLICT TOO_LATE");
        assertThat(inbox("?status=done").get("items").get(0).has("undoUntil")).isFalse();
    }

    @Test
    void approveAll_takesLateAndEarlyReasons_andARejectedReasonStillCounts() throws Exception {
        UUID late = checkIn(MON.plusDays(1), LocalTime.of(8, 5), null);                       // Tue, late 20 min
        UUID early = checkIn(MON, LocalTime.of(7, 40), LocalTime.of(15, 30));                  // Mon, left 90 min early
        send(navy, post("/api/v1/app/attendance/records/" + late + "/late-reason"), "{\"reason\":\"Flat tyre\"}").andExpect(status().isCreated());
        UUID earlyRequest = request("EARLY_REASON", early, "sick: Fever", Map.of("workDate", MON.toString(), "earlyMinutes", 90));
        UUID leave = request("LEAVE", UUID.randomUUID(), "Family", Map.of("days", 1));
        String lateRequest = inbox("?type=LATE_REASON").get("items").get(0).get("id").asText();

        assertThat(rules(send(admin, post(INBOX + "/bulk-approve"), "{\"ids\":[\"" + lateRequest + "\",\"" + leave + "\"]}"))).containsExactly("ids LateEarlyOnly");
        assertThat(rules(send(admin, post(INBOX + "/bulk-approve"), "{\"ids\":[]}"))).containsExactly("ids NotEmpty");
        UUID unknown = UUID.randomUUID();
        JsonNode bulk = body(send(admin, post(INBOX + "/bulk-approve"), "{\"ids\":[\"" + lateRequest + "\",\"" + earlyRequest + "\",\"" + unknown + "\"]}")
                .andExpect(status().isOk()));
        assertThat(bulk.get("approved")).extracting(i -> i.get("type").asText() + " " + i.get("status").asText())
                .containsExactly("LATE_REASON APPROVED", "EARLY_REASON APPROVED");
        assertThat(bulk.get("refused")).singleElement().satisfies(r -> {
            assertThat(r.get("id").asText()).isEqualTo(unknown.toString());
            assertThat(r.get("code").asText()).isEqualTo("NOT_FOUND");
        });
        assertThat(inbox("").get("groups").get("lateEarly").asInt()).isZero();
        takeEffect();
        assertThat(excused(late)).isEqualTo("true ON_TIME");
        assertThat(jdbc.sql("select early_excused || ' ' || status || ' ' || early_minutes from attendance_record where id = :id").param("id", early)
                .query(String.class).single()).isEqualTo("true ON_TIME 90");

        // rejected (a comment is needed): the late minutes still count; the person is told why
        UUID today = checkIn(MON.plusDays(2), LocalTime.of(8, 10), null);                      // Wed, late 25 min
        send(navy, post("/api/v1/app/attendance/records/" + today + "/late-reason"), "{\"reason\":\"Overslept\"}").andExpect(status().isCreated());
        String third = inbox("?type=LATE_REASON").get("items").get(0).get("id").asText();
        assertThat(rules(send(admin, post(INBOX + "/" + third + "/reject"), "{}"))).containsExactly("comment NotBlank");
        send(admin, post(INBOX + "/" + third + "/reject"), "{\"comment\":\"Please be on time\"}").andExpect(status().isOk());
        takeEffect();
        assertThat(excused(today)).isEqualTo("false LATE");
        assertThat(firstNotification()).isEqualTo("Late reason rejected: Wed 4 Nov: being late 25 min still counts. Please be on time");
        assertThat(jdbc.sql("select count(*) from notification where person_id = :p and kind = 'ATTENDANCE'").param("p", person)
                .query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void anApprovedCorrection_setsTheTime_andOneThatCannotBeAppliedIsRefused() throws Exception {
        UUID missing = checkIn(MON, LocalTime.of(7, 40), null);   // never checked out
        send(navy, post("/api/v1/app/attendance/corrections"), correction(MON, "CHECK_OUT", "17:05", "Forgot to scan out")).andExpect(status().isCreated());
        String id = inbox("?group=correction").get("items").get(0).get("id").asText();

        // HR may approve corrections of the last 7 days
        Caller hr = TestJwt.webUser("hr", school);
        mvc.perform(post(INBOX + "/" + id + "/approve").header("Authorization", hr.bearer())).andExpect(status().isOk());
        takeEffect();
        Map<String, Object> rec = jdbc.sql("select out_at, status, worked_minutes, edit_reason, edited_by from attendance_record where id = :id")
                .param("id", missing).query().singleRow();
        assertThat(rec.get("out_at").toString()).isNotNull();
        assertThat(((java.sql.Timestamp) rec.get("out_at")).toInstant()).isEqualTo(MON.atTime(17, 5).atZone(PP).toInstant());
        assertThat(rec).containsEntry("status", "ON_TIME").containsEntry("worked_minutes", 565).containsEntry("edit_reason", "Correction request approved")
                .containsEntry("edited_by", hr.userId());
        assertThat(firstNotification()).isEqualTo("Correction approved: Mon 2 Nov: your check-out is now 17:05.");
        JsonNode day = body(app(get("/api/v1/app/attendance/" + MON)));
        assertThat(day.get("shifts").get(0).get("corrections").get(0).get("status").asText()).isEqualTo("APPROVED");

        // a check-out for a day without a check-in cannot be applied: refused when approving; rejected instead
        send(navy, post("/api/v1/app/attendance/corrections"), correction(MON.plusDays(1), "CHECK_OUT", "17:00", "Gate was broken"))
                .andExpect(status().isCreated());
        String second = inbox("?group=correction").get("items").get(0).get("id").asText();
        assertThat(code(mvc.perform(post(INBOX + "/" + second + "/approve").header("Authorization", admin.bearer())).andExpect(status().isConflict())))
                .isEqualTo("CONFLICT NO_CHECK_IN");
        assertThat(inbox("").get("total").asInt()).isEqualTo(1);
        send(admin, post(INBOX + "/" + second + "/reject"), "{\"comment\":\"Ask for the check-in too\"}").andExpect(status().isOk());
        takeEffect();
        assertThat(firstNotification()).isEqualTo("Correction rejected: Tue 3 Nov, check-out 17:00: Ask for the check-in too");
    }

    // ------------------------------------------------------------------ helpers

    /** 11 s later: the decisions take effect, their events are handled. */
    private void takeEffect() {
        clock.set(clock.instant().plusSeconds(11));
        effects.applyDue();
        relay.drain();
    }

    /** Navy's shift on {@code day}: checked in at {@code in}, out at {@code out} (null: not yet). */
    private UUID checkIn(LocalDate day, LocalTime in, LocalTime out) {
        AttendanceRecord.Plan plan = new AttendanceRecord.Plan(1, null, LocalTime.of(7, 45), LocalTime.of(17, 0), 5, true);
        return SchoolOverride.callAs(school, () -> {
            AttendanceRecord r = AttendanceRecord.checkedIn(person, day, plan, day.atTime(in).atZone(PP).toInstant(), null, null,
                    AttendanceRecord.Source.SCAN, PP);
            if (out != null) {
                r.checkOut(day.atTime(out).atZone(PP).toInstant(), null, null, out.isBefore(plan.end()) ? "sick: Fever" : null, PP);
            }
            return records.saveAndFlush(r).getId();
        });
    }

    /** A waiting request as a module stores it. */
    private UUID request(String type, UUID subject, String reason, Map<String, Object> details) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into approval (id, school_id, type, subject_type, subject_id, person_id, requested_by, requested_at, reason, details, status,
                                      created_at, updated_at)
                values (:id, :s, :t, 'TEST', :sub, :p, :p, :at, :r, cast(:d as jsonb), 'PENDING', now(), now())""")
                .param("id", id).param("s", school).param("t", type).param("sub", subject).param("p", person)
                .param("at", java.sql.Timestamp.from(clock.instant())).param("r", reason).param("d", json.writeValueAsString(details)).update();
        return id;
    }

    private String correction(LocalDate date, String field, String time, String reason) {
        return "{\"date\":\"" + date + "\",\"shiftIdx\":1,\"field\":\"" + field + "\",\"time\":\"" + time + "\",\"reason\":\"" + reason + "\"}";
    }

    private String excused(UUID record) {
        return jdbc.sql("select late_excused || ' ' || status from attendance_record where id = :id").param("id", record).query(String.class).single();
    }

    private String lateReasonStatus() throws Exception {
        return body(app(get("/api/v1/app/attendance/" + MON))).get("shifts").get(0).get("lateReason").get("status").asText();
    }

    /** The newest notification, in English: "title: body". */
    private String firstNotification() throws Exception {
        JsonNode page = body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", navy.bearer()).header("Accept-Language", "en")));
        JsonNode first = (page.has("items") ? page.get("items") : page.get("content")).get(0);
        return first.get("title").asText() + ": " + first.get("body").asText();
    }

    private JsonNode inbox(String query) throws Exception {
        return body(mvc.perform(get(INBOX + query).header("Authorization", admin.bearer())).andExpect(status().isOk()));
    }

    private ResultActions app(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", navy.bearer()));
    }

    private List<String> rules(ResultActions result) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        return java.util.stream.StreamSupport.stream(problem.get("errors").spliterator(), false)
                .map(e -> e.get("field").asText() + " " + e.get("rule").asText()).toList();
    }

    /** "CODE" or "CODE REASON". */
    private String code(ResultActions result) throws Exception {
        JsonNode problem = body(result);
        return problem.get("code").asText() + (problem.has("reason") ? " " + problem.get("reason").asText() : "");
    }

    private ResultActions send(Caller caller, MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
