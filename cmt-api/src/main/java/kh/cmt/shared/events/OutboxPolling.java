package kh.cmt.shared.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link OutboxRelay} every {@code cmt.outbox.poll-delay} (2 s). Switched off with
 * {@code cmt.outbox.polling=false} (the tests drain the outbox themselves).
 */
@Component
@ConditionalOnProperty(name = "cmt.outbox.polling", havingValue = "true", matchIfMissing = true)
class OutboxPolling {

    private final OutboxRelay relay;

    OutboxPolling(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${cmt.outbox.poll-delay:PT2S}", initialDelayString = "PT10S")
    void poll() {
        relay.drain();
    }
}
