package kh.cmt.attendance.application;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.processing.Generated;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.domain.AttendanceRecord;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-11-05T20:28:07+0700",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.46.200.v20261008-1115, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
class HistoryMapperImpl implements HistoryMapper {

    @Override
    public HistoryViews.Shift shift(DayHistory.Line line, HistoryViews.Gate inGate, HistoryViews.Gate outGate, HistoryViews.LateReason lateReason, HistoryViews.EarlyReason earlyReason, HistoryViews.Edited edited, List<HistoryViews.Correction> corrections) {
        if ( line == null && inGate == null && outGate == null && lateReason == null && earlyReason == null && edited == null && corrections == null ) {
            return null;
        }

        int shiftIdx = 0;
        String name = null;
        LocalTime start = null;
        LocalTime end = null;
        String status = null;
        Instant checkIn = null;
        Instant checkOut = null;
        int workedMinutes = 0;
        int lateMinutes = 0;
        int earlyMinutes = 0;
        UUID recordId = null;
        Long version = null;
        if ( line != null ) {
            shiftIdx = line.shiftIdx();
            name = line.name();
            start = line.start();
            end = line.end();
            if ( line.status() != null ) {
                status = line.status().name();
            }
            checkIn = lineRecordInAt( line );
            checkOut = lineRecordOutAt( line );
            workedMinutes = lineRecordWorkedMinutes( line );
            lateMinutes = lineRecordLateMinutes( line );
            earlyMinutes = lineRecordEarlyMinutes( line );
            recordId = lineRecordId( line );
            version = lineRecordVersion( line );
        }
        HistoryViews.Gate inGate1 = null;
        inGate1 = inGate;
        HistoryViews.Gate outGate1 = null;
        outGate1 = outGate;
        HistoryViews.LateReason lateReason1 = null;
        lateReason1 = lateReason;
        HistoryViews.EarlyReason earlyReason1 = null;
        earlyReason1 = earlyReason;
        HistoryViews.Edited edited1 = null;
        edited1 = edited;
        List<HistoryViews.Correction> corrections1 = null;
        List<HistoryViews.Correction> list = corrections;
        if ( list != null ) {
            corrections1 = new ArrayList<HistoryViews.Correction>( list );
        }

        Boolean lateExcused = line.record() != null && line.record().isLateExcused() ? Boolean.TRUE : null;
        Boolean earlyExcused = line.record() != null && line.record().isEarlyExcused() ? Boolean.TRUE : null;

        HistoryViews.Shift shift = new HistoryViews.Shift( shiftIdx, name, start, end, status, checkIn, checkOut, inGate1, outGate1, workedMinutes, lateMinutes, earlyMinutes, lateReason1, earlyReason1, lateExcused, earlyExcused, edited1, corrections1, recordId, version );

        return shift;
    }

    @Override
    public HistoryViews.Correction correction(Approvals.View view) {
        if ( view == null ) {
            return null;
        }

        String status = null;
        Instant decidedAt = null;
        String comment = null;

        if ( view.status() != null ) {
            status = view.status().name();
        }
        decidedAt = view.decidedAt();
        comment = view.comment();

        String field = (String) view.details().get("field");
        String time = (String) view.details().get("requestedTime");

        HistoryViews.Correction correction = new HistoryViews.Correction( field, time, status, decidedAt, comment );

        return correction;
    }

    private Instant lineRecordInAt(DayHistory.Line line) {
        AttendanceRecord record = line.record();
        if ( record == null ) {
            return null;
        }
        return record.getInAt();
    }

    private Instant lineRecordOutAt(DayHistory.Line line) {
        AttendanceRecord record = line.record();
        if ( record == null ) {
            return null;
        }
        return record.getOutAt();
    }

    private int lineRecordWorkedMinutes(DayHistory.Line line) {
        AttendanceRecord record = line.record();
        if ( record == null ) {
            return 0;
        }
        return record.getWorkedMinutes();
    }

    private int lineRecordLateMinutes(DayHistory.Line line) {
        AttendanceRecord record = line.record();
        if ( record == null ) {
            return 0;
        }
        return record.getLateMinutes();
    }

    private int lineRecordEarlyMinutes(DayHistory.Line line) {
        AttendanceRecord record = line.record();
        if ( record == null ) {
            return 0;
        }
        return record.getEarlyMinutes();
    }

    private UUID lineRecordId(DayHistory.Line line) {
        AttendanceRecord record = line.record();
        if ( record == null ) {
            return null;
        }
        return record.getId();
    }

    private Long lineRecordVersion(DayHistory.Line line) {
        AttendanceRecord record = line.record();
        if ( record == null ) {
            return null;
        }
        return record.getVersion();
    }
}
