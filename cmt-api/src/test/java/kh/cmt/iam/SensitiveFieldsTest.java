package kh.cmt.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import kh.cmt.shared.export.ExcelExporter;
import kh.cmt.shared.export.ExportColumn;
import kh.cmt.shared.security.FieldMask;
import kh.cmt.shared.security.Sensitive;
import kh.cmt.shared.security.SensitiveField;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import kh.cmt.support.TestRoles;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Step 3.4 "done when": HR without the salary flag sees "••••" in a profile and the Excel export has no salary
 * column.
 * <p>
 * The person profile arrives in phase 4, so a stand-in profile view ({@link Profile}) is written with the API's own
 * ObjectMapper (the one every JSON response uses) and exported with the API's own {@link ExcelExporter}. The roles
 * and their settings are set through the real API.
 */
@ApiTest
class SensitiveFieldsTest {

    private static final String ROLES = "/api/v1/admin/roles";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ExcelExporter excel;

    @Autowired
    FieldMask mask;

    private final Caller superAdmin = TestJwt.webUser("super_admin");

    /** What the person profile will look like, as far as sensitive data goes. */
    record Profile(String name,
            @Sensitive(SensitiveField.SALARY_BANK) BigDecimal salary,
            @Sensitive(SensitiveField.SALARY_BANK) String bankAccount,
            @Sensitive(SensitiveField.NATIONAL_ID) String nationalId,
            @Sensitive(SensitiveField.PHONE) String phone,
            @Sensitive(SensitiveField.ID_SCANS) UUID idScanFile,
            @Sensitive(SensitiveField.ID_SCANS) List<String> documents) {
    }

    private static final Profile SOKHA = new Profile("Sokha Chan", new BigDecimal("650.00"), "ABA 000 123 456",
            "010203040", "012 345 678", UUID.fromString("11111111-2222-3333-4444-555555555555"), List.of("contract.pdf"));

    private static final List<ExportColumn<Profile>> COLUMNS = List.of(
            ExportColumn.of("Name", Profile::name),
            ExportColumn.sensitive("Salary", SensitiveField.SALARY_BANK, Profile::salary),
            ExportColumn.sensitive("Bank account", SensitiveField.SALARY_BANK, Profile::bankAccount),
            ExportColumn.sensitive("National ID", SensitiveField.NATIONAL_ID, Profile::nationalId),
            ExportColumn.sensitive("Phone", SensitiveField.PHONE, Profile::phone));

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ the "done when" check

    @Test
    void hrWithoutTheSalaryFlagSeesTheMaskAndTheExcelHasNoSalaryColumn() throws Exception {
        String role = hrWithoutSalary();

        JsonNode profile = asWebUser(role, this::profileJson);
        assertThat(profile.get("salary").asText()).isEqualTo("••••");
        assertThat(profile.get("bankAccount").asText()).isEqualTo("••••");
        assertThat(profile.get("nationalId").asText()).isEqualTo("010203040");
        assertThat(profile.get("phone").asText()).isEqualTo("012 345 678");
        assertThat(profile.get("idScanFile").asText()).isEqualTo("••••");   // this role has no ID scans either
        assertThat(profile.get("documents").asText()).isEqualTo("••••");
        assertThat(profile.get("name").asText()).isEqualTo("Sokha Chan");

        List<List<String>> sheet = asWebUser(role, this::excel);
        assertThat(sheet.get(0)).containsExactly("Name", "National ID", "Phone");   // the columns are gone, not empty
        assertThat(sheet.get(1)).containsExactly("Sokha Chan", "010203040", "012 345 678");
        assertThat(String.join(" ", sheet.get(1))).doesNotContain("650").doesNotContain("ABA");

        // the same profile and export for the system HR role (which has the salary flag)
        assertThat(asWebUser("hr", this::profileJson).get("salary").decimalValue()).isEqualByComparingTo("650.00");
        assertThat(asWebUser("hr", this::excel).get(0)).containsExactly("Name", "Salary", "Bank account", "National ID", "Phone");
    }

    @Test
    void sendingTheMaskBackNeverOverwritesTheStoredValue() throws Exception {
        String role = hrWithoutSalary();
        asWebUser(role, () -> {
            assertThat(mask.keepIfHidden(SensitiveField.SALARY_BANK, "••••", "650.00")).isEqualTo("650.00");
            assertThat(mask.keepIfHidden(SensitiveField.PHONE, "099 999 999", "012 345 678")).isEqualTo("099 999 999");
            return null;
        });
    }

