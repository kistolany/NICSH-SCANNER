package kh.cmt.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * Step 7.7 "done when month grid numbers match day records" (Admin Web › Staff attendance): today is Thursday 15 Oct
 * 2026 09:00; everyone works Monday – Friday from Monday 28 Sep, Friday 9 Oct is a school holiday.
 * <pre>
 * Dara (Front office, 07:45 – 17:00):  12 on time · 13 late 07:57 · 14 no scan · 15 checked in 07:40
 * Sokha (Finance, two shifts):           12 both shifts · 13 morning only · 15 refused "outside the area"
 * Bopha (Front office, 07:45 – 17:00):  no scan at all
 * </pre>
 */
@ApiTest
class AdminAttendanceTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    static final String DAY = "/api/v1/admin/attendance/day";
    static final String MONTH = "/api/v1/admin/attendance/month";
    static final String RECORDS = "/api/v1/admin/attendance/records";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestClock clock;
    @Autowired AttendanceRecordRepository records;
    @Autowired ScanEventRepository scans;

    private UUID school;
    private Caller admin;
    private UUID front;
    private UUID dara;
    private UUID sokha;
    private UUID bopha;
    private UUID gate;
    private UUID daraLate;

    @BeforeEach
    void threeWeeks() throws Exception {
        clock.set(LocalDate.of(2026, 9, 27).atTime(8, 0).atZone(PP).toInstant());
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Admin attendance school', now(), now())")
                .param("id", school).param("c", "A" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        front = UUID.fromString(id(send(post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"Front office\"}}")));
        UUID finance = UUID.fromString(id(send(post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"Finance\"}}")));
        String area = id(send(post("/api/v1/admin/areas"), "{\"name\":\"CMT School\",\"latitude\":11.5564,\"longitude\":104.9282,\"radiusMeters\":120}"));
        gate = UUID.fromString(id(send(post("/api/v1/admin/gates"), "{\"name\":\"Main gate\",\"areaId\":\"" + area + "\"}")));
        String full = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Full day\",\"graceMinutes\":5,\"earlyCheckOutNeedsReason\":true,"
                + "\"shifts\":[{\"start\":\"07:45\",\"end\":\"17:00\"}]}"));
        String split = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Split day\",\"graceMinutes\":5,\"earlyCheckOutNeedsReason\":true,"
                + "\"shifts\":[{\"name\":\"Morning\",\"start\":\"07:45\",\"end\":\"11:30\"},{\"name\":\"Afternoon\",\"start\":\"13:30\",\"end\":\"17:00\"}]}"));
        dara = person("Dara Heng", front, full);
        sokha = person("Sokha Lim", finance, split);
        bopha = person("Bopha Chan", front, full);
        send(post("/api/v1/admin/holidays"), "{\"name\":{\"en\":\"Sports day\"},\"kind\":\"SCHOOL\",\"startDate\":\"2026-10-09\",\"endDate\":\"2026-10-09\"}")
                .andExpect(status().isCreated());
        // an exam day for Sokha (the month grid reads date changes for the whole month at once, the day view one by one)
        send(post("/api/v1/admin/schedules/date-changes"), "{\"dates\":[\"2026-10-07\"],\"target\":\"person\",\"targetId\":\"" + sokha
                + "\",\"templateId\":\"" + full + "\",\"label\":\"Exam day\"}").andExpect(status().is2xxSuccessful());

        record(dara, "2026-10-12", 1, "07:40", "17:00");
        daraLate = record(dara, "2026-10-13", 1, "07:57", "17:00");
        record(dara, "2026-10-15", 1, "07:40", null);
        record(sokha, "2026-10-12", 1, "07:45", "11:30");
        record(sokha, "2026-10-12", 2, "13:25", "17:00");
        record(sokha, "2026-10-13", 1, "07:45", "11:30");
        SchoolOverride.runAs(school, () -> scans.saveAndFlush(new ScanEvent(new ScanEvent.Facts(sokha, UUID.randomUUID(), null, "out-" + UUID.randomUUID(),
                ScanEvent.Source.ONLINE, at("2026-10-15", "08:50"), at("2026-10-15", "08:50"), null, gate, 1, BigDecimal.ONE, BigDecimal.ONE, null,
                new BigDecimal("1203.2"), null, false, false, null, null, "OUTSIDE_AREA", null, null, null), Map.of())));
        clock.set(TODAY.atTime(9, 0).atZone(PP).toInstant());
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @Test
    void today_notYetOutsideAndWorking_theChipsAndTheFilters() throws Exception {
        JsonNode today = body(ask(DAY));
        assertThat(today.get("date").asText()).isEqualTo("2026-10-15");
        assertThat(statuses(today)).containsEntry("Dara Heng", "ON_TIME").containsEntry("Sokha Lim", "OUTSIDE_AREA").containsEntry("Bopha Chan", "NOT_YET");
        assertThat(today.get("counts").get("ALL").asInt()).isEqualTo(3);
        assertThat(today.get("counts").get("NOT_YET").asInt()).isEqualTo(1);
        assertThat(today.get("counts").get("OUTSIDE_AREA").asInt()).isEqualTo(1);
        JsonNode daraRow = rowOf(today, "Dara Heng");
        assertThat(daraRow.get("gate").get("name").asText()).isEqualTo("Main gate");
        assertThat(daraRow.get("shifts").get(0).get("checkIn").asText()).isEqualTo(at("2026-10-15", "07:40").toString());
        assertThat(rowOf(today, "Sokha Lim").get("shifts")).hasSize(2);   // two lines, both still to come

        // the chips narrow the rows, not the counts; department / role / search narrow both
        JsonNode notYet = body(ask(DAY + "?status=NOT_YET"));
        assertThat(statuses(notYet)).containsOnlyKeys("Bopha Chan");
        assertThat(notYet.get("counts").get("ALL").asInt()).isEqualTo(3);
        assertThat(statuses(body(ask(DAY + "?department=" + front)))).containsOnlyKeys("Dara Heng", "Bopha Chan");
        assertThat(statuses(body(ask(DAY + "?q=sokha")))).containsOnlyKeys("Sokha Lim");
        assertThat(statuses(body(ask(DAY + "?role=teacher")))).isEmpty();
        ask(DAY + "?status=LOST", 400);

        // after the last shift ends, nobody who has not come is "not yet" any more
        clock.set(TODAY.atTime(17, 30).atZone(PP).toInstant());
        assertThat(statuses(body(ask(DAY)))).containsEntry("Bopha Chan", "ABSENT");
    }

    @Test
    void aPastDay_late_missing_absent_andTheHoliday() throws Exception {
        JsonNode tue = body(ask(DAY + "?date=2026-10-13"));
        assertThat(statuses(tue)).containsEntry("Dara Heng", "LATE").containsEntry("Sokha Lim", "MISSING").containsEntry("Bopha Chan", "ABSENT");
        assertThat(rowOf(tue, "Dara Heng").get("lateMinutes").asInt()).isEqualTo(12);
        JsonNode sokhaTue = rowOf(tue, "Sokha Lim").get("shifts");
        assertThat(sokhaTue.get(0).get("status").asText()).isEqualTo("ON_TIME");
        assertThat(sokhaTue.get(1).get("status").asText()).isEqualTo("ABSENT");   // the afternoon was missed
        assertThat(statuses(body(ask(DAY + "?date=2026-10-09")))).containsValues("HOLIDAY", "HOLIDAY", "HOLIDAY");
        assertThat(statuses(body(ask(DAY + "?date=2026-10-10")))).isEmpty();   // Saturday: nobody works
    }

    @Test
    void theMonthGridIsTheDayRecordsCounted() throws Exception {
        JsonNode month = body(ask(MONTH + "?month=2026-10"));
        assertThat(month.get("month").asText()).isEqualTo("2026-10");
        assertThat(month.get("dates")).hasSize(31);
        Map<String, JsonNode> rows = new HashMap<>();
        month.get("rows").forEach(r -> rows.put(r.get("person").get("name").get("en").asText(), r));

        // every cell is what the day view says that day; the totals are those days counted
        Map<String, int[]> totals = new HashMap<>();
        for (int d = 1; d <= 31; d++) {
            LocalDate date = LocalDate.of(2026, 10, d);
            Map<String, String> fromDay = new HashMap<>();
            Map<String, Integer> worked = new HashMap<>();
            body(ask(DAY + "?date=" + date)).get("rows").forEach(r -> {
                fromDay.put(r.get("person").get("name").get("en").asText(), r.get("status").asText());
                worked.put(r.get("person").get("name").get("en").asText(), r.get("workedMinutes").asInt());
            });
            for (var e : rows.entrySet()) {
                JsonNode cell = e.getValue().get("days").get(d - 1);
                String fromMonth = cell.isNull() ? null : cell.asText();
                assertThat(fromMonth).as("%s on %s", e.getKey(), date).isEqualTo(fromDay.get(e.getKey()));
                int[] t = totals.computeIfAbsent(e.getKey(), k -> new int[4]);
                String st = fromDay.get(e.getKey());
                if (st != null && List.of("ON_TIME", "LATE", "EARLY", "MISSING").contains(st)) {
                    t[0]++;
                }
                if ("LATE".equals(st)) {
                    t[1]++;
                }
                if ("ABSENT".equals(st)) {
                    t[2]++;
                }
                t[3] += worked.getOrDefault(e.getKey(), 0);
            }
        }
        for (var e : rows.entrySet()) {
            int[] t = totals.get(e.getKey());
            JsonNode r = e.getValue();
            assertThat(List.of(r.get("present").asInt(), r.get("late").asInt(), r.get("absent").asInt(), r.get("workedMinutes").asInt()))
                    .as(e.getKey()).containsExactly(t[0], t[1], t[2], t[3]);
        }
        // and they are the numbers of the story above
        JsonNode daraRow = rows.get("Dara Heng");
        assertThat(List.of(daraRow.get("present").asInt(), daraRow.get("late").asInt(), daraRow.get("absent").asInt())).containsExactly(3, 1, 7);
        assertThat(daraRow.get("workedMinutes").asInt()).isEqualTo(560 + 543);
        assertThat(daraRow.get("days").get(8).asText()).isEqualTo("HOLIDAY");
        assertThat(daraRow.get("days").get(15).isNull()).isTrue();   // the 16th is still to come
        assertThat(rows.get("Sokha Lim").get("workedMinutes").asInt()).isEqualTo(225 + 215 + 225);
        assertThat(rows.get("Bopha Chan").get("present").asInt()).isZero();
        assertThat(rowOf(body(ask(DAY + "?date=2026-10-07")), "Sokha Lim").get("label").asText()).isEqualTo("Exam day");
    }

    @Test
    void editingARecord_andEnteringAMissedShift() throws Exception {
        long version = jdbc.sql("select version from attendance_record where id = :id").param("id", daraLate).query(Long.class).single();
        JsonNode edited = body(send(put(RECORDS + "/" + daraLate), "{\"checkIn\":\"07:40\",\"checkOut\":\"17:00\",\"reason\":\"The gate phone was off\","
                + "\"version\":" + version + "}").andExpect(status().isOk()));
        assertThat(edited.get("status").asText()).isEqualTo("ON_TIME");
        assertThat(edited.get("lateMinutes").asInt()).isZero();
        assertThat(edited.get("edited").get("reason").asText()).isEqualTo("The gate phone was off");
        JsonNode tue = rowOf(body(ask(DAY + "?date=2026-10-13")), "Dara Heng");
        assertThat(tue.get("status").asText()).isEqualTo("ON_TIME");
        assertThat(tue.get("flags").get(0).asText()).isEqualTo("EDITED");
        assertThat(monthRow("Dara Heng").get("late").asInt()).isZero();   // the grid follows the record
        assertThat(jdbc.sql("select count(*) from outbox where type = 'attendance.recorded' and payload->>'kind' = 'EDITED' and payload->>'recordId' = :r")
                .param("r", daraLate.toString()).query(Long.class).single()).isEqualTo(1);

        // the same version again: someone changed it meanwhile
        send(put(RECORDS + "/" + daraLate), "{\"checkIn\":\"07:41\",\"reason\":\"x\",\"version\":" + version + "}").andExpect(status().isConflict());
        assertThat(rules(send(put(RECORDS + "/" + daraLate), "{\"checkIn\":\"08:00\",\"checkOut\":\"07:00\",\"reason\":\" \"}")))
                .containsExactlyInAnyOrder("checkOut BeforeCheckIn", "reason NotBlank", "version NotNull");
        assertThat(rules(send(put(RECORDS + "/" + daraLate), "{\"checkOut\":\"25:00\",\"reason\":\"x\",\"version\":1}")))
                .containsExactlyInAnyOrder("checkIn NotNull", "checkOut Pattern");
        send(put(RECORDS + "/" + UUID.randomUUID()), "{\"checkIn\":\"07:40\",\"reason\":\"x\",\"version\":0}").andExpect(status().isNotFound());

        // Bopha's Wednesday nobody scanned: entered by the admin
        JsonNode manual = body(send(post(RECORDS), "{\"personId\":\"" + bopha + "\",\"date\":\"2026-10-14\",\"checkIn\":\"07:45\",\"checkOut\":\"17:00\","
                + "\"reason\":\"Signed the paper register\"}").andExpect(status().isCreated()));
        assertThat(manual.get("status").asText()).isEqualTo("ON_TIME");
        JsonNode wed = rowOf(body(ask(DAY + "?date=2026-10-14")), "Bopha Chan");
        assertThat(wed.get("status").asText()).isEqualTo("ON_TIME");
        assertThat(wed.get("flags").get(0).asText()).isEqualTo("MANUAL");
        send(post(RECORDS), "{\"personId\":\"" + bopha + "\",\"date\":\"2026-10-14\",\"checkIn\":\"07:45\",\"reason\":\"again\"}")
                .andExpect(status().isConflict());
        assertThat(body(send(post(RECORDS), "{\"personId\":\"" + bopha + "\",\"date\":\"2026-10-10\",\"checkIn\":\"07:45\",\"reason\":\"x\"}")
                .andExpect(status().isUnprocessableEntity())).get("code").asText()).isEqualTo("NOT_A_WORKING_DAY");
        assertThat(rules(send(post(RECORDS), "{\"personId\":\"" + bopha + "\",\"date\":\"2026-10-16\",\"checkIn\":\"07:45\",\"reason\":\"x\"}")))
                .containsExactly("date Future");
        assertThat(rules(send(post(RECORDS), "{\"personId\":\"" + bopha + "\",\"date\":\"2026-10-15\",\"checkIn\":\"10:00\",\"reason\":\"x\"}")))
                .containsExactly("checkIn Future");   // it is 09:00
    }

    @Test
    void exports_theDayAndTheMonth() throws Exception {
        clock.set(LocalDate.of(2026, 9, 27).atTime(8, 0).atZone(PP).toInstant());
        UUID noDepartment = UUID.fromString(id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"Vanna Ouk\"},\"role\":\"staff\"}")));
        String full = jdbc.sql("select id::text from shift_template where school_id = :s and name = 'Full day'").param("s", school).query(String.class).single();
        StringBuilder week = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            week.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":").append(wd == 2 ? "\"" + full + "\"" : "null").append("}");
        }
        send(put("/api/v1/admin/schedules/weekly?target=person&id=" + noDepartment), "{\"effectiveFrom\":\"2026-09-28\",\"days\":[" + week + "]}")
                .andExpect(status().isOk());
        clock.set(TODAY.atTime(9, 0).atZone(PP).toInstant());
        ResultActions dayFile = mvc.perform(get("/api/v1/admin/attendance/export.xlsx?date=2026-10-13").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=\"attendance_2026-10-13.xlsx\""));
        List<List<String>> day = sheet(dayFile);
        assertThat(day.getFirst()).startsWith("Staff ID", "Name", "Department", "Day status", "Shift", "Check-in", "Check-out", "Hours");
        assertThat(day.stream().filter(r -> r.contains("Dara Heng")).findFirst().orElseThrow())
                .contains("Front office", "Late", "07:57", "17:00", "9:03");
        assertThat(day.stream().filter(r -> r.contains("Sokha Lim")).count()).isEqualTo(2);   // one line per shift
        assertThat(day.stream().filter(r -> r.contains("Vanna Ouk")).findFirst().orElseThrow()).contains("Absent");   // no department: blank

        ResultActions monthFile = mvc.perform(get("/api/v1/admin/attendance/export.xlsx?view=month&month=2026-10&personId=" + dara)
                .header("Authorization", admin.bearer())).andExpect(status().isOk());
        List<List<String>> month = sheet(monthFile);
        assertThat(month).hasSize(2);   // the header and Dara
        assertThat(month.get(0)).contains("1", "31", "Present", "Late", "Absent", "Hours");
        assertThat(month.get(1)).contains("Dara Heng", "Holiday", "Late", "Absent");
        assertThat(month.get(1).get(month.get(0).indexOf("Present"))).isEqualTo("3");
        assertThat(month.get(1).get(month.get(0).indexOf("Hours"))).isEqualTo("18:23");

        // a Viewer may look, not change or export
        Caller viewer = TestJwt.webUser("viewer", school);
        mvc.perform(get(DAY).header("Authorization", viewer.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/attendance/export.xlsx").header("Authorization", viewer.bearer())).andExpect(status().isForbidden());
        mvc.perform(get(DAY).header("Authorization", TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), dara).bearer()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private UUID person(String name, UUID department, String template) throws Exception {
        UUID p = UUID.fromString(id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"" + name + "\"},\"role\":\"staff\","
                + "\"departmentId\":\"" + department + "\"}")));
        StringBuilder days = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            days.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":")
                    .append(wd <= 5 ? "\"" + template + "\"" : "null").append("}");
        }
        send(put("/api/v1/admin/schedules/weekly?target=person&id=" + p), "{\"effectiveFrom\":\"2026-09-28\",\"days\":[" + days + "]}")
                .andExpect(status().isOk());
        return p;
    }

    private UUID record(UUID who, String day, int shift, String in, String out) {
        LocalDate d = LocalDate.parse(day);
        AttendanceRecord.Plan plan = shift == 1 && who.equals(sokha) ? new AttendanceRecord.Plan(1, "Morning", LocalTime.of(7, 45), LocalTime.of(11, 30), 5, true)
                : shift == 2 ? new AttendanceRecord.Plan(2, "Afternoon", LocalTime.of(13, 30), LocalTime.of(17, 0), 5, true)
                : new AttendanceRecord.Plan(1, null, LocalTime.of(7, 45), LocalTime.of(17, 0), 5, true);
        return SchoolOverride.callAs(school, () -> {
            AttendanceRecord r = AttendanceRecord.checkedIn(who, d, plan, at(day, in), gate, null, AttendanceRecord.Source.SCAN, PP);
            if (out != null) {
                r.checkOut(at(day, out), gate, null, null, PP);
            }
            return records.saveAndFlush(r);
        }).getId();
    }

    private static Instant at(String day, String time) {
        return LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(PP).toInstant();
    }

    private JsonNode monthRow(String name) throws Exception {
        for (JsonNode r : body(ask(MONTH + "?month=2026-10")).get("rows")) {
            if (r.get("person").get("name").get("en").asText().equals(name)) {
                return r;
            }
        }
        throw new AssertionError(name);
    }

    private static Map<String, String> statuses(JsonNode page) {
        Map<String, String> out = new HashMap<>();
        page.get("rows").forEach(r -> out.put(r.get("person").get("name").get("en").asText(), r.get("status").asText()));
        return out;
    }

    private static JsonNode rowOf(JsonNode page, String name) {
        for (JsonNode r : page.get("rows")) {
            if (r.get("person").get("name").get("en").asText().equals(name)) {
                return r;
            }
        }
        throw new AssertionError(name);
    }

    private List<String> rules(ResultActions result) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        List<String> out = new ArrayList<>();
        problem.get("errors").forEach(e -> out.add(e.get("field").asText() + " " + e.get("rule").asText()));
        return out;
    }

    private static List<List<String>> sheet(ResultActions file) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.andReturn().getResponse().getContentAsByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (Row r : s) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < r.getLastCellNum(); c++) {
                    var cell = r.getCell(c);
                    cells.add(cell == null ? "" : switch (cell.getCellType()) {
                        case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                        case STRING -> cell.getStringCellValue();
                        default -> "";
                    });
                }
                rows.add(cells);
            }
            return rows;
        }
    }

    private ResultActions ask(String path) throws Exception {
        return ask(path, 200);
    }

    private ResultActions ask(String path, int expected) throws Exception {
        return mvc.perform(get(path).header("Authorization", admin.bearer())).andExpect(status().is(expected));
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
