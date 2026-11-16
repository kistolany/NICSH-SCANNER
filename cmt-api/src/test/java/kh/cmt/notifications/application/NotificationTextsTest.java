package kh.cmt.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The texts in km / en / ar: dates like the app writes them, Western digits, lists cut after three dates. */
class NotificationTextsTest {

    static final LocalDate FRI = LocalDate.of(2026, 10, 16);

    @Test
    void datesAsTheAppWritesThem() {
        assertThat(NotificationTexts.date("en", FRI)).isEqualTo("Fri 16 Oct");
        assertThat(NotificationTexts.date("km", FRI)).isEqualTo("សុក្រ 16 តុលា");
        assertThat(NotificationTexts.date("ar", FRI)).isEqualTo("الجمعة 16 أكتوبر");
        List<LocalDate> five = List.of(FRI, FRI.plusDays(3), FRI.plusDays(4), FRI.plusDays(5), FRI.plusDays(6));
        assertThat(NotificationTexts.dates("en", five)).isEqualTo("Fri 16 Oct, Mon 19 Oct, Tue 20 Oct and 2 more days");
        assertThat(NotificationTexts.dates("en", five.subList(0, 4))).endsWith("and 1 more day");
        assertThat(NotificationTexts.dates("ar", five.subList(0, 2))).isEqualTo("الجمعة 16 أكتوبر، الإثنين 19 أكتوبر");
    }

    @Test
    void leaveDecisions_withTheTypesNameInTheReadersLanguage() {
        Map<String, Object> name = Map.of("en", "Sick leave", "km", "ច្បាប់ឈឺ");
        Map<String, Object> half = Map.of("name", name, "start", FRI.toString(), "end", FRI.toString(), "days", "0.5", "half", "PM");
        assertThat(NotificationTexts.render(NotificationTexts.LEAVE_APPROVED, half, "en"))
                .isEqualTo(new NotificationTexts.Text("Leave request approved", "Sick leave, Fri 16 Oct (half a day (afternoon))."));
        assertThat(NotificationTexts.render(NotificationTexts.LEAVE_APPROVED, half, "km").body()).isEqualTo("ច្បាប់ឈឺ, សុក្រ 16 តុលា (កន្លះថ្ងៃ (ពេលរសៀល))។");
        Map<String, Object> one = Map.of("name", name, "start", FRI.toString(), "end", FRI.toString(), "days", "1", "comment", "Too late");
        assertThat(NotificationTexts.render(NotificationTexts.LEAVE_REJECTED, one, "en").body()).isEqualTo("Sick leave, Fri 16 Oct (1 day): Too late");
        assertThat(NotificationTexts.render(NotificationTexts.LEAVE_REJECTED, one, "ar"))
                .isEqualTo(new NotificationTexts.Text("تم رفض طلب الإجازة", "Sick leave، الجمعة 16 أكتوبر (يوم واحد): Too late"));
    }

    @Test
    void aMissingCheckOutInEachLanguage() {
        Map<String, Object> p = Map.of("date", FRI.toString(), "times", List.of("07:45 – 17:00"));
        assertThat(NotificationTexts.render(NotificationTexts.MISSING_CHECK_OUT, p, "en"))
                .isEqualTo(new NotificationTexts.Text("You forgot to check out", "You did not check out on Fri 16 Oct (07:45 – 17:00). Request a correction in the app."));
        assertThat(NotificationTexts.render(NotificationTexts.MISSING_CHECK_OUT, p, "km").body())
                .isEqualTo("អ្នកមិនបានចុះវត្តមានចេញនៅ សុក្រ 16 តុលា (07:45 – 17:00) ទេ។ សូមស្នើសុំកែតម្រូវនៅក្នុងកម្មវិធី។");
        assertThat(NotificationTexts.render(NotificationTexts.MISSING_CHECK_OUT, p, "ar").title()).isEqualTo("نسيت تسجيل الانصراف");
    }

    @Test
    void aDateChangeInEachLanguage() {
        Map<String, Object> p = Map.of("dates", List.of("2026-10-16"), "label", "Exam day", "template", "Exam", "times", List.of("07:00 – 12:00"),
                "off", false);
        assertThat(NotificationTexts.render(NotificationTexts.SCHEDULE_DAYS_CHANGED, p, "en"))
                .isEqualTo(new NotificationTexts.Text("Schedule changed", "Your schedule for Fri 16 Oct changed: Exam day 07:00 – 12:00."));
        assertThat(NotificationTexts.render(NotificationTexts.SCHEDULE_DAYS_CHANGED, p, "km").body())
                .isEqualTo("កាលវិភាគរបស់អ្នកសម្រាប់ សុក្រ 16 តុលា បានផ្លាស់ប្តូរ៖ Exam day 07:00 – 12:00។");
        assertThat(NotificationTexts.render(NotificationTexts.SCHEDULE_DAYS_CHANGED, p, "ar").body())
                .isEqualTo("تغيّر جدولك في الجمعة 16 أكتوبر: Exam day 07:00 – 12:00.");
        // no label: the template's name; a day off; an unknown language falls back to Khmer
        Map<String, Object> noLabel = Map.of("dates", List.of("2026-10-16"), "template", "Full day", "times", List.of("07:45 – 17:00"));
        assertThat(NotificationTexts.render(NotificationTexts.SCHEDULE_DAYS_CHANGED, noLabel, "en").body()).endsWith("changed: Full day 07:45 – 17:00.");
        Map<String, Object> off = Map.of("dates", List.of("2026-10-16"), "off", true);
        assertThat(NotificationTexts.render(NotificationTexts.SCHEDULE_DAYS_CHANGED, off, "en").body()).endsWith("changed: day off.");
        assertThat(NotificationTexts.render(NotificationTexts.SCHEDULE_DAYS_CHANGED, off, "fr").title()).isEqualTo("កាលវិភាគបានផ្លាស់ប្តូរ");
    }

    @Test
    void holidaysUseTheNameInTheReadersLanguage() {
        Map<String, Object> p = Map.of("name", Map.of("km", "បុណ្យអុំទូក", "en", "Water Festival"), "start", "2026-11-23", "end", "2026-11-25");
        assertThat(NotificationTexts.render(NotificationTexts.HOLIDAY_ADDED, p, "en").body())
                .isEqualTo("Water Festival: Mon 23 Nov – Wed 25 Nov.");
        assertThat(NotificationTexts.render(NotificationTexts.HOLIDAY_ADDED, p, "km").body())
                .isEqualTo("បុណ្យអុំទូក៖ ច័ន្ទ 23 វិច្ឆិកា – ពុធ 25 វិច្ឆិកា។");
        assertThat(NotificationTexts.render(NotificationTexts.HOLIDAY_REMOVED, p, "ar").body()).startsWith("لم يعد Water Festival");   // no Arabic name
        assertThat(NotificationTexts.render(NotificationTexts.HOLIDAY_CHANGED, Map.of("name", Map.of("en", "Sports day"), "start", "2026-10-30",
                "end", "2026-10-30"), "en").body()).isEqualTo("Sports day is now Fri 30 Oct.");
        assertThat(NotificationTexts.render("SOMETHING_NEW", Map.of(), "en").title()).isEqualTo("SOMETHING_NEW");
    }
}
