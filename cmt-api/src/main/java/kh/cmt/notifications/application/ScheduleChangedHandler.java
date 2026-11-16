package kh.cmt.notifications.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import kh.cmt.schedule.api.ScheduleChanged;
import kh.cmt.shared.events.OutboxHandler;
import kh.cmt.shared.i18n.LocalizedName;
import org.springframework.stereotype.Component;

/**
 * Step 5.5: a schedule change → a notification of kind {@value #KIND} for each person it reaches ("Your schedule for Fri
 * 17 Oct changed…"), pushed to their phones.
 */
@Component
class ScheduleChangedHandler implements OutboxHandler<ScheduleChanged> {

    static final String KIND = "SCHEDULE";

    private final Notifier notifier;

    ScheduleChangedHandler(Notifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public String type() {
        return ScheduleChanged.TYPE;
    }

    @Override
    public Class<ScheduleChanged> payloadType() {
        return ScheduleChanged.class;
    }

    @Override
    public void handle(ScheduleChanged e) {
        Map<String, Object> p = new LinkedHashMap<>();
        String code = switch (e.kind()) {
            case DAYS -> {
                p.put("dates", e.dates().stream().map(LocalDate::toString).toList());
                if (e.removed()) {
                    p.put("removed", true);
                } else {
                    putIfSet(p, "label", e.label());
                    putIfSet(p, "template", e.templateName());
                    p.put("times", e.times());
                    p.put("off", e.off());
                }
                yield NotificationTexts.SCHEDULE_DAYS_CHANGED;
            }
            case WEEK -> {
                p.put("from", e.from().toString());
                yield NotificationTexts.SCHEDULE_WEEK_CHANGED;
            }
            case SHIFT_TIMES -> {
                p.put("template", e.templateName());
                p.put("times", e.times());
                yield NotificationTexts.SHIFT_TIMES_CHANGED;
            }
            case HOLIDAY_ADDED, HOLIDAY_CHANGED, HOLIDAY_REMOVED -> {
                p.put("name", name(e.holidayName()));
                p.put("start", e.holidayStart().toString());
                p.put("end", e.holidayEnd().toString());
                yield switch (e.kind()) {
                    case HOLIDAY_ADDED -> NotificationTexts.HOLIDAY_ADDED;
                    case HOLIDAY_CHANGED -> NotificationTexts.HOLIDAY_CHANGED;
                    default -> NotificationTexts.HOLIDAY_REMOVED;
                };
            }
        };
        notifier.notify(e.personIds(), KIND, code, p);
    }

    private static Map<String, String> name(LocalizedName n) {
        Map<String, String> out = new LinkedHashMap<>();
        if (n != null) {
            putIfSet(out, "km", n.km());
            putIfSet(out, "en", n.en());
            putIfSet(out, "ar", n.ar());
        }
        return out;
    }

    private static void putIfSet(Map<String, ? super String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
