package kh.cmt.notifications.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The text of a notification in km / en / ar, made from its code and values when it is shown or pushed. Dates as the
 * app writes them ("Fri 17 Oct", "សុក្រ 17 តុលា", "الجمعة 17 أكتوبر"): fixed name tables and Western digits in every
 * language, the same as the app's {@code AppNames}.
 */
public final class NotificationTexts {

    public record Text(String title, String body) {
    }

    public static final String SCHEDULE_DAYS_CHANGED = "SCHEDULE_DAYS_CHANGED";
    public static final String SCHEDULE_WEEK_CHANGED = "SCHEDULE_WEEK_CHANGED";
    public static final String SHIFT_TIMES_CHANGED = "SHIFT_TIMES_CHANGED";
    public static final String HOLIDAY_ADDED = "HOLIDAY_ADDED";
    public static final String HOLIDAY_CHANGED = "HOLIDAY_CHANGED";
    public static final String HOLIDAY_REMOVED = "HOLIDAY_REMOVED";
    public static final String MISSING_CHECK_OUT = "MISSING_CHECK_OUT";
    public static final String LEAVE_APPROVED = "LEAVE_APPROVED";
    public static final String LEAVE_REJECTED = "LEAVE_REJECTED";
    public static final String LATE_REASON_APPROVED = "LATE_REASON_APPROVED";
    public static final String LATE_REASON_REJECTED = "LATE_REASON_REJECTED";
    public static final String EARLY_REASON_APPROVED = "EARLY_REASON_APPROVED";
    public static final String EARLY_REASON_REJECTED = "EARLY_REASON_REJECTED";
    public static final String CORRECTION_APPROVED = "CORRECTION_APPROVED";
    public static final String CORRECTION_REJECTED = "CORRECTION_REJECTED";

    /** Dates listed by name before "and N more". */
    static final int DATES_SHOWN = 3;

    private NotificationTexts() {
    }

