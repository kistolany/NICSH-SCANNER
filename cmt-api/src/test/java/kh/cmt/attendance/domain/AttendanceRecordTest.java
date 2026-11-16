package kh.cmt.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import kh.cmt.attendance.domain.AttendanceRecord.Plan;
import kh.cmt.attendance.domain.AttendanceRecord.Status;
import org.junit.jupiter.api.Test;

/** The rule of a shift: late after the grace (counted from the start), early before the end, worked minutes, status. */
class AttendanceRecordTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate DAY = LocalDate.of(2026, 10, 12);
    static final Plan MORNING = new Plan(1, "Morning", LocalTime.of(7, 45), LocalTime.of(11, 30), 5, true);
    static final UUID PERSON = UUID.randomUUID();

    static Instant at(int h, int m, int s) {
        return DAY.atTime(h, m, s).atZone(PP).toInstant();
    }

    static AttendanceRecord in(Instant at) {
        return AttendanceRecord.checkedIn(PERSON, DAY, MORNING, at, null, null, AttendanceRecord.Source.SCAN, PP);
    }

    @Test
    void lateOnlyAfterTheGrace_countedFromTheStart() {
        assertThat(in(at(7, 40, 0)).getStatus()).isEqualTo(Status.ON_TIME);
        assertThat(in(at(7, 50, 0))).satisfies(r -> {   // exactly start + grace: on time
            assertThat(r.getStatus()).isEqualTo(Status.ON_TIME);
            assertThat(r.getLateMinutes()).isZero();
        });
        assertThat(in(at(7, 50, 1))).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(Status.LATE);
            assertThat(r.getLateMinutes()).isEqualTo(5);
        });
        assertThat(in(at(7, 57, 30)).getLateMinutes()).isEqualTo(12);   // the design's "Late 12 min"
        AttendanceRecord noGrace = AttendanceRecord.checkedIn(PERSON, DAY, new Plan(1, null, LocalTime.of(7, 0), LocalTime.of(12, 0), 0, true),
                at(7, 0, 59), null, null, AttendanceRecord.Source.SCAN, PP);
        assertThat(noGrace.getLateMinutes()).isZero();   // parts of a minute do not count: not late
        assertThat(noGrace.getStatus()).isEqualTo(Status.ON_TIME);
    }

    @Test
    void earlyAndWorked_andLateWinsOverEarly() {
        AttendanceRecord r = in(at(7, 44, 0));
        assertThat(r.needsEarlyReason(at(11, 0, 0), PP)).isTrue();
        assertThat(r.needsEarlyReason(at(11, 30, 0), PP)).isFalse();
        r.checkOut(at(11, 0, 0), null, null, "Doctor", PP);
        assertThat(r.getStatus()).isEqualTo(Status.EARLY);
        assertThat(r.getEarlyMinutes()).isEqualTo(30);
        assertThat(r.getWorkedMinutes()).isEqualTo(196);
        assertThat(r.getEarlyReason()).isEqualTo("Doctor");

        AttendanceRecord both = in(at(8, 0, 0));
        both.checkOut(at(11, 10, 0), null, null, null, PP);
        assertThat(both.getStatus()).isEqualTo(Status.LATE);
        assertThat(both.getLateMinutes()).isEqualTo(15);
        assertThat(both.getEarlyMinutes()).isEqualTo(20);

        AttendanceRecord full = in(at(7, 45, 0));
        full.checkOut(at(11, 45, 0), null, null, null, PP);
        assertThat(full.getStatus()).isEqualTo(Status.ON_TIME);
        assertThat(full.getWorkedMinutes()).isEqualTo(240);
        assertThatThrownBy(() -> full.checkOut(at(12, 0, 0), null, null, null, PP)).isInstanceOf(IllegalStateException.class);

        // a shift whose template does not ask for a reason
        AttendanceRecord free = AttendanceRecord.checkedIn(PERSON, DAY, new Plan(2, "Afternoon", LocalTime.of(13, 30), LocalTime.of(17, 0), 5, false),
                at(13, 30, 0), null, null, AttendanceRecord.Source.SCAN, PP);
        assertThat(free.needsEarlyReason(at(16, 0, 0), PP)).isFalse();
    }

    @Test
    void endOfDayAndDaysWithoutACheckIn() {
        AttendanceRecord open = in(at(7, 45, 0));
        open.missingCheckOut();
        assertThat(open.getStatus()).isEqualTo(Status.MISSING);
        AttendanceRecord absent = AttendanceRecord.without(PERSON, DAY, MORNING, Status.ABSENT);
        assertThat(absent.getSource()).isEqualTo(AttendanceRecord.Source.SYSTEM);
        assertThat(absent.getInAt()).isNull();
        absent.missingCheckOut();   // no check-in: stays absent
        assertThat(absent.getStatus()).isEqualTo(Status.ABSENT);
        assertThatThrownBy(() -> AttendanceRecord.without(PERSON, DAY, MORNING, Status.LATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> absent.checkOut(at(11, 0, 0), null, null, null, PP)).isInstanceOf(IllegalStateException.class);
    }
}
