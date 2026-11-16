package kh.cmt.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kh.cmt.attendance.domain.ScanSignature;
import kh.cmt.shared.events.OutboxRelay;
import kh.cmt.support.ApiTest;
import kh.cmt.support.QrImages;
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
 * Step 7.2 "done when every result screen in the app design can be produced by a test" (SPEC §4.6): checked in on time
 * (b) and late (c), checked out (d), leave early → reason → checked out early (e), outside the school area (a), already
 * checked in, wrong QR, expired QR, location off (f), the two-shift titles and "Next: Shift 2 at 13:30" — plus the
 * phone checks. The day is played on Monday 12 Oct 2026 with the test clock.
 */
@ApiTest
class ScanTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    static final double LAT = 11.5564;
    static final double LNG = 104.9282;
    static final Instant SET_UP = MONDAY.minusDays(1).atTime(6, 0).atZone(PP).toInstant();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestClock clock;
    @Autowired OutboxRelay relay;

    private UUID school;
    private Caller admin;
    private String gate;
    private String qr;
    private Staff full;    // one shift 07:45 – 17:00
    private Staff split;   // 07:45 – 11:30 and 13:30 – 17:00

    record Staff(UUID person, UUID account, UUID phone, Caller app) {
    }

    @BeforeEach
    void aSchoolDay() throws Exception {
        clock.set(SET_UP);   // the day before: schedules may start then
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Scan school', now(), now())")
                .param("id", school).param("c", "S" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        String area = id(send(post("/api/v1/admin/areas"), "{\"name\":\"CMT School\",\"latitude\":" + LAT + ",\"longitude\":" + LNG
                + ",\"radiusMeters\":120}"));
        gate = id(send(post("/api/v1/admin/gates"), "{\"name\":\"Main gate\",\"areaId\":\"" + area + "\"}"));
        mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", admin.bearer())).andExpect(status().isCreated());
        qr = printedQr();
        String fullDay = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Full day\",\"graceMinutes\":5,\"earlyCheckOutNeedsReason\":true,"
                + "\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}"));
        String splitDay = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Split day\",\"graceMinutes\":5,\"earlyCheckOutNeedsReason\":true,"
                + "\"shifts\":[{\"name\":\"Morning\",\"start\":\"07:45\",\"end\":\"11:30\"},{\"name\":\"Afternoon\",\"start\":\"13:30\",\"end\":\"17:00\"}]}"));
        full = staff("Dara", fullDay);
        split = staff("Sokha", splitDay);
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    // ------------------------------------------------------------------ the result screens

    @Test
    void b_checkedInOnTime_thenAlreadyCheckedIn_d_checkedOut_thenAlreadyCheckedOut() throws Exception {
        JsonNode in = body(scan(full, at(7, 38)).andExpect(status().isOk()));
        assertThat(in.get("result").asText()).isEqualTo("CHECKED_IN");
        assertThat(Instant.parse(in.get("time").asText())).isEqualTo(at(7, 38));   // "07:38 · On time"
        assertThat(in.get("status").asText()).isEqualTo("ON_TIME");
        assertThat(in.get("gate").get("name").asText()).isEqualTo("Main gate");   // "CMT School · Main gate"
        assertThat(in.get("twoShifts").asBoolean()).isFalse();

        JsonNode again = body(scan(full, at(7, 40)).andExpect(status().isConflict()));   // "You already checked in today"
        assertThat(again.get("code").asText()).isEqualTo("ALREADY_CHECKED_IN");
        assertThat(Instant.parse(again.get("at").asText())).isEqualTo(at(7, 38));

        JsonNode out = body(scan(full, at(17, 2)).andExpect(status().isOk()));   // "Checked out · 17:02 · 9h 24m today"
        assertThat(out.get("result").asText()).isEqualTo("CHECKED_OUT");
        assertThat(out.get("status").asText()).isEqualTo("ON_TIME");
        assertThat(out.get("workedTodayMinutes").asInt()).isEqualTo(564);
        assertThat(out.has("nextShift")).isFalse();

        assertThat(body(scan(full, at(17, 30)).andExpect(status().isConflict())).get("code").asText()).isEqualTo("ALREADY_CHECKED_OUT");

        // one outbox event per check-in / check-out (none for the refusals), handled by the relay
        assertThat(jdbc.sql("select payload->>'kind' from outbox where type = 'attendance.recorded' and payload->>'personId' = :p order by created_at")
                .param("p", full.person().toString()).query(String.class).list()).containsExactly("CHECK_IN", "CHECK_OUT");
        relay.drain();
        assertThat(jdbc.sql("select count(*) from outbox where type = 'attendance.recorded' and published_at is null and payload->>'personId' = :p")
                .param("p", full.person().toString()).query(Long.class).single()).isZero();
    }

    @Test
    void c_checkedInLate() throws Exception {
        JsonNode in = body(scan(full, at(7, 57)).andExpect(status().isOk()));   // "07:57 · Late by 12 min", "Your work starts at 07:45"
        assertThat(in.get("status").asText()).isEqualTo("LATE");
        assertThat(in.get("lateMinutes").asInt()).isEqualTo(12);
        assertThat(in.get("shiftStart").asText()).isEqualTo("07:45");
        assertThat(in.get("recordId").asText()).isNotBlank();   // "Send reason to admin" (7.4)
    }

    @Test
    void e_leaveEarly_askForTheReason_thenCheckedOutEarly() throws Exception {
        scan(full, at(7, 40)).andExpect(status().isOk());
        JsonNode ask = body(scan(full, at(15, 30)).andExpect(status().isUnprocessableEntity()));   // "Leave early?" sheet
        assertThat(ask.get("code").asText()).isEqualTo("EARLY_REASON_REQUIRED");
        assertThat(ask.get("earlyMinutes").asInt()).isEqualTo(90);   // "It is now 15:30 (1h 30m early)"
        assertThat(Instant.parse(ask.get("shiftEnd").asText())).isEqualTo(at(17, 0));   // "Your work ends at 17:00"
        String ticket = ask.get("ticket").asText();
        // that scan sent again (its answer lost): the same sheet, the same ticket
        String askKey = jdbc.sql("select idempotency_key from scan_event where id = :id").param("id", UUID.fromString(ticket))
                .query(String.class).single();
        JsonNode askAgain = body(scanWithKey(full, at(15, 30), askKey).andExpect(status().isUnprocessableEntity()));
        assertThat(askAgain.get("ticket").asText()).isEqualTo(ticket);
        assertThat(askAgain.get("earlyMinutes").asInt()).isEqualTo(90);
        assertThat(askAgain.get("shiftEnd").asText()).isEqualTo(ask.get("shiftEnd").asText());

        clock.set(at(15, 31));   // the person picks a reason for a minute
        JsonNode out = body(early(full, ticket, "personal", "Family").andExpect(status().isOk()));   // "Checked out early · 15:30 · 1h 30m early"
        assertThat(out.get("status").asText()).isEqualTo("EARLY");
        assertThat(out.get("earlyMinutes").asInt()).isEqualTo(90);
        assertThat(Instant.parse(out.get("time").asText())).isEqualTo(at(15, 30));
        assertThat(jdbc.sql("select early_reason from attendance_record where id = :id").param("id", UUID.fromString(out.get("recordId").asText()))
                .query(String.class).single()).isEqualTo("personal: Family");
        // sent again: the same answer, one check-out
        assertThat(body(early(full, ticket, "personal", "Family").andExpect(status().isOk())).get("recordId").asText())
                .isEqualTo(out.get("recordId").asText());
        early(full, UUID.randomUUID().toString(), "sick", null).andExpect(status().isNotFound());
        early(full, ticket, " ", null).andExpect(status().isBadRequest());
    }

    @Test
    void twoShifts_titlesAndNextShift_andAMissedMorning() throws Exception {
        JsonNode in1 = body(scan(split, at(7, 45)).andExpect(status().isOk()));
        assertThat(in1.get("shiftIdx").asInt()).isEqualTo(1);
        assertThat(in1.get("shiftName").asText()).isEqualTo("Morning");
        assertThat(in1.get("twoShifts").asBoolean()).isTrue();
        JsonNode out1 = body(scan(split, at(11, 31)).andExpect(status().isOk()));   // "Checked out — Shift 1", "Next: Shift 2 at 13:30"
        assertThat(out1.get("shiftIdx").asInt()).isEqualTo(1);
        assertThat(out1.get("nextShift").get("shiftIdx").asInt()).isEqualTo(2);
        assertThat(out1.get("nextShift").get("start").asText()).isEqualTo("13:30");
        JsonNode in2 = body(scan(split, at(13, 28)).andExpect(status().isOk()));
        assertThat(in2.get("shiftIdx").asInt()).isEqualTo(2);
        assertThat(in2.get("status").asText()).isEqualTo("ON_TIME");
        JsonNode out2 = body(scan(split, at(17, 0)).andExpect(status().isOk()));
        assertThat(out2.get("workedTodayMinutes").asInt()).isEqualTo(226 + 212);
        assertThat(out2.has("nextShift")).isFalse();

        // someone who missed the morning: the first scan after 11:30 starts shift 2
        Staff late = staff("Vanna", templateOf(split));
        JsonNode afternoon = body(scan(late, at(12, 50)).andExpect(status().isOk()));
        assertThat(afternoon.get("shiftIdx").asInt()).isEqualTo(2);
        assertThat(afternoon.get("status").asText()).isEqualTo("ON_TIME");
    }

    @Test
    void checkInOpens_beforeEachShift() throws Exception {
        clock.set(SET_UP);
        JsonNode tpl = body(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Split, opens 60\",\"graceMinutes\":5,"
                + "\"earlyCheckOutNeedsReason\":false,\"checkInOpensMinutes\":60,"
                + "\"shifts\":[{\"start\":\"07:45\",\"end\":\"11:30\"},{\"start\":\"13:30\",\"end\":\"17:00\"}]}").andExpect(status().isCreated()));
        assertThat(tpl.get("checkInOpensMinutes").asInt()).isEqualTo(60);
        Staff s = staff("Rith", tpl.get("id").asText());

        JsonNode early = body(scan(s, at(6, 40)).andExpect(status().isUnprocessableEntity()));   // "Check-in opens at 06:45"
        assertThat(early.get("code").asText()).isEqualTo("TOO_EARLY");
        assertThat(early.get("opensAt").asText()).isEqualTo("06:45");
        assertThat(early.get("shiftStart").asText()).isEqualTo("07:45");
        assertThat(body(scan(s, at(6, 45)).andExpect(status().isOk())).get("shiftIdx").asInt()).isEqualTo(1);
        scan(s, at(11, 31)).andExpect(status().isOk());

        JsonNode lunch = body(scan(s, at(12, 0)).andExpect(status().isUnprocessableEntity()));   // shift 2 opens 12:30
        assertThat(lunch.get("shiftIdx").asInt()).isEqualTo(2);
        assertThat(lunch.get("opensAt").asText()).isEqualTo("12:30");
        assertThat(body(scan(s, at(12, 30)).andExpect(status().isOk())).get("shiftIdx").asInt()).isEqualTo(2);

        // no limit set (the other templates): an early scan still checks in
        scan(full, at(5, 0)).andExpect(status().isOk());
        // out of range
        send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Bad\",\"checkInOpensMinutes\":721,"
                + "\"shifts\":[{\"start\":\"07:45\",\"end\":\"11:30\"}]}").andExpect(status().isBadRequest());
    }

    @Test
    void a_outsideTheSchoolArea_withThePhonesAccuracy_andTheOtherSheets() throws Exception {
        // 1.2 km north: "You are 1.2 km away from CMT School"
        JsonNode outside = body(scan(full, at(7, 40), LAT + 1320 / 111_195.0, LNG, 10.0, Map.of()).andExpect(status().isUnprocessableEntity()));
        assertThat(outside.get("code").asText()).isEqualTo("OUTSIDE_AREA");
        assertThat(outside.get("distanceKm").asDouble()).isEqualTo(1.2);
        assertThat(outside.get("areaName").asText()).isEqualTo("CMT School");
        // 150 m from the centre of a 120 m area: inside when the phone is ±40 m accurate, outside at ±20 m
        double at150 = LAT + 150 / 111_195.0;
        assertThat(body(scan(full, at(7, 41), at150, LNG, 20.0, Map.of())).get("code").asText()).isEqualTo("OUTSIDE_AREA");
        scan(full, at(7, 42), at150, LNG, 40.0, Map.of()).andExpect(status().isOk());

        // wrong QR
        assertThat(body(scan(split, at(7, 40), Map.of("qrToken", "https://example.com/menu"))).get("code").asText()).isEqualTo("QR_INVALID");
        // a new QR at 07:40: the old one still works for 5 minutes, then "This QR code has expired"
        String oldQr = qr;
        clock.set(at(7, 40));
        mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", admin.bearer())).andExpect(status().isCreated());
        qr = printedQr();
        assertThat(body(scan(split, at(7, 44), Map.of("qrToken", oldQr)).andExpect(status().isOk())).get("result").asText()).isEqualTo("CHECKED_IN");
        Staff third = staff("Bopha", templateOf(full));
        assertThat(body(scan(third, at(7, 46), Map.of("qrToken", oldQr))).get("code").asText()).isEqualTo("QR_EXPIRED");
        // location off
        Map<String, Object> noLocation = new HashMap<>();
        noLocation.put("lat", null);
        assertThat(body(scan(third, at(7, 47), noLocation)).get("code").asText()).isEqualTo("LOCATION_OFF");
        scan(third, at(7, 48)).andExpect(status().isOk());   // the new QR

        // every attempt is kept, refused ones with their code
        assertThat(jdbc.sql("select result from scan_event where person_id = :p order by received_at").param("p", full.person())
                .query(String.class).list()).containsExactly("OUTSIDE_AREA", "OUTSIDE_AREA", "ACCEPTED");
    }

    @Test
    void theDayAndThePhone() throws Exception {
        // Sunday: no shift
        clock.set(MONDAY.minusDays(1).atTime(8, 0).atZone(PP).toInstant());
        assertThat(body(scan(full, MONDAY.minusDays(1).atTime(8, 0).atZone(PP).toInstant())).get("kind").asText()).isEqualTo("OFF");
        // fake GPS and rooted phones (the school's settings block both by default)
        assertThat(body(scan(full, at(7, 40), Map.of("isMockLocation", true))).get("code").asText()).isEqualTo("MOCK_LOCATION");
        assertThat(body(scan(full, at(7, 40), Map.of("isRooted", true))).get("code").asText()).isEqualTo("ROOTED_DEVICE");
        // a token from another phone; a blocked phone; an old app
        Caller otherPhone = TestJwt.withDevice("staff", school, full.account(), full.person(), UUID.randomUUID());
        assertThat(body(scanAs(otherPhone, at(7, 40), Map.of())).get("code").asText()).isEqualTo("DEVICE_MISMATCH");
        jdbc.sql("insert into auth.device_policy (school_id, min_app_version) values (:s, '1.5.0')").param("s", school).update();
        clock.set(at(7, 40));
        assertThat(body(mvc.perform(post("/api/v1/app/attendance/scan").header("Authorization", full.app().bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-App-Version", "1.2.0")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request(Map.of()))))
                .andExpect(status().isUpgradeRequired())).get("minVersion").asText()).isEqualTo("1.5.0");
        jdbc.sql("insert into auth.device_block (device_id, school_id, reason, blocked_at) values (:d, :s, 'Stolen phone', now())")
                .param("d", split.phone()).param("s", school).update();
        assertThat(body(scan(split, at(7, 40))).get("code").asText()).isEqualTo("DEVICE_BLOCKED");

        // the same key twice: the same answer, one scan
        String key = UUID.randomUUID().toString();
        JsonNode first = body(scanWithKey(full, at(7, 41), key).andExpect(status().isOk()));
        clock.set(at(7, 55));
        JsonNode second = body(scanWithKey(full, at(7, 55), key).andExpect(status().isOk()));
        assertThat(second.get("recordId").asText()).isEqualTo(first.get("recordId").asText());
        assertThat(second.get("status").asText()).isEqualTo("ON_TIME");
        assertThat(jdbc.sql("select count(*) from scan_event where idempotency_key = :k").param("k", key).query(Long.class).single()).isEqualTo(1);
        // a refusal sent again: the same answer with all its details ("You already checked in today at 07:41")
        String refusedKey = UUID.randomUUID().toString();
        clock.set(at(7, 45));
        JsonNode refused = body(scanWithKey(full, at(7, 45), refusedKey).andExpect(status().isConflict()));
        clock.set(at(7, 47));
        JsonNode refusedAgain = body(scanWithKey(full, at(7, 47), refusedKey).andExpect(status().isConflict()));
        assertThat(refusedAgain.get("code").asText()).isEqualTo("ALREADY_CHECKED_IN");
        assertThat(Instant.parse(refusedAgain.get("at").asText())).isEqualTo(at(7, 41));
        assertThat(refusedAgain.get("shiftIdx").asInt()).isEqualTo(1);
        assertThat(refusedAgain.get("at").asText()).isEqualTo(refused.get("at").asText());
        // web users have no scan
        mvc.perform(post("/api/v1/app/attendance/scan").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void signedScans_onceThePhoneHasAKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair keys = generator.generateKeyPair();
        jdbc.sql("update auth.account set device_public_key = :k where id = :a")
                .param("k", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())).param("a", full.account()).update();

        assertThat(body(scan(full, at(7, 40))).get("code").asText()).isEqualTo("SIGNATURE_INVALID");   // not signed
        String key = UUID.randomUUID().toString();
        String clientTime = "2026-10-12T07:41:00+07:00";
        double lat = LAT + 30 / 111_195.0;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(ScanSignature.text(key, qr, clientTime, lat, LNG).getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        clock.set(at(7, 41));
        Map<String, Object> body = request(Map.of("clientTime", clientTime, "signature", signature, "lat", lat, "lng", LNG));
        JsonNode in = body(mvc.perform(post("/api/v1/app/attendance/scan").header("Authorization", full.app().bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()));
        assertThat(in.get("result").asText()).isEqualTo("CHECKED_IN");
        assertThat(jdbc.sql("select signature_ok from scan_event where idempotency_key = :k").param("k", key).query(Boolean.class).single()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    static Instant at(int h, int m) {
        return MONDAY.atTime(h, m).atZone(PP).toInstant();
    }

    /** A person with an app login on a linked phone, working the template on Mondays from the test day. */
    private Staff staff(String name, String template) throws Exception {
        Instant before = clock.instant();
        clock.set(SET_UP);
        JsonNode created = body(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"" + name + " " + UUID.randomUUID().toString()
                .substring(0, 4).replaceAll("[0-9]", "x") + "\"},\"role\":\"staff\",\"login\":{}}").andExpect(status().isCreated()));
        UUID person = UUID.fromString(created.get("id").asText());
        UUID account = jdbc.sql("select id from auth.account where person_id = :p and type = 'app'").param("p", person).query(UUID.class).single();
        UUID phone = UUID.randomUUID();
        jdbc.sql("update auth.account set device_id = :d, device_name = 'Phone', device_bound_at = now() where id = :a")
                .param("d", phone).param("a", account).update();
        StringBuilder days = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            days.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":")
                    .append(wd <= 5 ? "\"" + template + "\"" : "null").append("}");
        }
        send(put("/api/v1/admin/schedules/weekly?target=person&id=" + person), "{\"effectiveFrom\":\"" + MONDAY.minusDays(1) + "\",\"days\":["
                + days + "]}").andExpect(status().isOk());
        clock.set(before);
        return new Staff(person, account, phone, TestJwt.withDevice("staff", school, account, person, phone));
    }

    private String templateOf(Staff s) {
        return jdbc.sql("""
                select d.template_id::text from weekly_schedule w join weekly_schedule_day d on d.schedule_id = w.id
                where w.person_id = :p and d.weekday = 1""").param("p", s.person()).query(String.class).single();
    }

    private Map<String, Object> request(Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>();
        body.put("qrToken", qr);
        body.put("lat", LAT + 30 / 111_195.0);
        body.put("lng", LNG);
        body.put("accuracy", 8.0);
        body.put("clientTime", clock.instant().toString());
        body.put("isMockLocation", false);
        body.put("isRooted", false);
        body.putAll(overrides);
        return body;
    }

    private ResultActions scan(Staff s, Instant when) throws Exception {
        return scan(s, when, Map.of());
    }

    private ResultActions scan(Staff s, Instant when, Map<String, Object> overrides) throws Exception {
        clock.set(when);
        return scanAs(s.app(), when, overrides);
    }

    private ResultActions scan(Staff s, Instant when, double lat, double lng, double accuracy, Map<String, Object> overrides) throws Exception {
        Map<String, Object> o = new HashMap<>(overrides);
        o.put("lat", lat);
        o.put("lng", lng);
        o.put("accuracy", accuracy);
        return scan(s, when, o);
    }

    private ResultActions scanAs(Caller app, Instant when, Map<String, Object> overrides) throws Exception {
        clock.set(when);
        return mvc.perform(post("/api/v1/app/attendance/scan").header("Authorization", app.bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(overrides))));
    }

    private ResultActions scanWithKey(Staff s, Instant when, String key) throws Exception {
        clock.set(when);
        return mvc.perform(post("/api/v1/app/attendance/scan").header("Authorization", s.app().bearer()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request(Map.of()))));
    }

    private ResultActions early(Staff s, String ticket, String reason, String note) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("ticket", ticket);
        body.put("reason", reason);
        body.put("note", note);
        return mvc.perform(post("/api/v1/app/attendance/scan/early").header("Authorization", s.app().bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** The QR on the printed sheet, read back like a phone would. */
    private String printedQr() throws Exception {
        byte[] png = mvc.perform(get("/api/v1/admin/gates/" + gate + "/qr/print.png").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return QrImages.read(png);
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
