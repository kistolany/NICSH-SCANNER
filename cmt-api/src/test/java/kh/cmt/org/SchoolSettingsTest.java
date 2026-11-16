package kh.cmt.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.util.UUID;
import kh.cmt.org.api.SchoolSettingsQueries;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Step 2.2 "done when": the Settings page saves and the app reads the grace minutes.
 * The tests share one database, so they run in a fixed order and each restores what it changed.
 */
@ApiTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchoolSettingsTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SchoolSettingsQueries settings;

    final Caller admin = TestJwt.webUser("super_admin");
    final Caller staff = TestJwt.appUser("staff");

    // ------------------------------------------------------------------ what the school starts with

    @Test
    @Order(1)
    void theSchoolStartsWithTheValuesFromTheDesign() throws Exception {
        JsonNode page = json.readTree(mvc.perform(get("/api/v1/admin/settings").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode school = page.get("school");
        assertThat(school.get("name").get("en").asText()).isEqualTo("CMT School");
        assertThat(school.get("name").get("km").asText()).isEqualTo("សាលា CMT");
        assertThat(school.get("address").asText()).isEqualTo("St. 271, Phnom Penh");
        assertThat(school.get("workingDays")).extracting(JsonNode::asInt).containsExactly(1, 2, 3, 4, 5, 6);   // Mon - Sat
        assertThat(school.get("timezone").asText()).isEqualTo("Asia/Phnom_Penh");
        assertThat(school.get("version").asLong()).isZero();

        JsonNode attendance = page.get("attendance");
        assertThat(attendance.get("defaultGraceMinutes").asInt()).isEqualTo(5);
        assertThat(attendance.get("earlyCheckOutNeedsReason").asBoolean()).isTrue();
        assertThat(attendance.get("manualCheckInAllowed").asBoolean()).isTrue();
        assertThat(attendance.get("offlineCheckInAllowed").asBoolean()).isTrue();
        assertThat(attendance.get("missingCheckOutRule").asText()).isEqualTo("ASK_CORRECTION_NEXT_DAY");

        assertThat(page.has("teacher")).isFalse();   // no teacher rules (teacher features removed)

        JsonNode notifications = page.get("notifications");
        assertThat(notifications.get("checkInReminderMin").asInt()).isEqualTo(10);
        assertThat(notifications.get("checkOutReminderMin").asInt()).isZero();                  // at shift end
        assertThat(notifications.has("teacherHourReminder")).isFalse();
        assertThat(notifications.get("shift2ReminderMin").asInt()).isEqualTo(10);

        JsonNode appearance = page.get("appearance");
        assertThat(appearance.get("defaultLanguage").asText()).isEqualTo("km");
        assertThat(appearance.get("defaultTheme").asText()).isEqualTo("system");
    }

    // ------------------------------------------------------------------ the app reads the grace minutes

    @Test
    @Order(2)
    void theAppReadsTheGraceMinutesAndSeesAChangeFromTheAdminWebRightAway() throws Exception {
        JsonNode before = publicSettings();
        assertThat(before.get("attendance").get("defaultGraceMinutes").asInt()).isEqualTo(5);
        assertThat(before.get("attendance").get("offlineCheckInAllowed").asBoolean()).isTrue();
        assertThat(before.get("school").get("name").get("en").asText()).isEqualTo("CMT School");

        // the admin saves the Attendance rules section with grace 10 and offline check-in off
        JsonNode saved = body(save("attendance", "{\"defaultGraceMinutes\":10,\"earlyCheckOutNeedsReason\":true,"
                + "\"manualCheckInAllowed\":false,\"offlineCheckInAllowed\":false,"
                + "\"missingCheckOutRule\":\"AUTO_CLOSE_AT_SHIFT_END\",\"version\":0}").andExpect(status().isOk()));
        assertThat(saved.get("version").asLong()).isEqualTo(1);

        // the phone asks again
        JsonNode after = publicSettings();
        assertThat(after.get("attendance").get("defaultGraceMinutes").asInt()).isEqualTo(10);
        assertThat(after.get("attendance").get("offlineCheckInAllowed").asBoolean()).isFalse();
        assertThat(after.get("attendance").get("manualCheckInAllowed").asBoolean()).isFalse();
        assertThat(after.get("attendance").get("missingCheckOutRule").asText()).isEqualTo("AUTO_CLOSE_AT_SHIFT_END");

        // other modules read the same value through the module API
        assertThat(settings.attendanceRules().defaultGraceMinutes()).isEqualTo(10);

        // put it back
        save("attendance", "{\"defaultGraceMinutes\":5,\"earlyCheckOutNeedsReason\":true,\"manualCheckInAllowed\":true,"
                + "\"offlineCheckInAllowed\":true,\"missingCheckOutRule\":\"ASK_CORRECTION_NEXT_DAY\",\"version\":1}")
                .andExpect(status().isOk());
    }

    @Test
    @Order(3)
    void thePublicEndpointIsForTheAppAndMayBeKeptForAMinute() throws Exception {
        mvc.perform(get("/api/v1/app/settings/public").header("Authorization", staff.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=60")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")));
        mvc.perform(get("/api/v1/app/settings/public")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/app/settings/public").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/settings").header("Authorization", staff.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/settings")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ saving each section

    @Test
    @Order(4)
    void everySectionSavesAndBumpsItsOwnVersionOnly() throws Exception {
        JsonNode school = body(save("school", "{\"name\":{\"en\":\"CMT International School\",\"km\":\"សាលា CMT\",\"ar\":\"مدرسة CMT\"},"
                + "\"address\":\"St. 271, Phnom Penh\",\"workingDays\":[5,1,2,3,4],\"timezone\":\"Asia/Bangkok\",\"version\":0}")
                .andExpect(status().isOk()));
        assertThat(school.get("name").get("ar").asText()).isEqualTo("مدرسة CMT");
        assertThat(school.get("workingDays")).extracting(JsonNode::asInt).containsExactly(1, 2, 3, 4, 5);   // sorted
        assertThat(school.get("version").asLong()).isEqualTo(1);
        assertThat(settings.zone()).isEqualTo(ZoneId.of("Asia/Bangkok"));

        JsonNode notifications = body(save("notifications", "{\"checkInReminder\":true,\"checkInReminderMin\":15,"
                + "\"checkOutReminder\":false,\"checkOutReminderMin\":0,"
                + "\"shift2Reminder\":false,\"shift2ReminderMin\":10,\"version\":0}").andExpect(status().isOk()));
        assertThat(notifications.get("checkInReminderMin").asInt()).isEqualTo(15);
        assertThat(notifications.get("checkOutReminder").asBoolean()).isFalse();

        JsonNode appearance = body(save("appearance", "{\"defaultLanguage\":\"en\",\"defaultTheme\":\"dark\",\"version\":0}")
                .andExpect(status().isOk()));
        assertThat(appearance.get("defaultLanguage").asText()).isEqualTo("en");

        // each section moved only its own version; the attendance section was not touched
        JsonNode page = json.readTree(mvc.perform(get("/api/v1/admin/settings").header("Authorization", admin.bearer()))
                .andReturn().getResponse().getContentAsString());
        assertThat(page.get("school").get("version").asLong()).isEqualTo(1);
        assertThat(page.get("notifications").get("version").asLong()).isEqualTo(1);
        assertThat(page.get("appearance").get("version").asLong()).isEqualTo(1);
        assertThat(page.get("attendance").get("version").asLong()).isEqualTo(2);   // changed and restored in test 2

        // and the app sees all of it
        JsonNode app = publicSettings();
        assertThat(app.get("school").get("timezone").asText()).isEqualTo("Asia/Bangkok");
        assertThat(app.has("teacher")).isFalse();
        assertThat(app.get("notifications").get("checkInReminderMin").asInt()).isEqualTo(15);
        assertThat(app.get("appearance").get("defaultTheme").asText()).isEqualTo("dark");

        // put the school back as it was
        save("school", "{\"name\":{\"en\":\"CMT School\",\"km\":\"សាលា CMT\"},\"address\":\"St. 271, Phnom Penh\","
                + "\"workingDays\":[1,2,3,4,5,6],\"timezone\":\"Asia/Phnom_Penh\",\"version\":1}").andExpect(status().isOk());
        save("notifications", "{\"checkInReminder\":true,\"checkInReminderMin\":10,\"checkOutReminder\":true,"
                + "\"checkOutReminderMin\":0,\"shift2Reminder\":true,"
                + "\"shift2ReminderMin\":10,\"version\":1}").andExpect(status().isOk());
        save("appearance", "{\"defaultLanguage\":\"km\",\"defaultTheme\":\"system\",\"version\":1}").andExpect(status().isOk());
    }

    @Test
    @Order(5)
    void aSectionSavedByAnotherAdminIsNotOverwritten() throws Exception {
        JsonNode current = body(get1("appearance"));
        long version = current.get("version").asLong();

        save("appearance", "{\"defaultLanguage\":\"en\",\"defaultTheme\":\"light\",\"version\":" + version + "}").andExpect(status().isOk());
        // a second admin still has the old version on screen
        save("appearance", "{\"defaultLanguage\":\"ar\",\"defaultTheme\":\"dark\",\"version\":" + version + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(body(get1("appearance")).get("defaultLanguage").asText()).isEqualTo("en");

        // no version at all
        save("appearance", "{\"defaultLanguage\":\"km\",\"defaultTheme\":\"system\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));

        save("appearance", "{\"defaultLanguage\":\"km\",\"defaultTheme\":\"system\",\"version\":" + (version + 1) + "}")
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ bad input

    @Test
    @Order(6)
    void badValuesAreRefusedWithTheFieldAndRule() throws Exception {
        long schoolVersion = body(get1("school")).get("version").asLong();
        long attendanceVersion = body(get1("attendance")).get("version").asLong();
        String okName = "\"name\":{\"en\":\"CMT School\"},\"timezone\":\"Asia/Phnom_Penh\"";

        // school
        save("school", "{" + okName.replace("CMT School", "  ") + ",\"workingDays\":[1],\"version\":" + schoolVersion + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name.en"));
        for (String days : new String[] {"[]", "[0,1]", "[1,8]", "[1,1,2]"}) {
            save("school", "{" + okName + ",\"workingDays\":" + days + ",\"version\":" + schoolVersion + "}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("workingDays"));
        }
        save("school", "{\"name\":{\"en\":\"CMT School\"},\"timezone\":\"Mars/Olympus\",\"workingDays\":[1],\"version\":" + schoolVersion + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("timezone"));
        save("school", "{" + okName + ",\"workingDays\":[1],\"logoFileId\":\"" + UUID.randomUUID() + "\",\"version\":" + schoolVersion + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("logoFileId"));

        // attendance
        String rest = "\"earlyCheckOutNeedsReason\":true,\"manualCheckInAllowed\":true,\"offlineCheckInAllowed\":true,"
                + "\"missingCheckOutRule\":\"FLAG_ONLY\",\"version\":" + attendanceVersion;
        save("attendance", "{\"defaultGraceMinutes\":121," + rest + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("defaultGraceMinutes")).andExpect(jsonPath("$.errors[0].rule").value("Max"));
        save("attendance", "{\"defaultGraceMinutes\":-1," + rest + "}").andExpect(status().isBadRequest());
        save("attendance", "{" + rest + "}").andExpect(status().isBadRequest())                 // grace missing
                .andExpect(jsonPath("$.errors[0].rule").value("NotNull"));
        save("attendance", "{\"defaultGraceMinutes\":5,\"earlyCheckOutNeedsReason\":true,\"manualCheckInAllowed\":true,"
                + "\"offlineCheckInAllowed\":true,\"missingCheckOutRule\":\"SOMETHING_ELSE\",\"version\":" + attendanceVersion + "}")
                .andExpect(status().isBadRequest());

        // notifications
        save("notifications", "{\"checkInReminder\":true,\"checkInReminderMin\":0,\"checkOutReminder\":true,\"checkOutReminderMin\":0,"
                + "\"shift2Reminder\":true,\"shift2ReminderMin\":10,\"version\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("checkInReminderMin"));

        // appearance
        save("appearance", "{\"defaultLanguage\":\"fr\",\"defaultTheme\":\"system\",\"version\":0}").andExpect(status().isBadRequest());

        // nothing above was saved
        assertThat(body(get1("school")).get("version").asLong()).isEqualTo(schoolVersion);
        assertThat(body(get1("attendance")).get("version").asLong()).isEqualTo(attendanceVersion);
    }

    @Test
    @Order(7)
    void aLogoCanBeSetToAnUploadedFileAndCleared() throws Exception {
        UUID file = UUID.randomUUID();
        jdbc.update("""
                insert into file (id, school_id, bucket, key, mime, size, purpose, status, created_at, updated_at)
                values (?, ?, 'cmt', ?, 'image/png', 1000, 'LOGO', 'READY', now(), now())
                """, file, TestJwt.SCHOOL, "logo-" + file);
        try {
            long v = body(get1("school")).get("version").asLong();
            JsonNode withLogo = body(save("school", "{\"name\":{\"en\":\"CMT School\",\"km\":\"សាលា CMT\"},"
                    + "\"address\":\"St. 271, Phnom Penh\",\"workingDays\":[1,2,3,4,5,6],\"timezone\":\"Asia/Phnom_Penh\","
                    + "\"logoFileId\":\"" + file + "\",\"version\":" + v + "}").andExpect(status().isOk()));
            assertThat(withLogo.get("logoFileId").asText()).isEqualTo(file.toString());
            assertThat(publicSettings().get("school").get("logoFileId").asText()).isEqualTo(file.toString());

            JsonNode cleared = body(save("school", "{\"name\":{\"en\":\"CMT School\",\"km\":\"សាលា CMT\"},"
                    + "\"address\":\"St. 271, Phnom Penh\",\"workingDays\":[1,2,3,4,5,6],\"timezone\":\"Asia/Phnom_Penh\","
                    + "\"version\":" + (v + 1) + "}").andExpect(status().isOk()));
            assertThat(cleared.has("logoFileId")).isFalse();
        } finally {
            jdbc.update("update school_settings set logo_file_id = null where school_id = ?", TestJwt.SCHOOL);
            jdbc.update("delete from file where id = ?", file);
        }
    }

    // ------------------------------------------------------------------ other schools

    @Test
    @Order(8)
    void anotherSchoolGetsItsOwnDefaultsAndNeverTouchesOurs() throws Exception {
        UUID otherSchool = UUID.randomUUID();
        jdbc.update("insert into school (id, code, name_en, created_at, updated_at) values (?, ?, 'Other School', now(), now())",
                otherSchool, "T" + otherSchool.toString().substring(0, 8));
        Caller other = TestJwt.webUser("super_admin", otherSchool);
        try {
            JsonNode theirs = json.readTree(mvc.perform(get("/api/v1/admin/settings/attendance").header("Authorization", other.bearer()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(theirs.get("defaultGraceMinutes").asInt()).isEqualTo(5);   // defaults, created on first use

            mvc.perform(put("/api/v1/admin/settings/attendance").header("Authorization", other.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"defaultGraceMinutes\":30,\"earlyCheckOutNeedsReason\":false,"
                            + "\"manualCheckInAllowed\":false,\"offlineCheckInAllowed\":false,\"missingCheckOutRule\":\"FLAG_ONLY\",\"version\":0}"))
                    .andExpect(status().isOk());

            assertThat(body(get1("attendance")).get("defaultGraceMinutes").asInt()).isEqualTo(5);   // ours is unchanged
            assertThat(jdbc.queryForObject("select count(*) from school_settings where school_id = ?", Integer.class, otherSchool)).isEqualTo(1);
        } finally {
            jdbc.update("delete from role where school_id = ?", otherSchool);
            jdbc.update("delete from school_settings where school_id = ?", otherSchool);
            jdbc.update("delete from school where id = ?", otherSchool);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions save(String section, String content) throws Exception {
        return mvc.perform(put("/api/v1/admin/settings/" + section).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private ResultActions get1(String section) throws Exception {
        return mvc.perform(get("/api/v1/admin/settings/" + section).header("Authorization", admin.bearer()));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private JsonNode publicSettings() throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/app/settings/public").header("Authorization", staff.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
