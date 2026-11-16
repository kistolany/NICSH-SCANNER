package kh.cmt.notifications.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kh.cmt.notifications.api.MyNotifications;
import kh.cmt.notifications.api.NotificationFeed;
import kh.cmt.notifications.domain.Notification;
import kh.cmt.notifications.domain.PushToken;
import kh.cmt.notifications.infrastructure.NotificationRepository;
import kh.cmt.notifications.infrastructure.PushTokenRepository;
import kh.cmt.org.api.SchoolSettingsQueries;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.ScopeFilter;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in app user's notifications and phones: the list (text in their language: the app's
 * {@code Accept-Language}, else the language of their login, else the school's), read / read all, and the push token.
 * Another person's notification is not found.
 */
@Service
@Transactional
public class MyNotificationService implements NotificationFeed {

    static final int MAX_SIZE = 100;
    private static final Set<String> LANGUAGES = Set.of("km", "en", "ar");
    private static final Set<String> PLATFORMS = Set.of("android", "ios");

    private final NotificationRepository notifications;
    private final PushTokenRepository tokens;
    private final PeopleQueries people;
    private final SchoolSettingsQueries settings;
    private final Clock clock;

    MyNotificationService(NotificationRepository notifications, PushTokenRepository tokens, PeopleQueries people,
            SchoolSettingsQueries settings, Clock clock) {
        this.notifications = notifications;
        this.tokens = tokens;
        this.people = people;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MyNotifications list(int page, int size, String acceptLanguage) {
        UUID me = me();
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw validation("size", "Range");
        }
        String lang = language(acceptLanguage);
        var found = notifications.findByPersonIdOrderByCreatedAtDescIdDesc(me, PageRequest.of(page, size));
        List<MyNotifications.Item> items = found.getContent().stream().map(n -> item(n, lang)).toList();
        return new MyNotifications(items, page, size, found.getTotalElements(), notifications.countByPersonIdAndReadAtIsNull(me));
    }

    @Override
    @Transactional(readOnly = true)
    public MyNotifications latest(int count, String acceptLanguage) {
        return list(0, count, acceptLanguage);
    }

    public MyNotifications.Item read(UUID id, String acceptLanguage) {
        UUID me = me();
        Notification n = notifications.findById(id).filter(x -> x.getPersonId().equals(me))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        n.markRead(clock.instant());
        return item(n, language(acceptLanguage));
    }

    /** Marks every notification read; answers how many were not read before. */
    public int readAll() {
        return notifications.markAllRead(me(), clock.instant());
    }

    /** Removes one of the caller's notifications for good (swiped away); nothing happens if it is already gone. */
    public void remove(UUID id) {
        notifications.deleteMine(me(), id);
    }

    /** Removes every notification of the caller ("Clear all"); answers how many there were. */
    public int removeAll() {
        return notifications.deleteAllMine(me());
    }

    /**
     * Registers this phone's push token for the signed-in person (again after a new token, a new language or another
     * account on the phone: the token is taken over).
     */
    public void registerToken(MyNotifications.PushTokenCommand c) {
        CurrentUser user = CurrentUser.require();
        UUID me = me();
        List<Map<String, String>> errors = new ArrayList<>();
        String token = c.token() == null ? "" : c.token().trim();
        if (token.isEmpty()) {
            errors.add(error("token", "NotBlank"));
        } else if (token.length() > 512) {
            errors.add(error("token", "Size"));
        }
        String platform = c.platform() == null ? "" : c.platform().trim().toLowerCase(Locale.ROOT);
        if (!PLATFORMS.contains(platform)) {
            errors.add(error("platform", "OneOf"));
        }
        String locale = c.locale() == null ? null : c.locale().trim().toLowerCase(Locale.ROOT);
        if (locale != null && !LANGUAGES.contains(locale)) {
            errors.add(error("locale", "OneOf"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
        }
        String lang = locale != null ? locale : language(null);
        PushToken row = ScopeFilter.withoutScope(() -> tokens.findByToken(token)).orElseGet(() -> new PushToken(token));
        row.assign(me, user.userId(), platform, lang, user.deviceId());
        try {
            tokens.saveAndFlush(row);
        } catch (DataIntegrityViolationException parallel) {
            throw new ApiException(ErrorCode.CONFLICT);   // the same token registered twice at the same moment
        }
    }

    /** Forgets this phone's token (sign out): no more pushes to it. Unknown tokens are ignored. */
    public void removeToken(String token) {
        UUID me = me();
        if (token != null) {
            tokens.findByToken(token.trim()).filter(t -> t.getPersonId().equals(me)).ifPresent(tokens::delete);
        }
    }

    // ------------------------------------------------------------------ helpers

    private MyNotifications.Item item(Notification n, String lang) {
        NotificationTexts.Text text = NotificationTexts.render(n.getCode(), n.getParams(), lang);
        return new MyNotifications.Item(n.getId(), n.getKind(), n.getCode(), n.getParams(), text.title(), text.body(),
                n.getCreatedAt(), n.getReadAt() != null, n.getReadAt());
    }

    /** The first of km / en / ar in Accept-Language, else the login's language, else the school's default. */
    private String language(String acceptLanguage) {
        if (acceptLanguage != null) {
            for (String part : acceptLanguage.split(",")) {
                String code = part.split(";")[0].trim().toLowerCase(Locale.ROOT);
                code = code.length() >= 2 ? code.substring(0, 2) : code;
                if (LANGUAGES.contains(code)) {
                    return code;
                }
            }
        }
        String login = CurrentUser.current().map(CurrentUser::locale).orElse(null);
        if (login != null && LANGUAGES.contains(login)) {
            return login;
        }
        return settings.appearance().defaultLanguage().code();
    }

    private UUID me() {
        CurrentUser user = CurrentUser.require();
        if (user.personId() == null || !people.isActive(user.personId())) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return user.personId();
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static ApiException validation(String field, String rule) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(error(field, rule)));
    }
}