    @Test
    void anEmptyValueStaysEmpty() throws Exception {
        String role = hrWithoutSalary();
        JsonNode profile = asWebUser(role, () -> write(new Profile("New person", null, null, null, null, null, null)));
        assertThat(profile.get("salary").isNull()).isTrue();
    }

    @Test
    void whoSeesWhat() throws Exception {
        assertThat(visible("web", "super_admin")).containsExactlyInAnyOrder(SensitiveField.values());
        assertThat(visible("web", "hr")).containsExactlyInAnyOrder(SensitiveField.SALARY_BANK, SensitiveField.NATIONAL_ID, SensitiveField.PHONE);
        assertThat(visible("web", "qr_manager")).containsExactlyInAnyOrder(SensitiveField.NATIONAL_ID, SensitiveField.PHONE);
        assertThat(visible("web", "viewer")).containsExactly(SensitiveField.PHONE);
        assertThat(visible("web", "no_such_role")).isEmpty();                     // fails closed
        assertThat(visible("app", "admin")).containsExactly(SensitiveField.PHONE);  // the admin app
        assertThat(visible("app", "staff")).containsExactlyInAnyOrder(SensitiveField.values());   // only their own records
        assertThat(visible("job", null)).containsExactlyInAnyOrder(SensitiveField.values());       // e.g. payroll system
        assertThat(mask.visibleFields()).containsExactlyInAnyOrder(SensitiveField.values());       // no caller: jobs

        JsonNode viewer = asWebUser("viewer", this::profileJson);
        assertThat(viewer.get("nationalId").asText()).isEqualTo("••••");
        assertThat(viewer.get("phone").asText()).isEqualTo("012 345 678");
    }

    @Test
    void aChangeAppliesToTheNextRequestWithTheSameToken() throws Exception {
        String role = hrWithoutSalary();
        Jwt token = token("web", role);
        assertThat(as(token, this::profileJson).get("salary").asText()).isEqualTo("••••");

        JsonNode before = roleByCode(role);
        JsonNode after = body(saveSensitive(before.get("id").asText(), true, true, true, false, before.get("version").asLong())
                .andExpect(status().isOk()));
        assertThat(after.get("permVersion").asInt()).isEqualTo(before.get("permVersion").asInt() + 1);

        assertThat(as(token, this::profileJson).get("salary").decimalValue()).isEqualByComparingTo("650.00");
        assertThat(as(token, this::excel).get(0)).contains("Salary");
    }

    // ------------------------------------------------------------------ the settings on the Roles page

    @Test
    void theSettingsAreShownAndSavedOnTheRole() throws Exception {
        mvc.perform(get(ROLES).header("Authorization", superAdmin.bearer()))
                .andExpect(jsonPath("$[?(@.code == 'super_admin')].sensitiveFields.idScans").value(true))
                .andExpect(jsonPath("$[?(@.code == 'hr')].sensitiveFields.salaryBank").value(true))
                .andExpect(jsonPath("$[?(@.code == 'hr')].sensitiveFields.idScans").value(false))
                .andExpect(jsonPath("$[?(@.code == 'viewer')].sensitiveFields.nationalId").value(false))
                .andExpect(jsonPath("$[?(@.code == 'viewer')].sensitiveFields.phone").value(true));

        // a new custom role: national ID and phone numbers, no salary, no ID scans
        JsonNode created = body(mvc.perform(post(ROLES).header("Authorization", superAdmin.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":{\"en\":\"Payroll clerk " + suffix() + "\"}}"))
                .andExpect(status().isCreated()));
        assertThat(created.get("sensitiveFields").toString())
                .isEqualTo("{\"salaryBank\":false,\"nationalId\":true,\"phone\":true,\"idScans\":false}");

        // created with its own settings
        JsonNode payroll = body(mvc.perform(post(ROLES).header("Authorization", superAdmin.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":{\"en\":\"Payroll " + suffix() + "\"},\"sensitiveFields\":"
                                + "{\"salaryBank\":true,\"nationalId\":false,\"phone\":false,\"idScans\":false}}"))
                .andExpect(status().isCreated()));
        assertThat(payroll.get("sensitiveFields").get("salaryBank").asBoolean()).isTrue();
        assertThat(payroll.get("sensitiveFields").get("phone").asBoolean()).isFalse();

        // the same settings again: nothing changes, no new permission version
        String id = created.get("id").asText();
        JsonNode same = body(saveSensitive(id, false, true, true, false, created.get("version").asLong()).andExpect(status().isOk()));
        assertThat(same.get("permVersion").asInt()).isEqualTo(created.get("permVersion").asInt());
        mvc.perform(get(ROLES + "/" + id).header("Authorization", superAdmin.bearer()))
                .andExpect(jsonPath("$.sensitiveFields.nationalId").value(true));
    }

