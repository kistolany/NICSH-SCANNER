package kh.cmt.leave.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import kh.cmt.leave.domain.LeaveType;
import kh.cmt.shared.i18n.LocalizedName;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-11-05T20:28:08+0700",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.46.200.v20261008-1115, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
class LeaveMapperImpl implements LeaveMapper {

    @Override
    public LeaveViews.Type type(LeaveType type) {
        if ( type == null ) {
            return null;
        }

        UUID id = null;
        String code = null;
        LocalizedName name = null;
        BigDecimal daysPerYear = null;
        boolean halfDays = false;
        String attachment = null;
        String color = null;
        boolean inactive = false;
        Instant deletedAt = null;
        String deleteReason = null;
        long version = 0L;

        id = type.getId();
        code = type.getCode();
        name = name( type.getName() );
        daysPerYear = type.getDaysPerYear();
        halfDays = type.isHalfDays();
        if ( type.getAttachment() != null ) {
            attachment = type.getAttachment().name();
        }
        color = type.getColor();
        inactive = type.isInactive();
        deletedAt = type.getDeletedAt();
        deleteReason = type.getDeleteReason();
        version = type.getVersion();

        LeaveViews.Type type1 = new LeaveViews.Type( id, code, name, daysPerYear, halfDays, attachment, color, inactive, deletedAt, deleteReason, version );

        return type1;
    }
}
