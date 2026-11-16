package kh.cmt.attendance.application;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import javax.annotation.processing.Generated;
import kh.cmt.attendance.api.TodayCard;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-11-05T20:28:07+0700",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.46.200.v20261008-1115, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
class TodayMapperImpl implements TodayMapper {

    @Override
    public TodayCard.Shift shift(HistoryViews.Shift shift, TodayCard.Phase phase) {
        if ( shift == null && phase == null ) {
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
        TodayCard.LateReason lateReason = null;
        TodayCard.EarlyReason earlyReason = null;
        Boolean lateExcused = null;
        Boolean earlyExcused = null;
        UUID recordId = null;
        Long version = null;
        if ( shift != null ) {
            shiftIdx = shift.shiftIdx();
            name = shift.name();
            start = shift.start();
            end = shift.end();
            status = shift.status();
            checkIn = shift.checkIn();
            checkOut = shift.checkOut();
            workedMinutes = shift.workedMinutes();
            lateMinutes = shift.lateMinutes();
            earlyMinutes = shift.earlyMinutes();
            lateReason = lateReasonToLateReason( shift.lateReason() );
            earlyReason = earlyReasonToEarlyReason( shift.earlyReason() );
            lateExcused = shift.lateExcused();
            earlyExcused = shift.earlyExcused();
            recordId = shift.recordId();
            version = shift.version();
        }
        TodayCard.Phase phase1 = null;
        phase1 = phase;

        TodayCard.Shift shift1 = new TodayCard.Shift( shiftIdx, name, start, end, phase1, status, checkIn, checkOut, workedMinutes, lateMinutes, earlyMinutes, lateReason, earlyReason, lateExcused, earlyExcused, recordId, version );

        return shift1;
    }

    @Override
    public TodayCard.Gate gate(HistoryViews.Gate gate) {
        if ( gate == null ) {
            return null;
        }

        UUID id = null;
        String name = null;

        id = gate.id();
        name = gate.name();

        TodayCard.Gate gate1 = new TodayCard.Gate( id, name );

        return gate1;
    }

    protected TodayCard.LateReason lateReasonToLateReason(HistoryViews.LateReason lateReason) {
        if ( lateReason == null ) {
            return null;
        }

        String text = null;
        String status = null;

        text = lateReason.text();
        status = lateReason.status();

        TodayCard.LateReason lateReason1 = new TodayCard.LateReason( text, status );

        return lateReason1;
    }

    protected TodayCard.EarlyReason earlyReasonToEarlyReason(HistoryViews.EarlyReason earlyReason) {
        if ( earlyReason == null ) {
            return null;
        }

        String code = null;
        String note = null;
        String status = null;

        code = earlyReason.code();
        note = earlyReason.note();
        status = earlyReason.status();

        TodayCard.EarlyReason earlyReason1 = new TodayCard.EarlyReason( code, note, status );

        return earlyReason1;
    }
}
