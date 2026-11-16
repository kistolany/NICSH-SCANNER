package kh.cmt.attendance.application;

import java.util.List;
import kh.cmt.approvals.api.Approvals;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/** A shift of the history as the app shows it: the plan from the line, the times and minutes from its record (if any). */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface HistoryMapper {

    @Mapping(target = "shiftIdx", source = "line.shiftIdx")
    @Mapping(target = "name", source = "line.name")
    @Mapping(target = "start", source = "line.start")
    @Mapping(target = "end", source = "line.end")
    @Mapping(target = "status", source = "line.status")
    @Mapping(target = "checkIn", source = "line.record.inAt")
    @Mapping(target = "checkOut", source = "line.record.outAt")
    @Mapping(target = "workedMinutes", source = "line.record.workedMinutes")
    @Mapping(target = "lateMinutes", source = "line.record.lateMinutes")
    @Mapping(target = "earlyMinutes", source = "line.record.earlyMinutes")
    @Mapping(target = "recordId", source = "line.record.id")
    @Mapping(target = "version", source = "line.record.version")
    @Mapping(target = "inGate", source = "inGate")
    @Mapping(target = "outGate", source = "outGate")
    @Mapping(target = "lateReason", source = "lateReason")
    @Mapping(target = "earlyReason", source = "earlyReason")
    @Mapping(target = "lateExcused", expression = "java(line.record() != null && line.record().isLateExcused() ? Boolean.TRUE : null)")
    @Mapping(target = "earlyExcused", expression = "java(line.record() != null && line.record().isEarlyExcused() ? Boolean.TRUE : null)")
    @Mapping(target = "edited", source = "edited")
    @Mapping(target = "corrections", source = "corrections")
    HistoryViews.Shift shift(DayHistory.Line line, HistoryViews.Gate inGate, HistoryViews.Gate outGate, HistoryViews.LateReason lateReason,
            HistoryViews.EarlyReason earlyReason, HistoryViews.Edited edited, List<HistoryViews.Correction> corrections);

    /** A correction request as the day sheet shows it. */
    @Mapping(target = "field", expression = "java((String) view.details().get(\"field\"))")
    @Mapping(target = "time", expression = "java((String) view.details().get(\"requestedTime\"))")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "decidedAt", source = "decidedAt")
    @Mapping(target = "comment", source = "comment")
    HistoryViews.Correction correction(Approvals.View view);
}
