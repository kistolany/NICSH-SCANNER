package kh.cmt.notifications.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.notifications.domain.Notification;
import kh.cmt.notifications.domain.PushToken;
import kh.cmt.notifications.infrastructure.NotificationRepository;
import kh.cmt.notifications.infrastructure.PushTokenRepository;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.shared.security.ScopeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells people something: one notification row each (active people only) and, once those rows are committed, a push
 * to each of their phones in the phone's language. A push that fails is not tried again (the notification stays in
 * the app's list); a token the phone no longer has is forgotten.
 */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final NotificationRepository notifications;
    private final PushTokenRepository tokens;
    private final PeopleQueries people;
    private final PushSender push;
    private final TransactionTemplate tx;

    Notifier(NotificationRepository notifications, PushTokenRepository tokens, PeopleQueries people, PushSender push,
            PlatformTransactionManager transactions) {
        this.notifications = notifications;
        this.tokens = tokens;
        this.people = people;
        this.push = push;
        this.tx = new TransactionTemplate(transactions);
        // runs after the handler's commit: Spring wants work started from afterCommit in a transaction of its own
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Must run in a transaction (the outbox handler's); the pushes go out after it commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Notification> notify(Collection<UUID> personIds, String kind, String code, Map<String, Object> params) {
        Set<UUID> active = people.activePeople().stream().map(PeopleQueries.PersonPlace::id).collect(Collectors.toSet());
        List<Notification> rows = new ArrayList<>();
        for (UUID person : new LinkedHashSet<>(personIds)) {
            if (active.contains(person)) {
                rows.add(new Notification(person, kind, code, params));
            }
        }
        if (rows.isEmpty()) {
            return rows;
        }
        rows = notifications.saveAll(rows);
        Map<UUID, Notification> byPerson = new HashMap<>();
        rows.forEach(n -> byPerson.put(n.getPersonId(), n));
        List<PushToken> phones = ScopeFilter.withoutScope(() -> tokens.findByPersonIdIn(byPerson.keySet()));
        List<PushSender.PushMessage> messages = phones.stream().map(t -> {
            Notification n = byPerson.get(t.getPersonId());
            NotificationTexts.Text text = NotificationTexts.render(code, params, t.getLocale());
            return new PushSender.PushMessage(t.getToken(), text.title(), text.body(),
                    Map.of("notificationId", n.getId().toString(), "kind", kind, "code", code));
        }).toList();
        if (!messages.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(messages);
                }
            });
        }
        return rows;
    }

    private void send(List<PushSender.PushMessage> messages) {
        int sent = 0;
        for (PushSender.PushMessage m : messages) {
            PushSender.Result result;
            try {
                result = push.send(m);
            } catch (RuntimeException e) {
                log.warn("Push failed: {}", e.toString());
                continue;
            }
            if (result == PushSender.Result.SENT) {
                sent++;
            } else if (result == PushSender.Result.TOKEN_GONE) {
                tx.executeWithoutResult(s -> ScopeFilter.withoutScope(() -> {
                    tokens.deleteByToken(m.token());
                    return null;
                }));
            }
        }
        if (sent > 0) {
            log.debug("Pushed {} of {} notifications", sent, messages.size());
        }
    }
}
