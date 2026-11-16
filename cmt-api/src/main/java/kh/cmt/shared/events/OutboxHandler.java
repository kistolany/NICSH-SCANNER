package kh.cmt.shared.events;

/**
 * Handles the outbox events of one {@link #type()} (e.g. {@code schedule.changed}). Runs after the change that wrote the
 * event was committed, in its own transaction, as the event's school ({@code SchoolContext}) and with no caller (so
 * record scope = whole school). Throwing rolls the handler's work back and the event is tried again later, so a
 * handler must be safe to run again for the same event.
 */
public interface OutboxHandler<T> {

    String type();

    Class<T> payloadType();

    void handle(T payload);
}
