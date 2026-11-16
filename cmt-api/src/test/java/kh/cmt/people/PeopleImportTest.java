package kh.cmt.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Step 4.5 "done when": a file with 2 bad rows imports the good ones and lists the errors. */
@ApiTest
class PeopleImportTest {

    private static final String IMPORT = "/api/v1/admin/people/import";
    private static final List<String> HEADER = List.of("Staff ID", "Name (Khmer) *", "Name (English) *", "Name (Arabic)", "Role *",
            "Department", "Position", "Gender", "Date of birth", "Phone", "Email", "National ID", "Employment type", "Start date");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private UUID school;
    private Caller admin;

    @BeforeEach
    void schoolOfItsOwn() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Import school', now(), now())")
                .param("id", school).param("c", "I" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        for (String[] dept : new String[][] {{"Front office", "ការិយាល័យ"}, {"Primary school", "បឋមសិក្សា"}, {"Finance", "ហិរញ្ញវត្ថុ"}}) {
            mvc.perform(post("/api/v1/admin/departments").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":{\"en\":\"" + dept[0] + "\",\"km\":\"" + dept[1] + "\"}}")).andExpect(status().isCreated());
        }
    }

    // ------------------------------------------------------------------ the "done when" check

    @Test
    void aFileWithTwoBadRowsImportsTheGoodOnesAndListsTheErrors() throws Exception {
        byte[] file = xlsx(HEADER,
                row(null, "សុខា លីម", "Sokha Lim", null, "Staff", "Front office", null, "Female", "1992-05-14", "012 345 678",
                        "sokha@cmt.school", null, "Full time", "12/10/2026"),
                row(null, "កញ្ញា អ៊ុក", "Kanha Ouk", null, "Teacher", "បឋមសិក្សា", null, null, null, null, null, null, null, null),
                row(null, "រចនា ពៅ", "Rachana Pov", null, "Teachr", "Primary school", null, null, null, null, null, null, null, null),
                row(null, "ស៊ុន", null, null, "Staff", "Finance", null, null, null, null, null, null, null, null),
                row(null, "សុធា ម៉ែន", "Sothea Men", null, "staff", "Finance", null, "male", null, null, null, null, "part-time", null));

        // preview: every row checked, nothing saved
        JsonNode preview = body(upload("preview", file).andExpect(status().isOk()));
        assertThat(preview.get("imported").asBoolean()).isFalse();
        assertThat(preview.get("total").asInt()).isEqualTo(5);
        assertThat(preview.get("good").asInt()).isEqualTo(3);
        assertThat(preview.get("bad").asInt()).isEqualTo(2);
        JsonNode teachr = preview.get("rows").get(2);
        assertThat(teachr.get("row").asInt()).isEqualTo(4);   // Excel's row number (row 1 is the header)
        assertThat(teachr.get("status").asText()).isEqualTo("ERROR");
        assertThat(teachr.get("errors").get(0).get("column").asText()).isEqualTo("Role");
        assertThat(teachr.get("errors").get(0).get("rule").asText()).isEqualTo("OneOf");
        JsonNode noName = preview.get("rows").get(3);
        assertThat(noName.get("errors").get(0).get("column").asText()).isEqualTo("Name (English)");
        assertThat(noName.get("errors").get(0).get("rule").asText()).isEqualTo("NotBlank");
        assertThat(preview.get("rows").get(0).get("status").asText()).isEqualTo("OK");
        assertThat(people()).isZero();

        // import: the good rows are saved with the next Staff IDs, the bad ones skipped and listed
        JsonNode done = body(upload("commit", file).andExpect(status().isOk()));
        assertThat(done.get("imported").asBoolean()).isTrue();
        assertThat(done.get("good").asInt()).isEqualTo(3);
        assertThat(done.get("bad").asInt()).isEqualTo(2);
        assertThat(people()).isEqualTo(3);
        List<String> ids = new ArrayList<>();
        done.get("rows").forEach(r -> {
            if (r.get("status").asText().equals("OK")) {
                ids.add(r.get("staffId").asText());
                assertThat(r.get("personId")).isNotNull();
            }
        });
        assertThat(ids).containsExactly("CMT-0001", "CMT-0002", "CMT-0003");   // numbers only for saved rows

        JsonNode sokha = body(mvc.perform(get("/api/v1/admin/people/" + done.get("rows").get(0).get("personId").asText())
                .header("Authorization", admin.bearer())));
        assertThat(sokha.get("department").get("name").get("en").asText()).isEqualTo("Front office");
        assertThat(sokha.get("gender").asText()).isEqualTo("female");
        assertThat(sokha.get("employmentType").asText()).isEqualTo("full_time");
        assertThat(sokha.get("startDate").asText()).isEqualTo("2026-10-12");
        assertThat(sokha.get("dateOfBirth").asText()).isEqualTo("1992-05-14");
        JsonNode sothea = body(mvc.perform(get("/api/v1/admin/people/" + done.get("rows").get(4).get("personId").asText())
                .header("Authorization", admin.bearer())));
        assertThat(sothea.get("employmentType").asText()).isEqualTo("part_time");
    }

    // ------------------------------------------------------------------ the rows

    @Test
    void staffIdsAndNationalIdsAreCheckedAgainstTheSchoolAndTheFile() throws Exception {
        byte[] first = xlsx(HEADER, row("CMT-0100", "ក", "Typed Id", null, "Staff", null, null, null, null, null, null, "N-1001", null, null));
        assertThat(body(upload("commit", first)).get("good").asInt()).isEqualTo(1);

        byte[] file = xlsx(HEADER,
                row("cmt-0100", "ក", "Taken Id", null, "Staff", null, null, null, null, null, null, null, null, null),
                row("CMT-0200", "ក", "New Id", null, "Staff", null, null, null, null, null, null, null, null, null),
                row("CMT-0200", "ក", "Same Id Again", null, "Staff", null, null, null, null, null, null, null, null, null),
                row(null, "ក", "Same National Id", null, "Staff", null, null, null, null, null, null, "n-1001", null, null),
                row(null, "ក", "Unknown Dept", null, "Staff", "Library", null, null, "31/02/2026", null, null, null, null, null));
        JsonNode result = body(upload("preview", file));
        assertThat(rules(result, 2)).containsExactly("STAFF_ID_TAKEN");
        assertThat(result.get("rows").get(1).get("status").asText()).isEqualTo("OK");
        assertThat(rules(result, 4)).containsExactly("DuplicateInFile");
        assertThat(rules(result, 5)).containsExactly("NATIONAL_ID_TAKEN");
        assertThat(rules(result, 6)).contains("UnknownDepartment", "Date");

        // typing Staff IDs needs Super Admin, as in Add person; empty ones are fine for HR
        Caller hr = TestJwt.webUser("hr", school);
        JsonNode byHr = body(upload("preview", hr, xlsx(HEADER,
                row("CMT-0300", "ក", "Hr Typed", null, "Staff", null, null, null, null, null, null, null, null, null),
                row(null, "ក", "Hr Auto", null, "Staff", null, null, null, null, null, null, null, null, null))));
        assertThat(rules(byHr, 2)).containsExactly("FORBIDDEN");
        assertThat(byHr.get("rows").get(1).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void columnsAreFoundByTheirHeader() throws Exception {
        byte[] file = xlsx(List.of("Role", "Name (English)", "Name (Khmer)", "Email"),
                row("Admin", "Bopha Ly", "បុប្ផា លី", "bopha@cmt.school"));
        JsonNode done = body(upload("commit", file).andExpect(status().isOk()));
        assertThat(done.get("good").asInt()).isEqualTo(1);

        upload("preview", xlsx(List.of("Full name", "Role"), row("X", "Staff")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMPORT_TEMPLATE"))
                .andExpect(jsonPath("$.missing[0]").value("Name (Khmer)"));
        mvc.perform(multipart(IMPORT + "/preview").file(new MockMultipartFile("file", "people.xlsx", "application/octet-stream",
                "not excel".getBytes())).header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.code").value("IMPORT_FILE"));
        mvc.perform(multipart(IMPORT + "/preview").file(new MockMultipartFile("file", "people.csv", "text/csv", file))
                .header("Authorization", admin.bearer())).andExpect(jsonPath("$.code").value("IMPORT_FILE"));
    }

    // ------------------------------------------------------------------ the template

    @Test
    void theTemplateHasTheColumnsAndTheSchoolsLists() throws Exception {
        byte[] bytes = mvc.perform(get(IMPORT + "/template").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("people-import-template.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<String> header = new ArrayList<>();
            workbook.getSheet("People").getRow(0).forEach(c -> header.add(c.getStringCellValue()));
            assertThat(header).isEqualTo(HEADER);
            List<String> lists = new ArrayList<>();
            workbook.getSheet("Lists").forEach(r -> r.forEach(c -> lists.add(c.getStringCellValue())));
            assertThat(lists).contains("Teacher", "Front office", "Primary school", "Full time");
            assertThat(workbook.getSheet("People").getDataValidations()).isNotEmpty();
        }

        // the template can be filled in and imported as it is
        byte[] filled;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Row row = workbook.getSheet("People").createRow(1);
            row.createCell(1).setCellValue("ចន្ថា សុខ");
            row.createCell(2).setCellValue("Chantha Sok");
            row.createCell(4).setCellValue("Teacher");
            workbook.write(out);
            filled = out.toByteArray();
        }
        assertThat(body(upload("commit", filled)).get("good").asInt()).isEqualTo(1);

        // a role that may not see national IDs gets no National ID column; one without People create gets nothing
        JsonNode role = body(mvc.perform(post("/api/v1/admin/roles").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"en\":\"Importer\"},\"sensitiveFields\":{\"salaryBank\":false,\"nationalId\":false,\"phone\":true,\"idScans\":false}}")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/roles/" + role.get("id").asText() + "/permissions")
                .header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":{\"people\":[\"view\",\"create\"]},\"version\":" + role.get("version") + "}")).andExpect(status().isOk());
        byte[] limited = mvc.perform(get(IMPORT + "/template").header("Authorization", TestJwt.webUser(role.get("code").asText(), school).bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(limited))) {
            List<String> header = new ArrayList<>();
            workbook.getSheet("People").getRow(0).forEach(c -> header.add(c.getStringCellValue()));
            assertThat(header).doesNotContain("National ID").contains("Phone");
        }
        mvc.perform(get(IMPORT + "/template").header("Authorization", TestJwt.webUser("viewer", school).bearer()))
                .andExpect(status().isForbidden());
        upload("preview", TestJwt.webUser("viewer", school), filled).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> row(String... cells) {
        List<String> row = new ArrayList<>();
        for (String c : cells) {
            row.add(c);
        }
        return row;
    }

    @SafeVarargs
    private static byte[] xlsx(List<String> header, List<String>... rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("People");
            Row head = sheet.createRow(0);
            for (int c = 0; c < header.size(); c++) {
                head.createCell(c).setCellValue(header.get(c));
            }
            for (int r = 0; r < rows.length; r++) {
                Row line = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].size(); c++) {
                    if (rows[r].get(c) != null) {
                        line.createCell(c).setCellValue(rows[r].get(c));
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private ResultActions upload(String step, byte[] file) throws Exception {
        return upload(step, admin, file);
    }

    private ResultActions upload(String step, Caller caller, byte[] file) throws Exception {
        return mvc.perform(multipart(IMPORT + "/" + step)
                .file(new MockMultipartFile("file", "people.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file))
                .header("Authorization", caller.bearer()));
    }

    /** The error rules of the row with this Excel row number. */
    private static List<String> rules(JsonNode result, int excelRow) {
        List<String> rules = new ArrayList<>();
        result.get("rows").forEach(r -> {
            if (r.get("row").asInt() == excelRow && r.has("errors")) {
                r.get("errors").forEach(e -> rules.add(e.get("rule").asText()));
            }
        });
        return rules;
    }

    private int people() {
        return jdbc.sql("select count(*) from person where school_id = :s").param("s", school).query(Integer.class).single();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