    /** {@code lang} km | en | ar (anything else: km, the school's language). */
    public static Text render(String code, Map<String, Object> p, String lang) {
        String l = switch (lang == null ? "" : lang) {
            case "en", "ar" -> lang;
            default -> "km";
        };
        return switch (code) {
            case SCHEDULE_DAYS_CHANGED -> days(p, l);
            case SCHEDULE_WEEK_CHANGED -> new Text(
                    pick(l, "កាលវិភាគប្រចាំសប្តាហ៍បានផ្លាស់ប្តូរ", "Weekly schedule changed", "تغيّر الجدول الأسبوعي"),
                    fill(pick(l, "កាលវិភាគប្រចាំសប្តាហ៍របស់អ្នកផ្លាស់ប្តូរចាប់ពី {0}។ សូមបើក «កាលវិភាគរបស់ខ្ញុំ» ដើម្បីមើល។",
                            "Your weekly schedule changes from {0}. Open My schedule to see it.",
                            "يتغيّر جدولك الأسبوعي ابتداءً من {0}. افتح «جدولي» للاطلاع عليه."),
                            date(l, LocalDate.parse(str(p, "from")))));
            case SHIFT_TIMES_CHANGED -> new Text(
                    pick(l, "ម៉ោងវេនបានផ្លាស់ប្តូរ", "Shift times changed", "تغيّرت أوقات المناوبة"),
                    fill(pick(l, "{0} ឥឡូវនេះគឺ {1}។", "{0} is now {1}.", "أصبحت أوقات {0}: {1}."),
                            str(p, "template"), String.join(", ", list(p, "times"))));
            case HOLIDAY_ADDED -> new Text(
                    pick(l, "ថ្ងៃឈប់សម្រាកថ្មី", "New holiday", "عطلة جديدة"),
                    fill(pick(l, "{0}៖ {1}។", "{0}: {1}.", "{0}: {1}."), holidayName(p, l), range(p, l)));
            case HOLIDAY_CHANGED -> new Text(
                    pick(l, "ថ្ងៃឈប់សម្រាកបានផ្លាស់ប្តូរ", "Holiday changed", "تغيّرت العطلة"),
                    fill(pick(l, "{0} ឥឡូវនេះគឺ {1}។", "{0} is now {1}.", "{0} أصبحت {1}."), holidayName(p, l), range(p, l)));
            case HOLIDAY_REMOVED -> new Text(
                    pick(l, "បានដកថ្ងៃឈប់សម្រាក", "Holiday removed", "أُلغيت العطلة"),
                    fill(pick(l, "{0} ({1}) លែងជាថ្ងៃឈប់សម្រាកទៀតហើយ។", "{0} ({1}) is no longer a holiday.", "لم يعد {0} ({1}) عطلة."),
                            holidayName(p, l), range(p, l)));
            case MISSING_CHECK_OUT -> new Text(
                    pick(l, "អ្នកភ្លេចចុះវត្តមានចេញ", "You forgot to check out", "نسيت تسجيل الانصراف"),
                    fill(pick(l, "អ្នកមិនបានចុះវត្តមានចេញនៅ {0} ({1}) ទេ។ សូមស្នើសុំកែតម្រូវនៅក្នុងកម្មវិធី។",
                            "You did not check out on {0} ({1}). Request a correction in the app.",
                            "لم تسجّل الانصراف في {0} ({1}). اطلب تصحيحًا من التطبيق."),
                            date(l, LocalDate.parse(str(p, "date"))), String.join(", ", list(p, "times"))));
            case LEAVE_APPROVED -> new Text(
                    pick(l, "សំណើសុំច្បាប់ត្រូវបានអនុម័ត", "Leave request approved", "تمت الموافقة على طلب الإجازة"),
                    fill(pick(l, "{0}, {1} ({2})។", "{0}, {1} ({2}).", "{0}، {1} ({2})."), holidayName(p, l), range(p, l), leaveDays(p, l)));
            case LEAVE_REJECTED -> new Text(
                    pick(l, "សំណើសុំច្បាប់ត្រូវបានបដិសេធ", "Leave request rejected", "تم رفض طلب الإجازة"),
                    fill(pick(l, "{0}, {1} ({2})៖ {3}", "{0}, {1} ({2}): {3}", "{0}، {1} ({2}): {3}"), holidayName(p, l), range(p, l),
                            leaveDays(p, l), str(p, "comment")));
            case LATE_REASON_APPROVED -> new Text(
                    pick(l, "មូលហេតុមកយឺតត្រូវបានអនុម័ត", "Late reason approved", "تمت الموافقة على سبب التأخر"),
                    fill(pick(l, "{0}៖ ការមកយឺត {1} នាទី ត្រូវបានអនុញ្ញាត ហើយមិនរាប់ទេ។", "{0}: being late {1} min is excused and does not count.",
                            "{0}: تم قبول عذر التأخر {1} دقيقة ولن يُحتسب."), day(p, l), str(p, "minutes")));
            case LATE_REASON_REJECTED -> new Text(
                    pick(l, "មូលហេតុមកយឺតត្រូវបានបដិសេធ", "Late reason rejected", "تم رفض سبب التأخر"),
                    fill(pick(l, "{0}៖ ការមកយឺត {1} នាទី នៅតែរាប់។ {2}", "{0}: being late {1} min still counts. {2}",
                            "{0}: التأخر {1} دقيقة لا يزال محتسبًا. {2}"), day(p, l), str(p, "minutes"), str(p, "comment")).trim());
            case EARLY_REASON_APPROVED -> new Text(
                    pick(l, "មូលហេតុចេញមុនម៉ោងត្រូវបានអនុម័ត", "Early leave reason approved", "تمت الموافقة على سبب الانصراف المبكر"),
                    fill(pick(l, "{0}៖ ការចេញមុនម៉ោង {1} នាទី ត្រូវបានអនុញ្ញាត ហើយមិនរាប់ទេ។", "{0}: leaving {1} min early is excused and does not count.",
                            "{0}: تم قبول عذر الانصراف المبكر {1} دقيقة ولن يُحتسب."), day(p, l), str(p, "minutes")));
            case EARLY_REASON_REJECTED -> new Text(
                    pick(l, "មូលហេតុចេញមុនម៉ោងត្រូវបានបដិសេធ", "Early leave reason rejected", "تم رفض سبب الانصراف المبكر"),
                    fill(pick(l, "{0}៖ ការចេញមុនម៉ោង {1} នាទី នៅតែរាប់។ {2}", "{0}: leaving {1} min early still counts. {2}",
                            "{0}: الانصراف المبكر {1} دقيقة لا يزال محتسبًا. {2}"), day(p, l), str(p, "minutes"), str(p, "comment")).trim());
            case CORRECTION_APPROVED -> new Text(
                    pick(l, "ការកែតម្រូវត្រូវបានអនុម័ត", "Correction approved", "تمت الموافقة على التصحيح"),
                    fill(pick(l, "{0}៖ {1} ឥឡូវនេះគឺ {2}។", "{0}: your {1} is now {2}.", "{0}: أصبح {1} {2}."), day(p, l), field(p, l),
                            str(p, "time")));
            case CORRECTION_REJECTED -> new Text(
                    pick(l, "ការកែតម្រូវត្រូវបានបដិសេធ", "Correction rejected", "تم رفض التصحيح"),
                    fill(pick(l, "{0}, {1} {2}៖ {3}", "{0}, {1} {2}: {3}", "{0}، {1} {2}: {3}"), day(p, l), field(p, l), str(p, "time"),
                            str(p, "comment")));
            default -> new Text(code, "");
        };
    }

