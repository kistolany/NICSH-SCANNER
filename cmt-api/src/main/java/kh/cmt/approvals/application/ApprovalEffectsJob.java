package kh.cmt.approvals.application;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 2 seconds: the decisions whose Undo time is over take effect ({@link ApprovalEffects}). One instance at a time
 * (ShedLock). Switched off with {@code cmt.approvals.effects.enabled=false} (the tests apply them themselves).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cmt.approvals.effects.enabled", havingValue = "true", matchIfMissing = true)
class ApprovalEffectsJob {

    private final ApprovalEffects effects;

    @Scheduled(fixedDelayString = "${cmt.approvals.effects.delay:PT2S}")
    @SchedulerLock(name = "approval-effects", lockAtMostFor = "PT1M")
    void run() {
        effects.applyDue();
    }
}
