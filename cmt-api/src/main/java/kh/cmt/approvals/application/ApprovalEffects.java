package kh.cmt.approvals.application;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.approvals.api.ApprovalHandler;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.approvals.domain.Approval;
import kh.cmt.approvals.infrastructure.ApprovalRepository;
import kh.cmt.shared.security.SchoolOverride;
import kh.cmt.shared.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decisions whose {@link ApprovalInboxService#UNDO_WINDOW} is over take effect: the type's {@link ApprovalHandler} runs,
 * one decision per transaction, for its school. An Undo at the same moment wins (the version changed: nothing is applied).
 * When the handler refuses, the decision stays and the reason is kept ({@code effectError}) — it is not tried again.
 * Run by {@link ApprovalEffectsJob}; the tests call {@link #applyDue} themselves.
 */
@Slf4j
@Component
public class ApprovalEffects {

    static final int BATCH = 100;

    private final ApprovalRepository approvals;
    private final Map<Approvals.Type, ApprovalHandler> handlers = new EnumMap<>(Approvals.Type.class);
    private final TransactionTemplate tx;
    private final Clock clock;

    ApprovalEffects(ApprovalRepository approvals, List<ApprovalHandler> handlers, PlatformTransactionManager transactions, Clock clock) {
        this.approvals = approvals;
        handlers.forEach(h -> this.handlers.put(h.type(), h));
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** Applies every decision that is due; how many were applied. */
    public int applyDue() {
        int applied = 0;
        for (UUID id : approvals.due(clock.instant(), PageRequest.of(0, BATCH))) {
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> apply(id)))) {
                    applied++;
                }
            } catch (ObjectOptimisticLockingFailureException undoneMeanwhile) {
                log.debug("Approval {} changed while its effect ran (undone?)", id);
            } catch (RuntimeException e) {
                log.warn("The effect of approval {} could not be applied", id, e);
                tx.executeWithoutResult(s -> failed(id, e));
            }
        }
        return applied;
    }

    private boolean apply(UUID id) {
        Approval a = approvals.findById(id).orElse(null);
        if (a == null || !a.waiting() || a.getEffectiveAt().isAfter(clock.instant())) {
            return false;   // undone meanwhile
        }
        ApprovalHandler handler = handlers.get(a.getType());
        SchoolOverride.runAs(a.getSchoolId(), () -> handler.decided(new ApprovalHandler.Decided(a.getId(), a.getSubjectId(), a.getPersonId(),
                a.getStatus(), a.getDecidedBy(), a.getDecidedAt(), a.getComment(), a.getDetails() == null ? Map.of() : a.getDetails())));
        a.applied(clock.instant(), null);
        approvals.saveAndFlush(a);
        return true;
    }

    private void failed(UUID id, RuntimeException e) {
        approvals.findById(id).filter(Approval::waiting).ifPresent(a -> {
            a.applied(clock.instant(), e instanceof ApiException api ? api.code().name() + " " + api.properties() : e.getClass().getSimpleName());
            approvals.saveAndFlush(a);
        });
    }
}