    /** "Fri 17 Oct", with the shift's name when it has one: "Fri 17 Oct (Morning)". */
    private static String day(Map<String, Object> p, String l) {
        String d = date(l, LocalDate.parse(str(p, "date")));
        String shift = str(p, "shift");
        return shift == null ? d : d + " (" + shift + ")";
    }

    /** check-in / check-out. */
    private static String field(Map<String, Object> p, String l) {
        boolean in = "CHECK_IN".equals(str(p, "field"));
        return in ? pick(l, "ម៉ោងចូល", "check-in", "وقت الحضور") : pick(l, "ម៉ោងចេញ", "check-out", "وقت الانصراف");
    }

    /** "3 days", "1 day", "half a day (morning)". */
    private static String leaveDays(Map<String, Object> p, String l) {
        String half = str(p, "half");
        if (half != null) {
            boolean am = half.equals("AM");
            return pick(l, am ? "កន្លះថ្ងៃ (ពេលព្រឹក)" : "កន្លះថ្ងៃ (ពេលរសៀល)", am ? "half a day (morning)" : "half a day (afternoon)",
                    am ? "نصف يوم (صباحًا)" : "نصف يوم (مساءً)");
        }
        String n = str(p, "days");
        boolean one = "1".equals(n);
        return fill(pick(l, "{0} ថ្ងៃ", one ? "1 day" : "{0} days", one ? "يوم واحد" : "{0} أيام"), n);
    }

