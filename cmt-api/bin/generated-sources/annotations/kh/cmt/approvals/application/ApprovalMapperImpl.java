package kh.cmt.approvals.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.processing.Generated;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.approvals.domain.Approval;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-11-05T20:28:06+0700",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.46.200.v20261008-1115, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
class ApprovalMapperImpl implements ApprovalMapper {

    @Override
    public Approvals.View view(Approval approval) {
        if ( approval == null ) {
            return null;
        }

        UUID id = null;
        Approvals.Type type = null;
        String reason = null;
        Map<String, Object> details = null;
        Instant requestedAt = null;

        id = approval.getId();
        type = approval.getType();
        reason = approval.getReason();
        Map<String, Object> map = approval.getDetails();
        if ( map != null ) {
            details = new LinkedHashMap<String, Object>( map );
        }
        requestedAt = approval.getRequestedAt();

        Approvals.Status status = approval.shownStatus();
        Instant decidedAt = approval.waiting() ? null : approval.getDecidedAt();
        String comment = approval.waiting() ? null : approval.getComment();
        UUID decidedBy = approval.waiting() ? null : approval.getDecidedBy();

        Approvals.View view = new Approvals.View( id, type, status, reason, details, requestedAt, decidedAt, comment, decidedBy );

        return view;
    }
}
