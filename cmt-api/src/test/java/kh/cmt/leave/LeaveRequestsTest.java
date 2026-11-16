package kh.cmt.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kh.cmt.approvals.application.ApprovalEffects;
import kh.cmt.shared.events.OutboxRelay;
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
 * Step 9.2: leave requests from the app (days from the schedule, the school's rules), and deciding them in the inbox —
 * done when approving updates the balance and the days become LEAVE. Today is Wednesday 4 Nov 2026; Navy works
 * Monday – Friday 07:45 – 17:00; Friday 13 Nov is a school holiday.
 */
@ApiTest
class LeaveRequestsTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final String REQ = "/api/v1/app/leave/requests";
    static final String INBOX = "/api/v1/admin/approvals";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestClock clock;
    @Autowired OutboxRelay relay;
    @Autowired ApprovalEffects effects;

    private UUID school;
    private Caller admin;
    private Caller navy;
    private UUID person;
    private String annual;
    private String sick;
    private String maternity;

    @BeforeEach
    void school() throws Exception {
        clock.set(LocalDate.of(2026, 10, 20).atTime(8, 0).atZone(PP).toInstant());
        relay.drain();
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Leave school', now(), now())")
                .param("id", school).param("c", "R" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        annual = id(send(post("/api/v1/admin/leave-types"), "{\"name\":{\"en\":\"Annual leave\"},\"daysPerYear\":12,\"color\":\"#1D4ED8\"}"));
        sick = id(send(post("/api/v1/admin/leave-types"), "{\"name\":{\"en\":\"Sick leave\"},\"daysPerYear\":10,\"attachment\":\"OVER_ONE_DAY\","
                + "\"color\":\"#B91C1C\"}"));
        maternity = id(send(post("/api/v1/admin/leave-types"), "{\"name\":{\"en\":\"Maternity leave\"},\"daysPerYear\":90,\"halfDays\":false,"
                + "\"attachment\":\"ALWAYS\",\"color\":\"#15803D\"}"));
        String full = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Full day\",\"graceMinutes\":5,\"earlyCheckOutNeedsReason\":true,"
                + "\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}"));
        person = UUID.fromString(id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ណាវី\",\"en\":\"Navy Chhun\"},\"role\":\"staff\"}")));
        StringBuilder days = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            days.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":").append(wd <= 5 ? "\"" + full + "\"" : "null").append("}");
        }
        send(put("/api/v1/admin/schedules/weekly?target=person&id=" + person), "{\"effectiveFrom\":\"2026-10-26\",\"days\":[" + days + "]}")
                .andExpect(status().isOk());
        send(post("/api/v1/admin/holidays"), "{\"name\":{\"en\":\"Sports day\"},\"kind\":\"SCHOOL\",\"startDate\":\"2026-11-13\",\"endDate\":\"2026-11-13\"}")
                .andExpect(status().isCreated());
        navy = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), person);
        clock.set(LocalDate.of(2026, 11, 4).atTime(9, 0).atZone(PP).toInstant());
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @Test
    void requests_countWorkingDays_andFollowTheSchoolsRules() throws Exception {
        // the preview: Mon 9 – Fri 13 Nov is 4 days (the holiday does not count)
        JsonNode preview = body(app(get("/api/v1/app/leave/preview?typeId=" + annual + "&startDate=2026-11-09&endDate=2026-11-13")).andExpect(status().isOk()));
        assertThat(preview.get("days").asText()).isEqualTo("4.0");
        assertThat(preview.get("dates")).extracting(JsonNode::asText).containsExactly("2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12");
        assertThat(List.of(preview.get("left").asText(), preview.get("after").asText(), preview.get("enough").asText(), preview.get("shortNotice").asText(),
                preview.get("attachmentRequired").asText())).containsExactly("12.0", "8.0", "true", "false", "false");
        assertThat(body(app(get("/api/v1/app/leave/preview?typeId=" + sick + "&startDate=2026-11-09&endDate=2026-11-10"))).get("attachmentRequired")
                .asBoolean()).isTrue();
        assertThat(body(app(get("/api/v1/app/leave/preview?typeId=" + sick + "&startDate=2026-11-09"))).get("attachmentRequired").asBoolean()).isFalse();

        JsonNode sent = body(send(navy, post(REQ), request(annual, "2026-11-09", "2026-11-13", null, "Family trip")).andExpect(status().isCreated()));
        assertThat(List.of(sent.get("status").asText(), sent.get("days").asText(), sent.get("type").asText(), sent.get("shortNotice").asText()))
                .containsExactly("PENDING", "4.0", "annual", "false");
        JsonNode inbox = body(mvc.perform(get(INBOX).header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(inbox.get("counts").get("LEAVE").asInt()).isEqualTo(1);
        JsonNode item = inbox.get("items").get(0);
        assertThat(List.of(item.get("type").asText(), item.get("reason").asText(), item.get("person").get("name").get("en").asText(),
                item.get("details").get("days").asText(), item.get("details").get("leftAfter").asText(), item.get("decidable").asText()))
                .containsExactly("LEAVE", "Family trip", "Navy Chhun", "4.0", "8.0", "true");
        JsonNode bal = body(app(get("/api/v1/app/leave/balances"))).get("balances").get(0);
        assertThat(List.of(bal.get("left").asText(), bal.get("pending").asText())).containsExactly("12.0", "4.0");

        // over another request, more than is left (waiting ones count), only days off
        assertThat(code(send(navy, post(REQ), request(annual, "2026-11-12", "2026-11-16", null, "x")).andExpect(status().isConflict())))
                .isEqualTo("LEAVE_OVERLAP");
        JsonNode tooMuch = body(send(navy, post(REQ), request(annual, "2026-11-16", "2026-11-27", null, "x")).andExpect(status().isUnprocessableEntity()));
        assertThat(List.of(tooMuch.get("code").asText(), tooMuch.get("left").asText(), tooMuch.get("days").asText()))
                .containsExactly("NOT_ENOUGH_BALANCE", "8.0", "10.0");
        assertThat(code(send(navy, post(REQ), request(annual, "2026-11-07", "2026-11-08", null, "Weekend")).andExpect(status().isUnprocessableEntity())))
                .isEqualTo("NO_WORKING_DAYS");

        // half days: the morning and the afternoon of one date are two requests; the whole date then overlaps
        JsonNode am = body(send(navy, post(REQ), request(annual, "2026-11-18", null, "AM", "Doctor")).andExpect(status().isCreated()));
        assertThat(List.of(am.get("days").asText(), am.get("half").asText(), am.get("endDate").asText())).containsExactly("0.5", "AM", "2026-11-18");
        String pm = body(send(navy, post(REQ), request(annual, "2026-11-18", null, "pm", "Bank")).andExpect(status().isCreated())).get("id").asText();
        assertThat(code(send(navy, post(REQ), request(annual, "2026-11-18", null, null, "x")).andExpect(status().isConflict()))).isEqualTo("LEAVE_OVERLAP");
        assertThat(body(send(navy, post(REQ), request(annual, "2026-11-05", null, null, "Tomorrow")).andExpect(status().isCreated())).get("shortNotice")
                .asBoolean()).isTrue();

        // every wrong field at once; attachments by the type's rule
        assertThat(rules(send(navy, post(REQ), "{\"typeId\":\"" + UUID.randomUUID() + "\",\"startDate\":\"2026-10-20\",\"endDate\":\"2026-10-19\",\"half\":\"NOON\"}")))
                .containsExactlyInAnyOrder("typeId Unknown", "startDate TooFarBack", "endDate BeforeStart", "half OneOf", "reason NotBlank");
        assertThat(rules(send(navy, post(REQ), request(maternity, "2026-12-28", "2027-01-04", "AM", "x"))))
                .containsExactlyInAnyOrder("endDate SameYear", "half OneDay", "half NotAllowed");
        assertThat(rules(send(navy, post(REQ), request(maternity, "2026-11-23", "2026-11-27", null, "Baby")))).containsExactly("attachmentFileId Required");
        assertThat(code(send(navy, post(REQ), request(maternity, "2026-11-23", "2026-11-27", null, "Baby").replace("}", ",\"attachmentFileId\":\""
                + UUID.randomUUID() + "\"}")).andExpect(status().isConflict()))).isEqualTo("FILE_NOT_READY");
        assertThat(rules(send(navy, post(REQ), "{\"startDate\":\"4 Nov\"}"))).containsExactlyInAnyOrder("typeId NotNull", "startDate Pattern", "reason NotBlank");

        // mine, newest first; one with its timeline; taken back while it waits
        JsonNode mine = body(app(get(REQ)).andExpect(status().isOk()));
        assertThat(mine).extracting(r -> r.get("startDate").asText() + " " + r.get("status").asText())
                .containsExactly("2026-11-18 PENDING", "2026-11-18 PENDING", "2026-11-09 PENDING", "2026-11-05 PENDING");
        app(delete(REQ + "/" + pm)).andExpect(status().isNoContent());
        assertThat(code(app(delete(REQ + "/" + pm)).andExpect(status().isConflict()))).isEqualTo("ALREADY_DECIDED");
        JsonNode detail = body(app(get(REQ + "/" + pm)).andExpect(status().isOk()));
        assertThat(detail.get("request").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(detail.get("timeline")).extracting(e -> e.get("event").asText()).containsExactly("SENT", "CANCELLED");
        assertThat(jdbc.sql("select status from approval where subject_id = :s").param("s", UUID.fromString(pm)).query(String.class).single())
                .isEqualTo("CANCELLED");
        Caller other = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(),
                UUID.fromString(id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"Other\"},\"role\":\"staff\"}"))));
        mvc.perform(get(REQ + "/" + pm).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        assertThat(body(mvc.perform(get(REQ).header("Authorization", other.bearer())))).isEmpty();
        mvc.perform(get(REQ).header("Authorization", TestJwt.appUser("staff").bearer())).andExpect(status().isNotFound());
        mvc.perform(get(REQ).header("Authorization", admin.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void approving_updatesTheBalance_andTheDaysBecomeLeave() throws Exception {
        // Monday 2 Nov the end-of-day job marked absent; on Wednesday Navy asks leave for Monday – Tuesday
        jdbc.sql("""
                        insert into attendance_record (id, school_id, person_id, work_date, shift_idx, planned_start, planned_end, grace_minutes,
                          early_needs_reason, worked_minutes, status, source, created_at, updated_at)
                        values (gen_random_uuid(), :s, :p, '2026-11-02', 1, '07:45', '17:00', 5, true, 0, 'ABSENT', 'SYSTEM', now(), now())""")
                .param("s", school).param("p", person).update();
        JsonNode sent = body(send(navy, post(REQ), request(annual, "2026-11-02", "2026-11-03", null, "Fever, forgot to send")).andExpect(status().isCreated()));
        assertThat(sent.get("shortNotice").asBoolean()).isTrue();
        String approval = body(mvc.perform(get(INBOX + "?type=LEAVE").header("Authorization", admin.bearer()))).get("items").get(0).get("id").asText();

        // HR may approve leave of up to 3 days only (Roles › Approval limits): this one goes to Super Admin
        JsonNode hr = body(mvc.perform(post(INBOX + "/" + approval + "/approve").header("Authorization", TestJwt.webUser("hr", school).bearer()))
                .andExpect(status().isOk()));
        assertThat(hr.get("status").asText()).isEqualTo("APPROVED");
        assertThat(hr.has("undoUntil")).isTrue();
        // the decision takes effect 10 s later: until then the request waits, it cannot be cancelled any more
        assertThat(body(app(get("/api/v1/app/leave/balances"))).get("balances").get(0).get("pending").asText()).isEqualTo("2.0");
        assertThat(code(app(delete(REQ + "/" + sent.get("id").asText())).andExpect(status().isConflict()))).isEqualTo("ALREADY_DECIDED");
        takeEffect();
        assertThat(code(mvc.perform(post(INBOX + "/" + approval + "/approve").header("Authorization", admin.bearer())).andExpect(status().isConflict())))
                .isEqualTo("ALREADY_DECIDED");

        // the balance: used 2, left 10
        JsonNode bal = body(app(get("/api/v1/app/leave/balances"))).get("balances").get(0);
        assertThat(List.of(bal.get("used").asText(), bal.get("left").asText(), bal.get("pending").asText())).containsExactly("2.0", "10.0", "0.0");
        JsonNode adminRow = body(mvc.perform(get("/api/v1/admin/leave-balances").header("Authorization", admin.bearer()))).get("rows").get(0);
        assertThat(adminRow.get("balances").get(0).get("used").asText()).isEqualTo("2.0");
        // the days are leave: the stored absence too, the summary, the history
        assertThat(jdbc.sql("select status from attendance_record where person_id = :p and work_date = '2026-11-02'").param("p", person)
                .query(String.class).single()).isEqualTo("LEAVE");
        assertThat(jdbc.sql("select status || ' ' || leave || ' ' || absent from attendance_daily_summary where person_id = :p and work_date = '2026-11-02'")
                .param("p", person).query(String.class).single()).isEqualTo("LEAVE true false");
        assertThat(body(app(get("/api/v1/app/attendance/2026-11-03"))).get("status").asText()).isEqualTo("LEAVE");
        // Navy is told
        assertThat(jdbc.sql("select code from notification where person_id = :p and kind = 'LEAVE'").param("p", person).query(String.class).list())
                .containsExactly("LEAVE_APPROVED");
        JsonNode note = body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", navy.bearer()).header("Accept-Language", "en")));
        JsonNode first = (note.has("items") ? note.get("items") : note.get("content")).get(0);
        assertThat(first.get("title").asText()).isEqualTo("Leave request approved");
        assertThat(first.get("body").asText()).isEqualTo("Annual leave, Mon 2 Nov – Tue 3 Nov (2 days).");
        assertThat(code(app(delete(REQ + "/" + sent.get("id").asText())).andExpect(status().isConflict()))).isEqualTo("ALREADY_DECIDED");

        // above HR's limit: 4 days → APPROVAL_LIMIT, Super Admin's inbox; rejecting needs a comment
        clock.set(LocalDate.of(2026, 11, 4).atTime(10, 0).atZone(PP).toInstant());
        String longer = body(send(navy, post(REQ), request(annual, "2026-11-16", "2026-11-19", null, "Wedding"))).get("id").asText();
        String second = body(mvc.perform(get(INBOX).header("Authorization", admin.bearer()))).get("items").get(0).get("id").asText();
        JsonNode limit = body(mvc.perform(post(INBOX + "/" + second + "/approve").header("Authorization", TestJwt.webUser("hr", school).bearer()))
                .andExpect(status().isForbidden()));
        assertThat(List.of(limit.get("code").asText(), limit.get("reason").asText(), limit.get("escalateTo").asText()))
                .containsExactly("APPROVAL_LIMIT", "LEAVE_TOO_LONG", "super_admin");
        assertThat(rules(mvc.perform(post(INBOX + "/" + second + "/reject").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\" \"}")))).containsExactly("comment NotBlank");
        JsonNode rejected = body(mvc.perform(post(INBOX + "/" + second + "/reject").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Exam week, please choose other dates\"}")).andExpect(status().isOk()));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        takeEffect();
        JsonNode timeline = body(app(get(REQ + "/" + longer))).get("timeline");
        assertThat(timeline).extracting(e -> e.get("event").asText()).containsExactly("SENT", "REJECTED");
        assertThat(timeline.get(1).get("comment").asText()).isEqualTo("Exam week, please choose other dates");
        assertThat(body(app(get("/api/v1/app/leave/balances"))).get("balances").get(0).get("pending").asText()).isEqualTo("0.0");
        JsonNode texts = body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", navy.bearer()).header("Accept-Language", "en")));
        assertThat((texts.has("items") ? texts.get("items") : texts.get("content")).get(0).get("body").asText())
                .isEqualTo("Annual leave, Mon 16 Nov – Thu 19 Nov (4 days): Exam week, please choose other dates");

        // the done tab; unknown requests; the app token of a staff member cannot read the inbox
        JsonNode done = body(mvc.perform(get(INBOX + "?status=done").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(done.get("items")).extracting(i -> i.get("status").asText()).containsExactly("REJECTED", "APPROVED");
        mvc.perform(get(INBOX + "?status=all").header("Authorization", admin.bearer())).andExpect(status().isBadRequest());
        mvc.perform(post(INBOX + "/" + UUID.randomUUID() + "/approve").header("Authorization", admin.bearer())).andExpect(status().isNotFound());
        mvc.perform(get(INBOX).header("Authorization", navy.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void halfADayApproved_onAOneShiftDay_movesTheShiftsStart() throws Exception {
        send(navy, post(REQ), request(annual, "2026-11-10", null, "AM", "Doctor in the morning")).andExpect(status().isCreated());
        String approval = body(mvc.perform(get(INBOX).header("Authorization", admin.bearer()))).get("items").get(0).get("id").asText();
        mvc.perform(post(INBOX + "/" + approval + "/approve").header("Authorization", admin.bearer())).andExpect(status().isOk());
        takeEffect();
        clock.set(LocalDate.of(2026, 11, 10).atTime(7, 0).atZone(PP).toInstant());
        JsonNode today = body(app(get("/api/v1/app/me/schedule/today")).andExpect(status().isOk()));
        assertThat(today.get("kind").asText()).isEqualTo("WORK");
        assertThat(today.get("leave").get("half").asText()).isEqualTo("AM");
        assertThat(today.get("shifts").get(0).get("start").asText()).isEqualTo("12:22");
        assertThat(today.get("shifts").get(0).get("end").asText()).isEqualTo("17:00");
        assertThat(body(app(get("/api/v1/app/leave/balances"))).get("balances").get(0).get("used").asText()).isEqualTo("0.5");
    }

    // ------------------------------------------------------------------ helpers

    /** 11 s later: the decisions take effect, their events are handled. */
    private void takeEffect() {
        clock.set(clock.instant().plusSeconds(11));
        effects.applyDue();
        relay.drain();
    }

    private static String request(String type, String start, String end, String half, String reason) {
        return "{\"typeId\":\"" + type + "\",\"startDate\":\"" + start + "\"" + (end == null ? "" : ",\"endDate\":\"" + end + "\"")
                + (half == null ? "" : ",\"half\":\"" + half + "\"") + ",\"reason\":\"" + reason + "\"}";
    }

    private ResultActions app(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", navy.bearer()));
    }

    private List<String> rules(ResultActions result) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        List<String> out = new ArrayList<>();
        problem.get("errors").forEach(e -> out.add(e.get("field").asText() + " " + e.get("rule").asText()));
        return out;
    }

    private String code(ResultActions result) throws Exception {
        return body(result).get("code").asText();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return send(admin, request, body);
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
