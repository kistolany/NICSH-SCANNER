package kh.cmt.approvals.application;

import kh.cmt.approvals.api.Approvals;
import kh.cmt.approvals.domain.Approval;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/** For the other modules: a decision still in its 10 s (it can be undone) shows as waiting. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface ApprovalMapper {

    @Mapping(target = "status", expression = "java(approval.shownStatus())")
    @Mapping(target = "decidedAt", expression = "java(approval.waiting() ? null : approval.getDecidedAt())")
    @Mapping(target = "comment", expression = "java(approval.waiting() ? null : approval.getComment())")
    @Mapping(target = "decidedBy", expression = "java(approval.waiting() ? null : approval.getDecidedBy())")
    Approvals.View view(Approval approval);
}
