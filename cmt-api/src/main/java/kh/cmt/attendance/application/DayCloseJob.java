package kh.cmt.attendance.application;

import java.util.UUID;
import kh.cmt.attendance.infrastructure.DayCloseStore;
import kh.cmt.shared.security.SchoolOverride;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the end of the school day ({@link DayCloseService}) for every school: every quarter of an hour it closes the days
 * that are due (normally nothing; today once past 23:30; the missed days after a stop). One instance at a time
 * (ShedLock). Switched off with {@code cmt.attendance.day-close.enabled=false} (the tests close days themselves).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cmt.attendance.day-close.enabled", havingValue = "true", matchIfMissing = true)
class DayCloseJob {

    private final DayCloseService days;
    private final DayCloseStore closes;

    @Scheduled(cron = "${cmt.attendance.day-close.cron:0 */15 * * * *}", zone = "${cmt.timezone}")
    @SchedulerLock(name = "attendance-day-close", lockAtMostFor = "PT20M")
    void run() {
        for (UUID school : closes.schools()) {
            try {
                SchoolOverride.runAs(school, () -> days.due().forEach(day -> {
                    DayCloseService.Result r = days.close(day);
                    log.info("Closed {} for school {}: {} people, {} absent, {} missing check-outs", r.date(), school, r.people(), r.absent(), r.missing());
                }));
            } catch (RuntimeException e) {
                log.error("Closing the day failed for school {} (tried again in 15 minutes)", school, e);   // the next school still runs
            }
        }
    }
}
