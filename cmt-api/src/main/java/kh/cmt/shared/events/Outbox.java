package kh.cmt.shared.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import kh.cmt.shared.security.SchoolContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional outbox: an event is written in the same transaction as the change it reports, so it exists exactly
 * when the change does, and is handed to its {@link OutboxHandler} afterwards by the {@link OutboxRelay}.
 */
@Component
public class Outbox {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final SchoolContext schools;
    private final Clock clock;

    Outbox(JdbcClient jdbc, ObjectMapper json, SchoolContext schools, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.schools = schools;
        this.clock = clock;
    }

    /** Adds an event of {@code type} for the current school; must run inside the transaction of the change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String type, Object payload) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox payload cannot be written as JSON: " + type, e);
        }
        jdbc.sql("""
                insert into outbox (id, school_id, aggregate, type, payload, created_at)
                values (:id, :school, :aggregate, :type, cast(:payload as jsonb), :at)""")
                .param("id", UUID.randomUUID())
                .param("school", schools.currentSchoolId())
                .param("aggregate", type.contains(".") ? type.substring(0, type.indexOf('.')) : type)
                .param("type", type)
                .param("payload", body)
                .param("at", OffsetDateTime.now(clock))
                .update();
    }
}
