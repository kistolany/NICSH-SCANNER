package kh.cmt.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Step 7.6 "Request correction" (SPEC §4.4 states 7 / 8, approvals card "Thu 8 Oct · Check-out · Recorded: --:-- →
 * Requested: 17:00"): today is Thursday 15 Oct 2026, the person works Monday – Friday 07:45 – 17:00; on Tuesday 13 they
 * checked in at 07:40 and never out, Wednesday 14 they did not scan at all.
 */
@ApiTest
class CorrectionTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestClock clock;
    @Autowired AttendanceRecordRepository records;

    private UUID school;
    private Caller admin;
    private Caller app;
    private UUID person;
    private UUID tuesday;

    @BeforeEach
    void aWeek() throws Exception {
        clock.set(LocalDate.of(2026, 9, 27).atTime(8, 0).atZone(PP).toInstant());
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Correction school', now(), now())")
                .param("id", school).param("c", "C" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        String template = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Full day\",\"graceMinutes\":5,\"earlyCheckOutNeedsReason\":true,"
                + "\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}"));
        person = UUID.fromString(id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ដារា\",\"en\":\"Dara Heng\"},\"role\":\"staff\"}")));
        StringBuilder days = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            days.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":")
                    .append(wd <= 5 ? "\"" + template + "\"" : "null").append("}");
        }
        send(put("/api/v1/admin/schedules/weekly?target=person&id=" + person), "{\"effectiveFrom\":\"2026-09-28\",\"days\":[" + days + "]}")
                .andExpect(status().isOk());
        app = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), person);
        LocalDate tue = LocalDate.of(2026, 10, 13);
        AttendanceRecord.Plan plan = new AttendanceRecord.Plan(1, null, LocalTime.of(7, 45), LocalTime.of(17, 0), 5, true);
        tuesday = SchoolOverride.callAs(school, () -> records.saveAndFlush(AttendanceRecord.checkedIn(person, tue, plan,
                tue.atTime(7, 40).atZone(PP).toInstant(), null, null, AttendanceRecord.Source.SCAN, PP))).getId();
        clock.set(TODAY.atTime(9, 0).atZone(PP).toInstant());
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @Test
    void aMissingCheckOut_andAMissedDay_goToTheInbox_andShowInTheHistory() throws Exception {
        // "Thu 8 Oct · Check-out · Recorded: --:-- → Requested: 17:00"
        JsonNode sent = body(ask("2026-10-13", "CHECK_OUT", "17:00", "Forgot to scan when leaving.").andExpect(status().isCreated()));
        assertThat(sent.get("type").asText()).isEqualTo("CORRECTION");
        assertThat(sent.get("status").asText()).isEqualTo("PENDING");
        JsonNode d = sent.get("details");
        assertThat(d.get("date").asText()).isEqualTo("2026-10-13");
        assertThat(d.get("field").asText()).isEqualTo("CHECK_OUT");
        assertThat(d.get("recordedTime").isNull()).isTrue();
        assertThat(d.get("requestedTime").asText()).isEqualTo("17:00");
        assertThat(d.get("recordId").asText()).isEqualTo(tuesday.toString());

        // sent again while it waits: the same request, the new time
        JsonNode changed = body(ask("2026-10-13", "CHECK_OUT", "17:05", "Forgot to scan when leaving.").andExpect(status().isOk()));
        assertThat(changed.get("id").asText()).isEqualTo(sent.get("id").asText());
        assertThat(changed.get("details").get("requestedTime").asText()).isEqualTo("17:05");

        // the check-in of the same day is a request of its own; so is a day without any scan (no record)
        JsonNode in = body(ask("2026-10-13", "CHECK_IN", "07:35", "The gate's QR was not working.").andExpect(status().isCreated()));
        assertThat(in.get("details").get("recordedTime").asText()).isEqualTo("07:40");
        JsonNode absent = body(ask("2026-10-14", "CHECK_IN", "07:40", "Phone battery died.").andExpect(status().isCreated()));
        assertThat(absent.get("details").has("recordId")).isFalse();
        assertThat(jdbc.sql("select count(*) from approval where person_id = :p and type = 'CORRECTION'").param("p", person).query(Long.class).single())
                .isEqualTo(3);

        // the history's day sheet shows where they stand
        JsonNode tue = body(mvc.perform(get("/api/v1/app/attendance/2026-10-13").header("Authorization", app.bearer())).andExpect(status().isOk()));
        List<String> asked = new ArrayList<>();
        tue.get("shifts").get(0).get("corrections").forEach(c -> asked.add(c.get("field").asText() + " " + c.get("time").asText() + " "
                + c.get("status").asText()));
        assertThat(asked).containsExactly("CHECK_IN 07:35 PENDING", "CHECK_OUT 17:05 PENDING");
        JsonNode wed = body(mvc.perform(get("/api/v1/app/attendance/2026-10-14").header("Authorization", app.bearer())).andExpect(status().isOk()));
        assertThat(wed.get("shifts").get(0).get("corrections").get(0).get("field").asText()).isEqualTo("CHECK_IN");

        // an admin approved it (the inbox is phase 10): it cannot change any more
        jdbc.sql("update approval set status = 'APPROVED', decided_at = now(), effective_at = now(), applied_at = now() where id = :id").param("id", UUID.fromString(sent.get("id").asText())).update();
        JsonNode decided = body(ask("2026-10-13", "CHECK_OUT", "17:10", "Again").andExpect(status().isConflict()));
        assertThat(decided.get("code").asText()).isEqualTo("ALREADY_DECIDED");
        assertThat(decided.get("decision").asText()).isEqualTo("APPROVED");
    }

    @Test
    void whatIsChecked() throws Exception {
        assertThat(rules(ask(null, null, null, " "))).containsExactlyInAnyOrder("date NotNull", "field OneOf", "time Pattern", "reason NotBlank");
        assertThat(rules(ask("2026-10-16", "CHECK_IN", "07:40", "x"))).containsExactly("date Future");
        assertThat(rules(ask("2026-10-15", "CHECK_IN", "09:30", "x"))).containsExactly("time Future");   // it is 09:00
        assertThat(rules(ask("2026-10-13", "CHECK_OUT", "07:30", "x"))).containsExactly("time BeforeCheckIn");
        assertThat(rules(ask("2026-10-13", "CHECK_IN", "07:40", "x"))).containsExactly("time Unchanged");
        assertThat(rules(ask("13/10/2026", "check_in", "7.40", "x"))).containsExactlyInAnyOrder("date Pattern", "time Pattern");

        JsonNode saturday = body(ask("2026-10-10", "CHECK_IN", "07:40", "x").andExpect(status().isUnprocessableEntity()));
        assertThat(saturday.get("code").asText()).isEqualTo("NOT_A_WORKING_DAY");
        assertThat(saturday.get("kind").asText()).isEqualTo("OFF");
        Map<String, Object> secondShift = body("2026-10-13", "CHECK_IN", "13:30", "x");
        secondShift.put("shiftIdx", 2);
        assertThat(body(submit(secondShift).andExpect(status().isUnprocessableEntity())).get("code").asText()).isEqualTo("NOT_A_WORKING_DAY");
        mvc.perform(post("/api/v1/app/attendance/corrections").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        assertThat(jdbc.sql("select count(*) from approval where person_id = :p").param("p", person).query(Long.class).single()).isZero();
    }

    @Test
    void anAttachment_isTakenByTheRequest_andLetGoWhenReplaced() throws Exception {
        UUID photo = upload("battery.pdf");
        Map<String, Object> c = body("2026-10-14", "CHECK_IN", "07:40", "Phone battery died.");
        c.put("attachmentFileId", photo);
        JsonNode sent = body(submit(c).andExpect(status().isCreated()));
        assertThat(sent.get("details").get("attachmentFileId").asText()).isEqualTo(photo.toString());
        assertThat(attached(photo)).isTrue();
        submit(c).andExpect(status().isOk());   // the same again: still its file

        // a file already used elsewhere / not confirmed
        Map<String, Object> other = body("2026-10-13", "CHECK_OUT", "17:00", "x");
        other.put("attachmentFileId", photo);
        assertThat(body(submit(other).andExpect(status().isConflict())).get("code").asText()).isEqualTo("FILE_IN_USE");
        other.put("attachmentFileId", UUID.randomUUID());
        assertThat(body(submit(other)).get("code").asText()).isEqualTo("FILE_NOT_READY");

        // replaced by another file: the first one is removed
        UUID second = upload("battery2.pdf");
        c.put("attachmentFileId", second);
        submit(c).andExpect(status().isOk());
        assertThat(attached(second)).isTrue();
        assertThat(jdbc.sql("select count(*) from file where id = :id").param("id", photo).query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions ask(String date, String field, String time, String reason) throws Exception {
        return submit(body(date, field, time, reason));
    }

    private static Map<String, Object> body(String date, String field, String time, String reason) {
        Map<String, Object> b = new HashMap<>();
        b.put("date", date);
        b.put("field", field);
        b.put("time", time);
        b.put("reason", reason);
        return b;
    }

    private ResultActions submit(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/app/attendance/corrections")
                .header("Authorization", app.bearer()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private List<String> rules(ResultActions result) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        List<String> out = new ArrayList<>();
        problem.get("errors").forEach(e -> out.add(e.get("field").asText() + " " + e.get("rule").asText()));
        return out;
    }

    /** The app's upload: link, PUT to storage, confirm. */
    private UUID upload(String name) throws Exception {
        byte[] pdf = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        JsonNode link = body(mvc.perform(post("/api/v1/files/upload-url")
                .header("Authorization", app.bearer()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("purpose",
                        "ATTACHMENT", "fileName", name, "contentType", "application/pdf", "size", pdf.length)))).andExpect(status().isOk()));
        HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(URI.create(link.get("uploadUrl").asText())).header("Content-Type", "application/pdf")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(pdf)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        UUID id = UUID.fromString(link.get("fileId").asText());
        mvc.perform(post("/api/v1/files/" + id + "/confirm")
                .header("Authorization", app.bearer())).andExpect(status().isOk());
        return id;
    }

    private boolean attached(UUID file) {
        return jdbc.sql("select attached from file where id = :id").param("id", file).query(Boolean.class).single();
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
