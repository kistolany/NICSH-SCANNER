package kh.cmt.attendance.application;

import java.time.Instant;
import kh.cmt.attendance.api.AttendanceRecorded;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.domain.ScanEvent;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.Mapping;

/** A record as the scan answers it, and as the outbox event that reports it. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface ScanMapper {

    @Mapping(target = "recordId", source = "r.id")
    @Mapping(target = "shiftIdx", source = "r.shiftIdx")
    @Mapping(target = "shiftStart", source = "r.plannedStart")
    @Mapping(target = "shiftEnd", source = "r.plannedEnd")
    @Mapping(target = "result", source = "result")
    @Mapping(target = "time", source = "time")
    @Mapping(target = "twoShifts", source = "twoShifts")
    @Mapping(target = "workedTodayMinutes", source = "workedTodayMinutes")
    @Mapping(target = "nextShift", source = "nextShift")
    @Mapping(target = "gate", source = "gate")
    ScanViews.Result result(AttendanceRecord r, String result, Instant time, boolean twoShifts, int workedTodayMinutes,
            ScanViews.NextShift nextShift, ScanViews.Gate gate);

    @Mapping(target = "recordId", source = "r.id")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "at", source = "at")
    AttendanceRecorded recorded(AttendanceRecord r, ScanEvent.Kind kind, Instant at);

    /** A record an admin changed or entered ({@code kind} EDITED | MANUAL). */
    @Mapping(target = "recordId", source = "r.id")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "at", source = "at")
    AttendanceRecorded changed(AttendanceRecord r, String kind, Instant at);
}