    /** "Your schedule for Fri 17 Oct changed: Exam day 07:00 – 12:00." */
    private static Text days(Map<String, Object> p, String l) {
        String title = pick(l, "កាលវិភាគបានផ្លាស់ប្តូរ", "Schedule changed", "تغيّر الجدول");
        String dates = dates(l, list(p, "dates").stream().map(LocalDate::parse).toList());
        if (Boolean.TRUE.equals(p.get("removed"))) {
            return new Text(title, fill(pick(l, "កាលវិភាគរបស់អ្នកសម្រាប់ {0} បានផ្លាស់ប្តូរ។ សូមបើក «កាលវិភាគរបស់ខ្ញុំ» ដើម្បីមើល។",
                    "Your schedule for {0} changed. Open My schedule to see it.",
                    "تغيّر جدولك في {0}. افتح «جدولي» للاطلاع عليه."), dates));
        }
        String what;
        if (Boolean.TRUE.equals(p.get("off"))) {
            what = pick(l, "ថ្ងៃឈប់", "day off", "يوم راحة");
            String label = str(p, "label");
            if (label != null) {
                what = label + " (" + what + ")";
            }
        } else {
            String name = str(p, "label") != null ? str(p, "label") : str(p, "template");
            String times = String.join(", ", list(p, "times"));
            what = name == null ? times : times.isEmpty() ? name : name + " " + times;
        }
        return new Text(title, fill(pick(l, "កាលវិភាគរបស់អ្នកសម្រាប់ {0} បានផ្លាស់ប្តូរ៖ {1}។",
                "Your schedule for {0} changed: {1}.", "تغيّر جدولك في {0}: {1}."), dates, what));
    }

    // ------------------------------------------------------------------ dates and names

    static String date(String l, LocalDate d) {
        String weekday = WEEKDAYS.get(l).get(d.getDayOfWeek().getValue() - 1);
        String month = MONTHS.get(l).get(d.getMonthValue() - 1);
        if (l.equals("en")) {
            weekday = weekday.substring(0, 3);
            month = month.substring(0, 3);
        }
        return weekday + " " + d.getDayOfMonth() + " " + month;
    }

    /** Up to {@value #DATES_SHOWN} dates by name, then "and N more". */
    static String dates(String l, List<LocalDate> all) {
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(all.size(), DATES_SHOWN); i++) {
            shown.add(date(l, all.get(i)));
        }
        String text = String.join(l.equals("ar") ? "، " : ", ", shown);
        int more = all.size() - shown.size();
        if (more > 0) {
            text += fill(pick(l, " និង {0} ថ្ងៃទៀត", more == 1 ? " and 1 more day" : " and {0} more days", " و{0} أيام أخرى"),
                    String.valueOf(more));
        }
        return text;
    }

    private static String range(Map<String, Object> p, String l) {
        LocalDate start = LocalDate.parse(str(p, "start"));
        LocalDate end = LocalDate.parse(str(p, "end"));
        return start.equals(end) ? date(l, start) : date(l, start) + " – " + date(l, end);
    }

    /** The holiday's (or leave type's) name in the language, else English, else Khmer, else Arabic. */
    @SuppressWarnings("unchecked")
    private static String holidayName(Map<String, Object> p, String l) {
        Object value = p.get("name");
        if (!(value instanceof Map<?, ?> name)) {
            return "";
        }
        for (String key : List.of(l, "en", "km", "ar")) {
            Object v = ((Map<String, Object>) name).get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    private static final Map<String, List<String>> WEEKDAYS = Map.of(
            "km", List.of("ច័ន្ទ", "អង្គារ", "ពុធ", "ព្រហស្បតិ៍", "សុក្រ", "សៅរ៍", "អាទិត្យ"),
            "ar", List.of("الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد"),
            "en", List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"));

    private static final Map<String, List<String>> MONTHS = Map.of(
            "km", List.of("មករា", "កុម្ភៈ", "មីនា", "មេសា", "ឧសភា", "មិថុនា", "កក្កដា", "សីហា", "កញ្ញា", "តុលា", "វិច្ឆិកា", "ធ្នូ"),
            "ar", List.of("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"),
            "en", List.of("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November",
                    "December"));

    // ------------------------------------------------------------------ helpers

    private static String pick(String l, String km, String en, String ar) {
        return switch (l) {
            case "en" -> en;
            case "ar" -> ar;
            default -> km;
        };
    }

    private static String fill(String pattern, String... values) {
        String out = pattern;
        for (int i = 0; i < values.length; i++) {
            out = out.replace("{" + i + "}", values[i] == null ? "" : values[i]);
        }
        return out;
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    private static List<String> list(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
