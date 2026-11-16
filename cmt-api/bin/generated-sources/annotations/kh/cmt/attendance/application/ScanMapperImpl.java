package kh.cmt.attendance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import javax.annotation.processing.Generated;
import kh.cmt.attendance.api.AttendanceRecorded;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.domain.ScanEvent;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-11-05T20:28:07+0700",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.46.200.v20261008-1115, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
class ScanMapperImpl implements ScanMapper {

    @Override
    public ScanViews.Result result(AttendanceRecord r, String result, Instant time, boolean twoShifts, int workedTodayMinutes, ScanViews.NextShift nextShift, ScanViews.Gate gate) {
        if ( r == null && result == null && time == null && nextShift == null && gate == null ) {
            return null;
        }

        UUID recordId = null;
        int shiftIdx = 0;
        LocalTime shiftStart = null;
        LocalTime shiftEnd = null;
        String shiftName = null;
        String status = null;
        int lateMinutes = 0;
        int earlyMinutes = 0;
        int workedMinutes = 0;
        if ( r != null ) {
            recordId = r.getId();
            shiftIdx = r.getShiftIdx();
            shiftStart = r.getPlannedStart();
            shiftEnd = r.getPlannedEnd();
            shiftName = r.getShiftName();
            if ( r.getStatus() != null ) {
                status = r.getStatus().name();
            }
            lateMinutes = r.getLateMinutes();
            earlyMinutes = r.getEarlyMinutes();
            workedMinutes = r.getWorkedMinutes();
        }
        String result1 = null;
        result1 = result;
        Instant time1 = null;
        time1 = time;
        boolean twoShifts1 = false;
        twoShifts1 = twoShifts;
        int workedTodayMinutes1 = 0;
        workedTodayMinutes1 = workedTodayMinutes;
        ScanViews.NextShift nextShift1 = null;
        nextShift1 = nextShift;
        ScanViews.Gate gate1 = null;
        gate1 = gate;

        ScanViews.Result result2 = new ScanViews.Result( result1, time1, shiftIdx, shiftName, twoShifts1, status, lateMinutes, earlyMinutes, workedMinutes, workedTodayMinutes1, shiftStart, shiftEnd, nextShift1, gate1, recordId );

        return result2;
    }

    @Override
    public AttendanceRecorded recorded(AttendanceRecord r, ScanEvent.Kind kind, Instant at) {
        if ( r == null && kind == null && at == null ) {
            return null;
        }

        UUID recordId = null;
        UUID personId = null;
        LocalDate workDate = null;
        int shiftIdx = 0;
        String status = null;
        int lateMinutes = 0;
        int earlyMinutes = 0;
        String source = null;
        if ( r != null ) {
            recordId = r.getId();
            personId = r.getPersonId();
            workDate = r.getWorkDate();
            shiftIdx = r.getShiftIdx();
            if ( r.getStatus() != null ) {
                status = r.getStatus().name();
            }
            lateMinutes = r.getLateMinutes();
            earlyMinutes = r.getEarlyMinutes();
            if ( r.getSource() != null ) {
                source = r.getSource().name();
            }
        }
        String kind1 = null;
        if ( kind != null ) {
            kind1 = kind.name();
        }
        Instant at1 = null;
        at1 = at;

        AttendanceRecorded attendanceRecorded = new AttendanceRecorded( recordId, personId, workDate, shiftIdx, kind1, at1, status, lateMinutes, earlyMinutes, source );

        return attendanceRecorded;
    }

    @Override
    public AttendanceRecorded changed(AttendanceRecord r, String kind, Instant at) {
        if ( r == null && kind == null && at == null ) {
            return null;
        }

        UUID recordId = null;
        UUID personId = null;
        LocalDate workDate = null;
        int shiftIdx = 0;
        String status = null;
        int lateMinutes = 0;
        int earlyMinutes = 0;
        String source = null;
        if ( r != null ) {
            recordId = r.getId();
            personId = r.getPersonId();
            workDate = r.getWorkDate();
            shiftIdx = r.getShiftIdx();
            if ( r.getStatus() != null ) {
                status = r.getStatus().name();
            }
            lateMinutes = r.getLateMinutes();
            earlyMinutes = r.getEarlyMinutes();
            if ( r.getSource() != null ) {
                source = r.getSource().name();
            }
        }
        String kind1 = null;
        kind1 = kind;
        Instant at1 = null;
        at1 = at;

        AttendanceRecorded attendanceRecorded = new AttendanceRecorded( recordId, personId, workDate, shiftIdx, kind1, at1, status, lateMinutes, earlyMinutes, source );

        return attendanceRecorded;
    }
}
