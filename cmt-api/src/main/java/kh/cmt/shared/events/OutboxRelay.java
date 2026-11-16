package kh.cmt.shared.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.shared.security.SchoolOverride;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hands outbox events to their handlers, oldest first. Each event runs in its own transaction with the row locked
 * ({@code FOR UPDATE SKIP LOCKED}), so several app instances can relay at once without handling an event twice. A
 * handler that fails rolls back; the event is tried again after a growing pause (5 s, 10 s, 20 s… at most 1 h), and
 * after {@value #MAX_ATTEMPTS} tries it is left with its last error for somebody to look at. Called every few seconds
 * by {@link OutboxPolling}; tests call {@link #drain()} directly.
 */
@Component
public class OutboxRelay {

    static final int MAX_ATTEMPTS = 10;
    private static final int MAX_PER_RUN = 200;
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<String, OutboxHandler<?>> handlers = new HashMap<>();

    OutboxRelay(JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transactions, Clock clock,
            List<OutboxHandler<?>> handlers) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        handlers.forEach(h -> {
            if (this.handlers.put(h.type(), h) != null) {
                throw new IllegalStateException("Two outbox handlers for " + h.type());
            }
        });
    }

    private record Row(UUID id, UUID schoolId, String type, String payload, int attempts) {
    }

    /** What happened to one event: {@code row} null = nothing was due; {@code error} null = handled. */
    private record Outcome(Row row, RuntimeException error) {
    }

    /** Handles every event that is due now; returns how many were handled. */
    public int drain() {
        int handled = 0;
        for (int i = 0; i < MAX_PER_RUN; i++) {
            Outcome outcome = tx.execute(status -> {
                Optional<Row> row = next();
                if (row.isEmpty()) {
                    return new Outcome(null, null);
                }
                try {
                    handle(row.get());
                    markDone(row.get());
                    return new Outcome(row.get(), null);
                } catch (RuntimeException e) {
                    status.setRollbackOnly();   // the handler's work is undone; the failure is noted below
                    return new Outcome(row.get(), e);
                }
            });
            if (outcome == null || outcome.row() == null) {
                break;
            }
            if (outcome.error() != null) {
                tx.executeWithoutResult(status -> markFailed(outcome.row(), outcome.error()));
            } else {
                handled++;
            }
        }
        return handled;
    }

    private Optional<Row> next() {
        return jdbc.sql("""
                select id, school_id, type, payload::text as payload, attempts from outbox
                where published_at is null and attempts < :max and coalesce(next_attempt_at, created_at) <= :now
                order by coalesce(next_attempt_at, created_at)
                limit 1
                for update skip locked""")
                .param("max", MAX_ATTEMPTS)
                .param("now", OffsetDateTime.now(clock))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("school_id", UUID.class),
                        rs.getString("type"), rs.getString("payload"), rs.getInt("attempts")))
                .optional();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handle(Row row) {
        OutboxHandler handler = handlers.get(row.type());
        if (handler == null) {
            throw new IllegalStateException("No outbox handler for " + row.type());
        }
        Object payload;
        try {
            payload = json.readValue(row.payload(), handler.payloadType());
        } catch (Exception e) {
            throw new IllegalStateException("Outbox payload of " + row.type() + " cannot be read", e);
        }
        SchoolOverride.runAs(row.schoolId(), () -> handler.handle(payload));
    }

    private void markDone(Row row) {
        jdbc.sql("update outbox set published_at = :now, attempts = attempts + 1, last_error = null where id = :id")
                .param("now", OffsetDateTime.now(clock)).param("id", row.id()).update();
    }

    private void markFailed(Row row, RuntimeException error) {
        int attempts = row.attempts() + 1;
        Duration pause = Duration.ofSeconds(5L << Math.min(attempts - 1, 10));
        if (pause.compareTo(Duration.ofHours(1)) > 0) {
            pause = Duration.ofHours(1);
        }
        String message = error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage();
        log.warn("Outbox event {} ({}) failed, attempt {} of {}: {}", row.id(), row.type(), attempts, MAX_ATTEMPTS, message, error);
        jdbc.sql("update outbox set attempts = :attempts, next_attempt_at = :next, last_error = :error where id = :id")
                .param("attempts", attempts)
                .param("next", OffsetDateTime.now(clock).plus(pause))
                .param("error", message.length() > 2000 ? message.substring(0, 2000) : message)
                .param("id", row.id())
                .update();
    }
}
