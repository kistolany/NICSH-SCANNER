package kh.cmt.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.domain.ScanEvent;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.attendance.infrastructure.ScanEventRepository;
import kh.cmt.shared.security.SchoolOverride;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Step 7.1: the tables keep their promises — scans never change, one key is one scan, one record per shift. */
@ApiTest
class AttendanceTablesTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;
    @Autowired ScanEventRepository scans;
    @Autowired AttendanceRecordRepository records;
    @Autowired PlatformTransactionManager transactions;

    private UUID school;
    private Caller admin;
    private UUID person;

    @BeforeEach
    void aPerson() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Attendance school', now(), now())")
                .param("id", school).param("c", "T" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        String created = mvc.perform(post("/api/v1/admin/people").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"km\":\"ក\",\"en\":\"Sokha Lim\"},\"role\":\"staff\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        person = UUID.fromString(json.readTree(created).get("id").asText());
    }

    @Test
    void aScanIsKeptAsItCameIn_andOneKeyIsOneScan() {
        ScanEvent scan = inSchool(() -> scans.saveAndFlush(new ScanEvent(facts("key-" + UUID.randomUUID(), ScanEvent.ACCEPTED))));
        assertThat(scan.getId()).isNotNull();
        assertThat(inSchool(() -> scans.findByIdempotencyKey(scan.getIdempotencyKey())).map(ScanEvent::getId)).contains(scan.getId());

        // the same key again is refused by the database
        assertThatThrownBy(() -> inSchool(() -> scans.saveAndFlush(new ScanEvent(facts(scan.getIdempotencyKey(), "OUTSIDE_AREA")))))
                .isInstanceOf(DataIntegrityViolationException.class);
        // never changed, never removed
        assertThatThrownBy(() -> jdbc.sql("update scan_event set result = 'X' where id = :id").param("id", scan.getId()).update())
                .hasMessageContaining("never changed or removed");
        assertThatThrownBy(() -> jdbc.sql("delete from scan_event where id = :id").param("id", scan.getId()).update())
                .hasMessageContaining("never changed or removed");
        // a refused scan is kept too, with its code
        ScanEvent refused = inSchool(() -> scans.saveAndFlush(new ScanEvent(facts("key-" + UUID.randomUUID(), "QR_EXPIRED"))));
        assertThat(refused.accepted()).isFalse();
    }

    @Test
    void oneRecordPerPersonDayAndShift_andTheyKeepThePersonFromBeingDeleted() throws Exception {
        LocalDate day = LocalDate.now(clock);
        AttendanceRecord.Plan morning = new AttendanceRecord.Plan(1, "Morning", LocalTime.of(7, 45), LocalTime.of(11, 30), 5, true);
        AttendanceRecord.Plan afternoon = new AttendanceRecord.Plan(2, "Afternoon", LocalTime.of(13, 30), LocalTime.of(17, 0), 5, true);
        Instant in = day.atTime(7, 57).atZone(clock.getZone()).toInstant();
        AttendanceRecord first = inSchool(() -> records.saveAndFlush(
                AttendanceRecord.checkedIn(person, day, morning, in, null, null, AttendanceRecord.Source.SCAN, clock.getZone())));
        assertThat(first.getLateMinutes()).isEqualTo(12);
        inSchool(() -> records.saveAndFlush(AttendanceRecord.without(person, day, afternoon, AttendanceRecord.Status.ABSENT)));
        assertThat(inSchool(() -> records.findByPersonIdAndWorkDateOrderByShiftIdx(person, day))).extracting(AttendanceRecord::getShiftIdx)
                .containsExactly(1, 2);

        // a second record for the same shift is refused
        assertThatThrownBy(() -> inSchool(() -> records.saveAndFlush(
                AttendanceRecord.checkedIn(person, day, morning, in, null, null, AttendanceRecord.Source.SCAN, clock.getZone()))))
                .isInstanceOf(DataIntegrityViolationException.class);

        // check-out on the stored record
        inSchool(() -> {
            AttendanceRecord r = records.findByPersonIdAndWorkDateAndShiftIdx(person, day, 1).orElseThrow();
            r.checkOut(day.atTime(11, 35).atZone(clock.getZone()).toInstant(), null, null, null, clock.getZone());
            return records.saveAndFlush(r);
        });
        AttendanceRecord stored = inSchool(() -> records.findByPersonIdAndWorkDateAndShiftIdx(person, day, 1).orElseThrow());
        assertThat(stored.getStatus()).isEqualTo(AttendanceRecord.Status.LATE);
        assertThat(stored.getWorkedMinutes()).isEqualTo(218);

        // attendance is history: the person cannot be deleted forever any more
        mvc.perform(post("/api/v1/admin/people/" + person + "/inactivate").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"RESIGNED\"}")).andExpect(status().is2xxSuccessful());
        mvc.perform(delete("/api/v1/admin/people/" + person).header("Authorization", admin.bearer())).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ helpers

    private ScanEvent.Facts facts(String key, String result) {
        Instant now = clock.instant();
        return new ScanEvent.Facts(person, UUID.randomUUID(), UUID.randomUUID(), key, ScanEvent.Source.ONLINE, now, now, "hash", null, 3,
                new java.math.BigDecimal("11.556400"), new java.math.BigDecimal("104.928200"), new java.math.BigDecimal("8.5"),
                java.math.BigDecimal.ZERO, null, false, false, null, ScanEvent.Kind.CHECK_IN, result, null, 1, null);
    }

    /** Repository work as the test school (no caller: record scope is the whole school). */
    private <T> T inSchool(java.util.function.Supplier<T> work) {
        return SchoolOverride.callAs(school, () -> new TransactionTemplate(transactions).execute(s -> work.get()));
    }
}
