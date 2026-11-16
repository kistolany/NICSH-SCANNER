package kh.cmt.leave.application;

import kh.cmt.leave.domain.LeaveType;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.i18n.LocalizedText;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/** Leave rows as the leave pages answer them. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface LeaveMapper {

    LeaveViews.Type type(LeaveType type);

    default LocalizedName name(LocalizedText t) {
        return t == null ? null : new LocalizedName(t.getKm(), t.getEn(), t.getAr());
    }
}
