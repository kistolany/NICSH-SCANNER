package kh.cmt.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kh.cmt.org.api.StaffIdGenerator;
import kh.cmt.org.api.StaffRole;
import kh.cmt.org.infrastructure.StaffIdSequenceStore;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Step 2.3: automatic Staff IDs. "Done when": two parallel creations never get the same ID. */
@ApiTest
class NumberingTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StaffIdGenerator generator;
    @Autowired StaffIdSequenceStore sequences;
    @Autowired PlatformTransactionManager transactionManager;

    final Caller admin = TestJwt.webUser("super_admin");

    /** Every test works with its own prefix, so the counters of the tests cannot get mixed up. */
    String prefix = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

    @AfterEach
    void restoreDefaults() throws Exception {
        configure("CMT", "-", 4, false, false);
    }

    // ------------------------------------------------------------------ the setting and its preview

    @Test
    void theNumberingSectionStartsWithTheDesignValues() throws Exception {
        configure("CMT", "-", 4, false, false);
        JsonNode numbering = body(get("/api/v1/admin/settings/numbering"));
        assertThat(numbering.get("prefix").asText()).isEqualTo("CMT");
        assertThat(numbering.get("separator").asText()).isEqualTo("-");
        assertThat(numbering.get("length").asInt()).isEqualTo(4);
        assertThat(numbering.get("rolePrefix").asBoolean()).isFalse();
        assertThat(numbering.get("includeYear").asBoolean()).isFalse();
        assertThat(numbering.get("nextNumber").asLong()).isGreaterThanOrEqualTo(1);

        // it is also part of the whole Settings page
        assertThat(body(get("/api/v1/admin/settings")).get("numbering").get("prefix").asText()).isEqualTo("CMT");
    }

    @Test
    void savingChangesTheFormatAndNormalisesThePrefix() throws Exception {
        JsonNode saved = configure(prefix.toLowerCase(), "_", 6, true, true);
        assertThat(saved.get("prefix").asText()).isEqualTo(prefix);                  // capitals
        assertThat(saved.get("separator").asText()).isEqualTo("_");
        assertThat(saved.get("length").asInt()).isEqualTo(6);
        assertThat(saved.get("rolePrefix").asBoolean()).isTrue();
        assertThat(saved.get("includeYear").asBoolean()).isTrue();
        assertThat(saved.get("nextNumber").asLong()).isEqualTo(1);                    // a new prefix starts at 1
        assertThat(saved.get("version").asLong()).isGreaterThan(0);

        JsonNode preview = body(get("/api/v1/admin/settings/numbering/preview"));
        assertThat(preview.get("staff").asText()).isEqualTo(prefix + "_STF_" + yy() + "_000001");
        assertThat(preview.get("teacher").asText()).isEqualTo(prefix + "_TCH_" + yy() + "_000001");
        assertThat(preview.get("admin").asText()).isEqualTo(prefix + "_ADM_" + yy() + "_000001");
    }

    @Test
    void badFormatsAreRefusedWithTheFieldAndRule() throws Exception {
        long v = body(get("/api/v1/admin/settings/numbering")).get("version").asLong();
        String rest = ",\"rolePrefix\":false,\"includeYear\":false,\"version\":" + v + "}";
        for (String bad : new String[] {"\"ABC-1\"", "\"\"", "\"ABCDEFGHIJK\"", "\"A B\"", "\"កម្ពុជា\""}) {
            putSection("numbering", "{\"prefix\":" + bad + ",\"separator\":\"-\",\"length\":4" + rest)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("prefix"));
        }
        for (String bad : new String[] {"\"ab\"", "\"----\"", "\" \"", "\"#\""}) {
            putSection("numbering", "{\"prefix\":\"CMT\",\"separator\":" + bad + ",\"length\":4" + rest)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("separator"));
        }
        for (int len : new int[] {2, 9, 0, -1}) {
            putSection("numbering", "{\"prefix\":\"CMT\",\"separator\":\"-\",\"length\":" + len + rest)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("length"));
        }
        putSection("numbering", "{\"prefix\":\"CMT\",\"separator\":\"-\",\"length\":4,\"version\":" + v + "}")   // switches missing
                .andExpect(status().isBadRequest());
        assertThat(body(get("/api/v1/admin/settings/numbering")).get("version").asLong()).isEqualTo(v);   // nothing saved
    }

    @Test
    void aStaleVersionIsReportedNotOverwritten() throws Exception {
        long v = body(get("/api/v1/admin/settings/numbering")).get("version").asLong();
        putSection("numbering", "{\"prefix\":\"AAA\",\"separator\":\"-\",\"length\":4,\"rolePrefix\":false,\"includeYear\":false,\"version\":" + v + "}")
                .andExpect(status().isOk());
        putSection("numbering", "{\"prefix\":\"BBB\",\"separator\":\"-\",\"length\":4,\"rolePrefix\":false,\"includeYear\":false,\"version\":" + v + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(body(get("/api/v1/admin/settings/numbering")).get("prefix").asText()).isEqualTo("AAA");
    }

    @Test
    void thePreviewCanShowUnsavedEditsAndNeverUsesANumberUp() throws Exception {
        configure(prefix, "-", 4, false, false);

        JsonNode saved = body(get("/api/v1/admin/settings/numbering/preview"));
        assertThat(saved.get("staff").asText()).isEqualTo(prefix + "-0001");

        // the page is editing: role prefix on, separator changed (nothing saved)
        JsonNode edited = body(get("/api/v1/admin/settings/numbering/preview").param("rolePrefix", "true").param("separator", "/"));
        assertThat(edited.get("teacher").asText()).isEqualTo(prefix + "/TCH/0001");
        assertThat(body(get("/api/v1/admin/settings/numbering")).get("rolePrefix").asBoolean()).isFalse();

        // asking many times uses nothing up
        for (int i = 0; i < 5; i++) {
            body(get("/api/v1/admin/settings/numbering/preview"));
        }
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "-0001");

        // and a preview of a bad edit is refused
        mvc.perform(get("/api/v1/admin/settings/numbering/preview").param("prefix", "no way")
                .header("Authorization", admin.bearer())).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ handing out IDs

    @Test
    void idsGoUpByOneFromOneAndFollowTheFormat() throws Exception {
        configure(prefix, "-", 4, true, false);
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "-STF-0001");
        assertThat(inTransaction(() -> generator.next(StaffRole.TEACHER))).isEqualTo(prefix + "-TCH-0002");   // one counter for all roles
        assertThat(inTransaction(() -> generator.next(StaffRole.ADMIN))).isEqualTo(prefix + "-ADM-0003");
        assertThat(body(get("/api/v1/admin/settings/numbering")).get("nextNumber").asLong()).isEqualTo(4);
    }

    @Test
    void idsAlreadyTakenAreSkippedAndTheirNumbersAreNotOfferedAgain() throws Exception {
        configure(prefix, "-", 4, false, false);
        Set<String> taken = Set.of(prefix + "-0001", prefix + "-0002", prefix + "-0004");   // typed in by hand or imported

        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF, taken::contains))).isEqualTo(prefix + "-0003");
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF, taken::contains))).isEqualTo(prefix + "-0005");
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF, taken::contains))).isEqualTo(prefix + "-0006");
    }

    @Test
    void parallelCreationsNeverReceiveTheSameId() throws Exception {
        configure(prefix, "-", 4, false, false);
        int people = 30;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(15);
        List<Future<String>> ids = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            ids.add(pool.submit(() -> tx.execute(status -> {
                String id = generator.next(StaffRole.STAFF);
                pause(40);   // the "person" is still being saved: the next creation has to wait for the number lock
                return id;
            })));
        }
        List<String> all = new ArrayList<>();
        for (Future<String> id : ids) {
            all.add(id.get());
        }
        pool.shutdown();

        assertThat(all).doesNotHaveDuplicates().hasSize(people);
        Set<String> expected = new TreeSet<>();
        for (int n = 1; n <= people; n++) {
            expected.add(prefix + "-" + String.format("%04d", n));
        }
        assertThat(new TreeSet<>(all)).isEqualTo(expected);   // exactly 1..30: no gaps either
    }

    @Test
    void theGeneratorOnlyRunsInsideTheTransactionThatSavesThePerson() {
        assertThatThrownBy(() -> generator.next(StaffRole.STAFF)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void ifThePersonCannotBeSavedTheNumberIsNotLost() throws Exception {
        configure(prefix, "-", 4, false, false);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(generator.next(StaffRole.STAFF)).isEqualTo(prefix + "-0001");
            throw new IllegalStateException("saving the person failed");
        })).isInstanceOf(IllegalStateException.class);

        // nobody has ID 0001, so the next person gets it (an ID only counts once a person has it)
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "-0001");
    }

    // ------------------------------------------------------------------ counters

    @Test
    void changingSeparatorLengthOrRoleKeepsTheCounterButAnotherPrefixHasItsOwn() throws Exception {
        configure(prefix, "-", 4, false, false);
        inTransaction(() -> generator.next(StaffRole.STAFF));
        inTransaction(() -> generator.next(StaffRole.STAFF));
        inTransaction(() -> generator.next(StaffRole.STAFF));

        configure(prefix, "/", 6, true, false);   // looks different, same counter
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "/STF/000004");

        String other = "O" + prefix.substring(1);
        configure(other, "-", 4, false, false);   // another prefix starts at 1
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(other + "-0001");

        configure(prefix, "-", 4, false, false);  // back to the first prefix: it carries on, nothing comes back
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "-0005");
    }

    @Test
    void withAYearPartEachYearHasItsOwnCounter() throws Exception {
        configure(prefix, "-", 4, false, true);
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "-" + yy() + "-0001");
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "-" + yy() + "-0002");

        UUID school = TestJwt.SCHOOL;
        int year = LocalDate.now(ZoneId.of("Asia/Phnom_Penh")).getYear();
        assertThat(jdbc.queryForObject("select last_value from staff_id_sequence where school_id = ? and prefix = ? and year = ?",
                Long.class, school, prefix, year)).isEqualTo(2);

        // next year the number starts again (its own counter row)
        assertThat(inTransaction(() -> sequences.take(school, prefix, year + 1))).isEqualTo(1);
        // without a year part the counter is the year-0 one
        configure(prefix, "-", 4, false, false);
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "-0001");
    }

    @Test
    void theNextNumberCanBeMovedForwardAfterAnImportButNeverBack() throws Exception {
        configure(prefix, "-", 4, false, false);
        inTransaction(() -> generator.next(StaffRole.STAFF));   // 0001
        inTransaction(() -> generator.next(StaffRole.STAFF));   // 0002

        post1("/api/v1/admin/settings/numbering/advance", "{\"nextNumber\":151}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextNumber").value(151));
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "-0151");

        post1("/api/v1/admin/settings/numbering/advance", "{\"nextNumber\":100}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("nextNumber"))
                .andExpect(jsonPath("$.errors[0].rule").value("NotBackwards"));
        post1("/api/v1/admin/settings/numbering/advance", "{\"nextNumber\":152}").andExpect(status().isOk());   // same as now: fine
        post1("/api/v1/admin/settings/numbering/advance", "{\"nextNumber\":0}").andExpect(status().isBadRequest());
        post1("/api/v1/admin/settings/numbering/advance", "{}").andExpect(status().isBadRequest());
        assertThat(inTransaction(() -> generator.next(StaffRole.STAFF))).isEqualTo(prefix + "-0152");
    }

    @Test
    void everySchoolHasItsOwnCounters() throws Exception {
        configure("CMT", "-", 4, false, false);
        long ours = body(get("/api/v1/admin/settings/numbering")).get("nextNumber").asLong();
        inTransaction(() -> generator.next(StaffRole.STAFF));

        UUID other = UUID.randomUUID();
        jdbc.update("insert into school (id, code, name_en, created_at, updated_at) values (?, ?, 'Other', now(), now())",
                other, "T" + other.toString().substring(0, 8));
        try {
            Caller theirAdmin = TestJwt.webUser("super_admin", other);
            JsonNode theirs = json.readTree(mvc.perform(get("/api/v1/admin/settings/numbering/preview")
                    .header("Authorization", theirAdmin.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(theirs.get("staff").asText()).isEqualTo("CMT-0001");   // their own start, whatever we have used
            assertThat(body(get("/api/v1/admin/settings/numbering")).get("nextNumber").asLong()).isEqualTo(ours + 1);
        } finally {
            jdbc.update("delete from staff_id_sequence where school_id = ?", other);
            jdbc.update("delete from role where school_id = ?", other);
            jdbc.update("delete from school_settings where school_id = ?", other);
            jdbc.update("delete from school where id = ?", other);
        }
    }

    @Test
    void onlyOfficeUsersMayUseNumberingSettings() throws Exception {
        mvc.perform(get("/api/v1/admin/settings/numbering")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/settings/numbering").header("Authorization", TestJwt.appUser("admin").bearer()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode configure(String prefix, String separator, int length, boolean role, boolean year) throws Exception {
        long v = body(get("/api/v1/admin/settings/numbering")).get("version").asLong();
        return body(putSection("numbering", "{\"prefix\":\"" + prefix + "\",\"separator\":\"" + separator + "\",\"length\":" + length
                + ",\"rolePrefix\":" + role + ",\"includeYear\":" + year + ",\"version\":" + v + "}").andExpect(status().isOk()));
    }

    private ResultActions putSection(String section, String content) throws Exception {
        return mvc.perform(put("/api/v1/admin/settings/" + section).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private ResultActions post1(String url, String content) throws Exception {
        return mvc.perform(post(url).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private JsonNode body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization", admin.bearer())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static String yy() {
        return String.format("%02d", LocalDate.now(ZoneId.of("Asia/Phnom_Penh")).getYear() % 100);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
