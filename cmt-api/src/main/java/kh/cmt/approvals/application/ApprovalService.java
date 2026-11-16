package kh.cmt.approvals.application;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.approvals.domain.Approval;
import kh.cmt.approvals.infrastructure.ApprovalRepository;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Sending requests (the inbox that decides them is phase 10). Runs in the caller's transaction. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ApprovalService implements Approvals {

    private final ApprovalRepository approvals;
    private final ApprovalMapper mapper;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Sent request(Request r) {
        Optional<Approval> latest = approvals.findFirstByTypeAndSubjectIdOrderByRequestedAtDesc(r.type(), r.subjectId());
        if (latest.isPresent() && latest.get().open()) {
            latest.get().change(r.reason(), r.details());
            return new Sent(mapper.view(approvals.saveAndFlush(latest.get())), false);
        }
        if (latest.isPresent() && latest.get().getStatus() != Status.CANCELLED) {
            throw new ApiException(ErrorCode.ALREADY_DECIDED).with("decision", latest.get().getStatus().name());
        }
        try {
            Approval created = approvals.saveAndFlush(Approval.pending(r, CurrentUser.require().userId(), clock.instant()));
            return new Sent(mapper.view(created), true);
        } catch (DataIntegrityViolationException sentTwiceAtOnce) {
            throw new ApiException(ErrorCode.CONFLICT);   // the same request at the same moment: the other one is open
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(Type type, UUID subjectId) {
        Optional<Approval> latest = approvals.findFirstByTypeAndSubjectIdOrderByRequestedAtDesc(type, subjectId);
        if (latest.isEmpty() || latest.get().getStatus() == Status.CANCELLED) {
            return;
        }
        if (!latest.get().open()) {
            throw new ApiException(ErrorCode.ALREADY_DECIDED).with("decision", latest.get().getStatus().name());   // also in its 10 s
        }
        latest.get().cancel(CurrentUser.require().userId(), clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<View> latest(Type type, UUID subjectId) {
        return approvals.findFirstByTypeAndSubjectIdOrderByRequestedAtDesc(type, subjectId).map(mapper::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, View> latest(Type type, Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        return approvals.findByTypeAndSubjectIdInOrderByRequestedAtDesc(type, subjectIds).stream()
                .collect(Collectors.toMap(Approval::getSubjectId, mapper::view, (newest, older) -> newest));
    }
}
