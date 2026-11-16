package kh.cmt.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * One shift of one person on one day: the plan of that day (copied, so later template or schedule changes never
 * rewrite it), the check-in and check-out, and what they mean.
 * <p>
 * <b>The rule:</b> a check-in after {@code start + grace} is late by the minutes since {@code start} ("Late 12 min" for
 * 07:57 against 07:45 with 5 min grace); at or before {@code start + grace} it is on time. A check-out before
 * {@code end} is early by the minutes left. Worked minutes run from check-in to check-out. Status: LATE when late
 * (whatever the check-out), else EARLY when it left early, else ON_TIME; both minutes are always kept. Minutes an admin
 * excused (an approved late / early reason, step 10.1) stay but no longer count: an excused late check-in is not LATE. MISSING (checked
 * in, never out), ABSENT and LEAVE are set at the end of the day (7.8) or by leave (phase 9). Minutes are whole minutes
 * (parts of a minute do not count). Times are the school's time zone.
 */
@Entity
@Table(name = "attendance_record")
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON_ID, deduceAliasInjectionPoints = false)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRecord extends BaseEntity {

    public enum Status { ON_TIME, LATE, EARLY, MISSING, ABSENT, LEAVE }

    public enum Source { SCAN, OFFLINE, MANUAL, SYSTEM }

    /** A shift as planned for the day (from the "today's schedule" resolver). */
    public record Plan(int shiftIdx, String name, LocalTime start, LocalTime end, int graceMinutes, boolean earlyNeedsReason) {
    }

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(name = "shift_idx", nullable = false, updatable = false)
    private int shiftIdx;

    @Column(name = "shift_name", updatable = false)
    private String shiftName;

    @Column(name = "planned_start", nullable = false, updatable = false)
    private LocalTime plannedStart;

    @Column(name = "planned_end", nullable = false, updatable = false)
    private LocalTime plannedEnd;

    @Column(name = "grace_minutes", nullable = false, updatable = false)
    private int graceMinutes;

    @Column(name = "early_needs_reason", nullable = false, updatable = false)
    private boolean earlyNeedsReason;

    @Column(name = "in_at")
    private Instant inAt;

    @Column(name = "in_gate_id")
    private UUID inGateId;

    @Column(name = "in_scan_id")
    private UUID inScanId;

    @Column(name = "out_at")
    private Instant outAt;

    @Column(name = "out_gate_id")
    private UUID outGateId;

    @Column(name = "out_scan_id")
    private UUID outScanId;

    @Column(name = "late_minutes", nullable = false)
    private int lateMinutes;

    @Column(name = "early_minutes", nullable = false)
    private int earlyMinutes;

    @Column(name = "worked_minutes", nullable = false)
    private int workedMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(name = "late_reason")
    private String lateReason;

    @Column(name = "early_reason")
    private String earlyReason;

    @Column(name = "late_excused", nullable = false)
    private boolean lateExcused;

    @Column(name = "early_excused", nullable = false)
    private boolean earlyExcused;

    @Column(name = "edited_by")
    private UUID editedBy;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "edit_reason")
    private String editReason;

    private AttendanceRecord(UUID personId, LocalDate workDate, Plan plan, Status status, Source source) {
        this.personId = personId;
        this.workDate = workDate;
        this.shiftIdx = plan.shiftIdx();
        this.shiftName = plan.name();
        this.plannedStart = plan.start();
        this.plannedEnd = plan.end();
        this.graceMinutes = plan.graceMinutes();
        this.earlyNeedsReason = plan.earlyNeedsReason();
        this.status = status;
        this.source = source;
    }

    /** A check-in: the record of that shift starts. */
    public static AttendanceRecord checkedIn(UUID personId, LocalDate day, Plan plan, Instant at, UUID gateId, UUID scanId, Source source,
            ZoneId zone) {
        AttendanceRecord r = new AttendanceRecord(personId, day, plan, Status.ON_TIME, source);
        r.inAt = at;
        r.inGateId = gateId;
        r.inScanId = scanId;
        r.lateMinutes = lateMinutes(day, plan.start(), plan.graceMinutes(), at, zone);
        r.status = r.timeStatus();
        return r;
    }

    /** A day without a check-in (end-of-day job: ABSENT; leave: LEAVE). */
    public static AttendanceRecord without(UUID personId, LocalDate day, Plan plan, Status status) {
        if (status != Status.ABSENT && status != Status.LEAVE) {
            throw new IllegalArgumentException("only ABSENT or LEAVE without a check-in");
        }
        return new AttendanceRecord(personId, day, plan, status, Source.SYSTEM);
    }

    /** Would checking out at {@code at} be early and need a reason (the app's "Leave early?" sheet)? */
    public boolean needsEarlyReason(Instant at, ZoneId zone) {
        return earlyNeedsReason && earlyMinutes(workDate, plannedEnd, at, zone) > 0;
    }

    public void checkOut(Instant at, UUID gateId, UUID scanId, String reason, ZoneId zone) {
        if (inAt == null) {
            throw new IllegalStateException("no check-in to check out from");
        }
        if (outAt != null) {
            throw new IllegalStateException("already checked out");
        }
        outAt = at.isBefore(inAt) ? inAt : at;
        outGateId = gateId;
        outScanId = scanId;
        earlyReason = reason;
        earlyMinutes = earlyMinutes(workDate, plannedEnd, outAt, zone);
        workedMinutes = (int) Duration.between(inAt, outAt).toMinutes();
        status = timeStatus();
    }

    /**
     * An admin's change of the times (step 7.7): minutes and status follow the new times by the same rule as a scan
     * (no check-out: worked and early 0, status by the check-in); marked "Edited by admin" with who, when and why.
     */
    public void edit(Instant in, Instant out, UUID by, String reason, Instant at, ZoneId zone) {
        if (in == null || (out != null && out.isBefore(in))) {
            throw new IllegalArgumentException("a check-in, and a check-out not before it");
        }
        inAt = in;
        outAt = out;
        lateMinutes = lateMinutes(workDate, plannedStart, graceMinutes, in, zone);
        earlyMinutes = out == null ? 0 : earlyMinutes(workDate, plannedEnd, out, zone);
        workedMinutes = out == null ? 0 : (int) Duration.between(in, out).toMinutes();
        status = timeStatus();
        editedBy = by;
        editedAt = at;
        editReason = reason;
    }

    /** A shift nobody scanned, entered by an admin (step 7.7): the "Manual" flag. */
    public static AttendanceRecord manual(UUID personId, LocalDate day, Plan plan, Instant in, Instant out, UUID by, String reason, Instant at,
            ZoneId zone) {
        AttendanceRecord r = new AttendanceRecord(personId, day, plan, Status.ON_TIME, Source.MANUAL);
        r.edit(in, out, by, reason, at, zone);
        return r;
    }

    /**
     * End of the day with the school's rule "close automatically at the end of the shift": the check-out is the planned
     * end (not before the check-in); the reason says so.
     */
    public void autoClose(ZoneId zone) {
        if (inAt == null || outAt != null) {
            return;
        }
        Instant end = workDate.atTime(plannedEnd).atZone(zone).toInstant();
        checkOut(end, null, null, null, zone);
        editReason = AUTO_CLOSED;
    }

    /** The reason of a check-out the end-of-day job set at the shift's end. */
    public static final String AUTO_CLOSED = "Closed automatically at the end of the shift";

    /** End of the day: checked in, never out. */
    public void missingCheckOut() {
        if (inAt != null && outAt == null) {
            status = Status.MISSING;
        }
    }

    /** Leave approved for a shift the end-of-day job already marked absent (step 9.2): it becomes LEAVE. */
    public boolean onLeave() {
        if (source != Source.SYSTEM || status != Status.ABSENT) {
            return false;
        }
        status = Status.LEAVE;
        return true;
    }

    public void lateReason(String reason) {
        this.lateReason = reason;
    }

    /** An admin approved (true) or rejected the late reason: excused minutes no longer make the shift LATE. */
    public void excuseLate(boolean excused) {
        this.lateExcused = excused;
        restatus();
    }

    /** The same for leaving early. */
    public void excuseEarly(boolean excused) {
        this.earlyExcused = excused;
        restatus();
    }

    /** Late minutes that count (not excused). */
    public int countedLateMinutes() {
        return lateExcused ? 0 : lateMinutes;
    }

    public int countedEarlyMinutes() {
        return earlyExcused ? 0 : earlyMinutes;
    }

    /** The status by the times, for a record that has a check-in (MISSING, ABSENT, LEAVE stay). */
    private void restatus() {
        if (inAt != null && (status == Status.ON_TIME || status == Status.LATE || status == Status.EARLY)) {
            status = timeStatus();
        }
    }

    private Status timeStatus() {
        return countedLateMinutes() > 0 ? Status.LATE : outAt != null && countedEarlyMinutes() > 0 ? Status.EARLY : Status.ON_TIME;
    }

    /** The scan that made the check-in (the record is saved first, then its scan, which names the record). */
    public void checkInScan(UUID scanId) {
        this.inScanId = scanId;
    }

    // ------------------------------------------------------------------ the rule

    /** Minutes late: since {@code start}, but only once past {@code start + grace}. */
    static int lateMinutes(LocalDate day, LocalTime start, int grace, Instant in, ZoneId zone) {
        Instant planned = day.atTime(start).atZone(zone).toInstant();
        if (!in.isAfter(planned.plus(Duration.ofMinutes(grace)))) {
            return 0;
        }
        return (int) Duration.between(planned, in).toMinutes();
    }

    /** Minutes left before {@code end}. */
    static int earlyMinutes(LocalDate day, LocalTime end, Instant out, ZoneId zone) {
        Instant planned = day.atTime(end).atZone(zone).toInstant();
        return out.isBefore(planned) ? (int) Duration.between(out, planned).toMinutes() : 0;
    }
}
