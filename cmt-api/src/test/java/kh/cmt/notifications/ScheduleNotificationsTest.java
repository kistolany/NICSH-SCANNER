package kh.cmt.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.UUID;
import kh.cmt.notifications.application.PushSender.PushMessage;
import kh.cmt.shared.events.OutboxRelay;
import kh.cmt.support.ApiTest;
import kh.cmt.support.RecordingPushSender;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Step 5.5 "done when the app receives the push": a schedule change reaches exactly the people whose day changes, as a
 * notification in the app's list and a push to their phone in the phone's language.
 */
@ApiTest
class ScheduleNotificationsTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;
    @Autowired OutboxRelay relay;
    @Autowired RecordingPushSender phones;

    private UUID school;
    private Caller admin;
    private LocalDate wednesday;
    private String split;
    private String exam;
    private String primary;
    private Person sokha;   // Primary, phone in Khmer
    private Person dara;    // Primary, phone in English
    private Person vanna;   // no department, phone in Arabic

    record Person(String id, Caller app, String token) {
    }

    @BeforeEach
    void aSchoolWithThreePhones() throws Exception {
        relay.drain();   // events of other tests first
        phones.sent.clear();
        phones.gone.clear();
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Notify school', now(), now())")
                .param("id", school).param("c", "N" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        LocalDate today = LocalDate.now(clock);
        wednesday = today.with(DayOfWeek.MONDAY).plusWeeks(1).plusDays(2);
        split = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Split day\",\"shifts\":[{\"name\":\"Morning\",\"start\":\"07:45\","
                + "\"end\":\"11:30\"},{\"name\":\"Afternoon\",\"start\":\"13:30\",\"end\":\"17:00\"}]}"));
        exam = id(send(post("/api/v1/admin/shift-templates"), "{\"name\":\"Exam\",\"shifts\":[{\"start\":\"07:00\",\"end\":\"12:00\"}]}"));
        primary = id(send(post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"Primary school\"}}"));
        sokha = person("Sokha", primary, "km");
        dara = person("Dara", primary, "en");
        vanna = person("Vanna", null, "ar");
        StringBuilder days = new StringBuilder();
        for (int wd = 1; wd <= 7; wd++) {
            days.append(wd > 1 ? "," : "").append("{\"weekday\":").append(wd).append(",\"templateId\":")
                    .append(wd <= 6 ? "\"" + split + "\"" : "null").append("}");
        }
        send(put("/api/v1/admin/schedules/weekly"), "{\"effectiveFrom\":\"" + today + "\",\"days\":[" + days + "]}").andExpect(status().isOk());
        relay.drain();   // everybody got a week: they are told (checked here), then the slate is cleaned
        assertThat(phones.sent).extracting(PushMessage::token).containsExactlyInAnyOrder(sokha.token(), dara.token(), vanna.token());
        phones.sent.clear();
        jdbc.sql("delete from notification where school_id = :s").param("s", school).update();
    }

    @Test
    void anExamDayReachesThePhonesOfTheDepartmentInTheirLanguage() throws Exception {
        examDayForPrimary(wednesday, "Exam day");
        relay.drain();

        String en = "Your schedule for " + english(wednesday) + " changed: Exam day 07:00 – 12:00.";
        assertThat(phones.to(dara.token())).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("Schedule changed");
            assertThat(m.body()).isEqualTo(en);
            assertThat(m.data()).containsEntry("kind", "SCHEDULE").containsEntry("code", "SCHEDULE_DAYS_CHANGED").containsKey("notificationId");
        });
        assertThat(phones.to(sokha.token())).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("កាលវិភាគបានផ្លាស់ប្តូរ");
            assertThat(m.body()).startsWith("កាលវិភាគរបស់អ្នកសម្រាប់ ").contains(wednesday.getDayOfMonth() + " ")
                    .endsWith("បានផ្លាស់ប្តូរ៖ Exam day 07:00 – 12:00។");
        });
        assertThat(phones.to(vanna.token())).isEmpty();   // not in Primary

        // the app's list: the same text, in the language the app asks for
        JsonNode list = body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", sokha.app().bearer())
                .header("Accept-Language", "en")).andExpect(status().isOk()));
        assertThat(list.get("unread").asInt()).isEqualTo(1);
        JsonNode item = list.get("items").get(0);
        assertThat(item.get("kind").asText()).isEqualTo("SCHEDULE");
        assertThat(item.get("code").asText()).isEqualTo("SCHEDULE_DAYS_CHANGED");
        assertThat(item.get("body").asText()).isEqualTo(en);
        assertThat(item.get("params").get("dates").get(0).asText()).isEqualTo(wednesday.toString());
        assertThat(item.get("read").asBoolean()).isFalse();
        assertThat(item.get("id").asText()).isEqualTo(phones.to(sokha.token()).get(0).data().get("notificationId"));

        // read one, then all
        mvc.perform(post("/api/v1/app/notifications/" + item.get("id").asText() + "/read").header("Authorization", sokha.app().bearer()))
                .andExpect(status().isOk());
        assertThat(body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", sokha.app().bearer()))).get("unread").asInt()).isZero();
        assertThat(body(mvc.perform(post("/api/v1/app/notifications/read-all").header("Authorization", dara.app().bearer()))).get("marked").asInt())
                .isEqualTo(1);
        // nobody opens another person's notification
        mvc.perform(post("/api/v1/app/notifications/" + item.get("id").asText() + "/read").header("Authorization", dara.app().bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void swipedAwayAndClearAllRemoveOnlyTheCallersOwn() throws Exception {
        examDayForPrimary(wednesday, "Exam day");
        examDayForPrimary(wednesday.plusDays(1), "Sports day");
        relay.drain();
        JsonNode mine = body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", sokha.app().bearer())));
        assertThat(mine.get("total").asInt()).isEqualTo(2);
        String first = mine.get("items").get(0).get("id").asText();

        // another person cannot remove it (nothing happens), the owner can, twice is fine
        mvc.perform(delete("/api/v1/app/notifications/" + first).header("Authorization", dara.app().bearer())).andExpect(status().isNoContent());
        assertThat(body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", sokha.app().bearer()))).get("total").asInt())
                .isEqualTo(2);
        mvc.perform(delete("/api/v1/app/notifications/" + first).header("Authorization", sokha.app().bearer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/app/notifications/" + first).header("Authorization", sokha.app().bearer())).andExpect(status().isNoContent());
        JsonNode left = body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", sokha.app().bearer())));
        assertThat(left.get("total").asInt()).isEqualTo(1);
        assertThat(left.get("items").get(0).get("id").asText()).isNotEqualTo(first);

        // Clear all: only the caller's list
        assertThat(body(mvc.perform(delete("/api/v1/app/notifications").header("Authorization", sokha.app().bearer()))).get("removed").asInt())
                .isEqualTo(1);
        assertThat(body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", sokha.app().bearer()))).get("total").asInt())
                .isZero();
        assertThat(body(mvc.perform(get("/api/v1/app/notifications").header("Authorization", dara.app().bearer()))).get("total").asInt())
                .isEqualTo(2);
    }

    @Test
    void onlyPeopleWhoseDayReallyChanges() throws Exception {
        // Sokha has their own change that day: the department's exam day does not reach them
        send(post("/api/v1/admin/schedules/date-changes"), "{\"dates\":[\"" + wednesday + "\"],\"target\":\"person\",\"targetId\":\"" + sokha.id()
                + "\",\"templateId\":null,\"label\":\"Training away\"}").andExpect(status().isCreated());
        relay.drain();
        assertThat(phones.to(sokha.token())).singleElement().satisfies(m ->
                assertThat(m.body()).contains("Training away (ថ្ងៃឈប់)"));
        phones.sent.clear();

        examDayForPrimary(wednesday, "Exam day");
        relay.drain();
        assertThat(phones.to(sokha.token())).isEmpty();
        assertThat(phones.to(dara.token())).hasSize(1);
        phones.sent.clear();

        // saving the same again tells nobody
        examDayForPrimary(wednesday, "Exam day");
        relay.drain();
        assertThat(phones.sent).isEmpty();

        // a holiday that day: nobody works, a date change on it tells nobody
        send(post("/api/v1/admin/holidays"), "{\"name\":{\"en\":\"Sports day\"},\"kind\":\"SCHOOL\",\"startDate\":\"" + wednesday.plusDays(1)
                + "\"}").andExpect(status().isCreated());
        relay.drain();
        assertThat(phones.sent).extracting(PushMessage::token).containsExactlyInAnyOrder(sokha.token(), dara.token(), vanna.token());
        assertThat(phones.to(dara.token()).get(0)).satisfies(m -> {
            assertThat(m.title()).isEqualTo("New holiday");
            assertThat(m.body()).isEqualTo("Sports day: " + english(wednesday.plusDays(1)) + ".");
        });
        assertThat(phones.to(vanna.token()).get(0).title()).isEqualTo("عطلة جديدة");
        phones.sent.clear();
        examDayForPrimary(wednesday.plusDays(1), "Exam day");
        relay.drain();
        assertThat(phones.sent).isEmpty();
    }

    @Test
    void removingAChangeAWeeklySaveAndNewShiftTimes() throws Exception {
        JsonNode saved = body(examDayForPrimary(wednesday, "Exam day"));
        relay.drain();
        phones.sent.clear();
        mvc.perform(delete("/api/v1/admin/schedules/date-changes/" + saved.get("changes").get(0).get("id").asText())
                .header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        relay.drain();
        assertThat(phones.sent).extracting(PushMessage::token).containsExactlyInAnyOrder(sokha.token(), dara.token());
        assertThat(phones.to(dara.token()).get(0).body()).isEqualTo("Your schedule for " + english(wednesday) + " changed. Open My schedule to see it.");
        phones.sent.clear();

        // Primary gets the same week as everyone: nothing changes for them, nobody is told
        LocalDate monday = wednesday.minusDays(2);
        String sameWeek = week(split, split, split, split, split, split, null);
        send(put("/api/v1/admin/schedules/weekly?target=department&id=" + primary), "{\"effectiveFrom\":\"" + monday + "\",\"days\":[" + sameWeek + "]}")
                .andExpect(status().isOk());
        relay.drain();
        assertThat(phones.sent).isEmpty();
        // Saturday off for Primary from that Monday
        send(put("/api/v1/admin/schedules/weekly?target=department&id=" + primary), "{\"effectiveFrom\":\"" + monday + "\",\"days\":["
                + week(split, split, split, split, split, null, null) + "]}").andExpect(status().isOk());
        relay.drain();
        assertThat(phones.sent).extracting(PushMessage::token).containsExactlyInAnyOrder(sokha.token(), dara.token());
        assertThat(phones.to(dara.token()).get(0)).satisfies(m -> {
            assertThat(m.title()).isEqualTo("Weekly schedule changed");
            assertThat(m.body()).isEqualTo("Your weekly schedule changes from " + english(monday) + ". Open My schedule to see it.");
        });
        phones.sent.clear();

        // the split day starts later: everybody who works it is told; a new colour alone tells nobody
        JsonNode template = body(mvc.perform(get("/api/v1/admin/shift-templates/" + split).header("Authorization", admin.bearer())));
        send(put("/api/v1/admin/shift-templates/" + split), "{\"name\":\"Split day\",\"color\":\"#16A34A\",\"shifts\":[{\"name\":\"Morning\","
                + "\"start\":\"07:45\",\"end\":\"11:30\"},{\"name\":\"Afternoon\",\"start\":\"13:30\",\"end\":\"17:00\"}],\"version\":"
                + template.get("version").asLong() + "}").andExpect(status().isOk());
        relay.drain();
        assertThat(phones.sent).isEmpty();
        template = body(mvc.perform(get("/api/v1/admin/shift-templates/" + split).header("Authorization", admin.bearer())));
        send(put("/api/v1/admin/shift-templates/" + split), "{\"name\":\"Split day\",\"shifts\":[{\"name\":\"Morning\",\"start\":\"08:00\","
                + "\"end\":\"11:30\"},{\"name\":\"Afternoon\",\"start\":\"13:30\",\"end\":\"17:00\"}],\"version\":" + template.get("version").asLong()
                + "}").andExpect(status().isOk());
        relay.drain();
        assertThat(phones.sent).extracting(PushMessage::token).containsExactlyInAnyOrder(sokha.token(), dara.token(), vanna.token());
        assertThat(phones.to(dara.token()).get(0).body()).isEqualTo("Split day is now 08:00 – 11:30, 13:30 – 17:00.");
    }

    @Test
    void pushTokens() throws Exception {
        // the phone moves to another account: the token follows it
        token(dara.app(), sokha.token(), "en").andExpect(status().isNoContent());
        examDayForPrimary(wednesday, "Exam day");
        relay.drain();
        assertThat(phones.to(sokha.token())).singleElement().satisfies(m -> assertThat(m.body()).startsWith("Your schedule for"));
        assertThat(jdbc.sql("select count(*) from push_token where person_id = :p").param("p", UUID.fromString(sokha.id())).query(Long.class).single())
                .isZero();

        // an app that was uninstalled: FCM says the token is gone, it is forgotten
        phones.gone.add(dara.token());
        examDayForPrimary(wednesday, "Mid-term");
        relay.drain();
        assertThat(jdbc.sql("select count(*) from push_token where token = :t").param("t", dara.token()).query(Long.class).single()).isZero();
        phones.gone.clear();

        // sign out forgets the token; checks; web users have no phone
        mvc.perform(delete("/api/v1/app/push-token").header("Authorization", vanna.app().bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + vanna.token() + "\"}")).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from push_token where token = :t").param("t", vanna.token()).query(Long.class).single()).isZero();
        JsonNode bad = body(mvc.perform(post("/api/v1/app/push-token").header("Authorization", vanna.app().bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\" \",\"platform\":\"windows\",\"locale\":\"fr\"}"))
                .andExpect(status().isBadRequest()));
        assertThat(bad.get("errors")).extracting(e -> e.get("field").asText() + ":" + e.get("rule").asText())
                .containsExactlyInAnyOrder("token:NotBlank", "platform:OneOf", "locale:OneOf");
        mvc.perform(post("/api/v1/app/push-token").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"x\",\"platform\":\"android\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/app/notifications").header("Authorization", TestJwt.appUser("staff").bearer())).andExpect(status().isNotFound());
    }

    @Test
    void aFailingEventIsTriedAgainLater() {
        UUID broken = UUID.randomUUID();
        // times from the API's clock, not the database's (the container's clock may run a little ahead)
        jdbc.sql("insert into outbox (id, school_id, aggregate, type, payload, created_at) values (:id, :s, 'x', 'x.unknown', '{}'::jsonb, :at)")
                .param("id", broken).param("s", school).param("at", java.time.OffsetDateTime.now(clock).minusSeconds(1)).update();
        relay.drain();
        var row = jdbc.sql("select attempts, published_at is null as open, next_attempt_at > :now as later, last_error from outbox where id = :id")
                .param("now", java.time.OffsetDateTime.now(clock)).param("id", broken).query().singleRow();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("open")).isEqualTo(true);
        assertThat(row.get("later")).isEqualTo(true);
        assertThat((String) row.get("last_error")).contains("No outbox handler for x.unknown");
        relay.drain();   // not due yet: left alone
        assertThat(jdbc.sql("select attempts from outbox where id = :id").param("id", broken).query(Integer.class).single()).isEqualTo(1);
        jdbc.sql("delete from outbox where id = :id").param("id", broken).update();
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions examDayForPrimary(LocalDate day, String label) throws Exception {
        return send(post("/api/v1/admin/schedules/date-changes"), "{\"dates\":[\"" + day + "\"],\"target\":\"department\",\"targetId\":\""
                + primary + "\",\"templateId\":\"" + exam + "\",\"label\":\"" + label + "\"}").andExpect(status().isCreated());
    }

    private Person person(String name, String department, String locale) throws Exception {
        String id = id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"" + name + " Lim\"},\"role\":\"staff\""
                + (department == null ? "" : ",\"departmentId\":\"" + department + "\"") + "}"));
        Caller app = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), UUID.fromString(id));
        String token = "fcm-" + name + "-" + UUID.randomUUID();
        token(app, token, locale).andExpect(status().isNoContent());
        return new Person(id, app, token);
    }

    private ResultActions token(Caller app, String token, String locale) throws Exception {
        return mvc.perform(post("/api/v1/app/push-token").header("Authorization", app.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"platform\":\"android\",\"locale\":\"" + locale + "\"}"));
    }

    private static String week(String... templates) {
        StringBuilder days = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            days.append(i > 0 ? "," : "").append("{\"weekday\":").append(i + 1).append(",\"templateId\":")
                    .append(templates[i] == null ? "null" : "\"" + templates[i] + "\"").append("}");
        }
        return days.toString();
    }

    private static String english(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth() + " "
                + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
