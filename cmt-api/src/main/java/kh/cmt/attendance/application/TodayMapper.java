package kh.cmt.attendance.application;

import kh.cmt.attendance.api.TodayCard;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/** A shift of today as the history shows it, with where it stands, as the Today card shows it. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface TodayMapper {

    @Mapping(target = "phase", source = "phase")
    TodayCard.Shift shift(HistoryViews.Shift shift, TodayCard.Phase phase);

    TodayCard.Gate gate(HistoryViews.Gate gate);
}