    @Test
    void wrongSettingsAreRefused() throws Exception {
        JsonNode role = roleByCode(hrWithoutSalary());
        String id = role.get("id").asText();
        long version = role.get("version").asLong();

        mvc.perform(put(ROLES + "/" + id + "/sensitive-fields").header("Authorization", superAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"salaryBank\":true,\"nationalId\":true,\"phone\":true,\"version\":" + version + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sensitiveFields.idScans"))
                .andExpect(jsonPath("$.errors[0].rule").value("NotNull"));
        mvc.perform(put(ROLES + "/" + id + "/sensitive-fields").header("Authorization", superAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"salaryBank\":true,\"nationalId\":true,\"phone\":true,\"idScans\":true}"))
                .andExpect(status().isBadRequest());   // version missing
        saveSensitive(id, true, true, true, true, version + 5).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        saveSensitive(roleByCode("hr").get("id").asText(), true, true, true, true, 0).andExpect(jsonPath("$.code").value("ROLE_LOCKED"));

        mvc.perform(put(ROLES + "/" + id + "/sensitive-fields").header("Authorization", TestJwt.webUser("viewer").bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salaryBank\":true,\"nationalId\":true,\"phone\":true,\"idScans\":true,\"version\":" + version + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.module").value("users"));

        // the holder of the role cannot give themselves more
        TestRoles.callerWithRole(mvc, json, role.get("name").get("en").asText(),
                Map.of("people", List.of("view", "export"), "users", List.of("view", "edit")));
        JsonNode now = roleByCode(role.get("code").asText());
        mvc.perform(put(ROLES + "/" + id + "/sensitive-fields").header("Authorization", TestJwt.webUser(role.get("code").asText()).bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salaryBank\":true,\"nationalId\":true,\"phone\":true,\"idScans\":true,\"version\":" + now.get("version") + "}"))
                .andExpect(jsonPath("$.code").value("OWN_ROLE"));
    }

    // ------------------------------------------------------------------ helpers

    /** Custom role "HR without salary": People view + export; sees national ID, phone; no salary and bank, no ID scans. */
    private String hrWithoutSalary() throws Exception {
        Caller holder = TestRoles.callerWithRole(mvc, json, "HR without salary", Map.of("people", List.of("view", "export")));
        String code = body(mvc.perform(get("/api/v1/admin/me").header("Authorization", holder.bearer()))).get("role").asText();
        JsonNode role = roleByCode(code);
        saveSensitive(role.get("id").asText(), false, true, true, false, role.get("version").asLong()).andExpect(status().isOk());
        return code;
    }

    private JsonNode profileJson() {
        return write(SOKHA);
    }

    private JsonNode write(Profile profile) {
        try {
            return json.readTree(json.writeValueAsString(profile));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The export as text: first row the headers, then one row per person. */
    private List<List<String>> excel() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excel.write("People", COLUMNS, List.of(SOKHA), out);
        List<List<String>> rows = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (Cell cell : row) {
                    cells.add(cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            ? String.valueOf(cell.getNumericCellValue()) : cell.getStringCellValue());
                }
                rows.add(cells);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    private java.util.Set<SensitiveField> visible(String type, String role) {
        return as(token(type, role), mask::visibleFields);
    }

    private <T> T asWebUser(String role, Supplier<T> work) {
        return as(token("web", role), work);
    }

    private static Jwt token(String type, String role) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("typ", type).claim("school_id", TestJwt.SCHOOL.toString());
        if (role != null) {
            jwt.claim("role", role);
        }
        return jwt.build();
    }

    /** Runs {@code work} as one request of the token's user (a fresh request each time, like the browser). */
    private static <T> T as(Jwt token, Supplier<T> work) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ResultActions saveSensitive(String roleId, boolean salaryBank, boolean nationalId, boolean phone, boolean idScans,
            long version) throws Exception {
        return mvc.perform(put(ROLES + "/" + roleId + "/sensitive-fields").header("Authorization", superAdmin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"salaryBank\":" + salaryBank + ",\"nationalId\":" + nationalId + ",\"phone\":" + phone
                        + ",\"idScans\":" + idScans + ",\"version\":" + version + "}"));
    }

    private JsonNode roleByCode(String code) throws Exception {
        for (JsonNode role : body(mvc.perform(get(ROLES).header("Authorization", superAdmin.bearer())))) {
            if (role.get("code").asText().equals(code)) {
                return role;
            }
        }
        throw new AssertionError("no role " + code);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
