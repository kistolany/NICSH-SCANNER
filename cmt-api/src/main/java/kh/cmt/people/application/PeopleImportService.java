package kh.cmt.people.application;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.api.OrgQueries;
import kh.cmt.shared.export.ExcelReader;
import kh.cmt.shared.export.ExcelReader.SheetRow;
import kh.cmt.shared.export.ExcelTemplates;
import kh.cmt.shared.export.ExcelTemplates.TemplateColumn;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SensitiveField;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * People › Import from Excel: download the template, upload it for a preview (every row checked, nothing saved),
 * then import (the good rows are saved, the others are listed with their errors and skipped).
 * <p>
 * Each row goes through exactly the same checks as Add person, because it <i>is</i> Add person: for the preview it
 * runs in its own transaction that is rolled back, for the import in its own transaction that is committed when the
 * row is good. So a bad row never stops the others. Rows repeating a Staff ID or national ID of an earlier row of the
 * same file are refused too ({@code DuplicateInFile}). An empty Staff ID gets the next number; typing Staff IDs needs
 * the Super Admin role, as in Add person.
 * <p>
 * Columns are found by their header (order does not matter, required ones marked "*" in the template). Role, gender
 * and employment type accept the template's words ("Teacher", "Full time"); department and position are found by
 * their English or Khmer name; dates as Excel dates or yyyy-mm-dd / dd/mm/yyyy.
 */
@Service
public class PeopleImportService {

    static final int MAX_ROWS = 1000;
    static final long MAX_BYTES = 2L * 1024 * 1024;

    // column headers (the template's, without the "*")
    static final String STAFF_ID = "Staff ID";
    static final String NAME_KM = "Name (Khmer)";
    static final String NAME_EN = "Name (English)";
    static final String NAME_AR = "Name (Arabic)";
    static final String ROLE = "Role";
    static final String DEPARTMENT = "Department";
    static final String POSITION = "Position";
    static final String GENDER = "Gender";
    static final String BIRTH = "Date of birth";
    static final String PHONE = "Phone";
    static final String EMAIL = "Email";
    static final String NATIONAL_ID = "National ID";
    static final String EMPLOYMENT = "Employment type";
    static final String START = "Start date";

    /** Where a form field came from, to tell the user which column is wrong. */
    private static final Map<String, String> COLUMN_OF = Map.ofEntries(
            Map.entry("staffId", STAFF_ID), Map.entry("name.km", NAME_KM), Map.entry("name.en", NAME_EN),
            Map.entry("name.ar", NAME_AR), Map.entry("role", ROLE), Map.entry("departmentId", DEPARTMENT),
            Map.entry("positionId", POSITION), Map.entry("gender", GENDER), Map.entry("dateOfBirth", BIRTH),
            Map.entry("phone", PHONE), Map.entry("email", EMAIL), Map.entry("nationalId", NATIONAL_ID),
            Map.entry("employmentType", EMPLOYMENT), Map.entry("startDate", START));

    /** Strict: 31/02/2026 is refused, not turned into the 28th. */
    private static final List<DateTimeFormatter> DATES = java.util.stream.Stream.of("uuuu-MM-dd", "d/M/uuuu", "d-M-uuuu", "d.M.uuuu")
            .map(p -> DateTimeFormatter.ofPattern(p).withResolverStyle(java.time.format.ResolverStyle.STRICT)).toList();

    private final PersonService people;
    private final OrgQueries org;
    private final ExcelReader reader;
    private final ExcelTemplates templates;
    private final TransactionTemplate rowTransaction;

    PeopleImportService(PersonService people, OrgQueries org, ExcelReader reader, ExcelTemplates templates,
            PlatformTransactionManager transactions) {
        this.people = people;
        this.org = org;
        this.reader = reader;
        this.templates = templates;
        this.rowTransaction = new TransactionTemplate(transactions);
        this.rowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The template (.xlsx) with drop-downs of the school's roles, departments, positions... */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.CREATE)
    public void template(OutputStream out) {
        List<TemplateColumn> columns = List.of(
                TemplateColumn.text(STAFF_ID, false, 14),
                TemplateColumn.text(NAME_KM, true, 22),
                TemplateColumn.text(NAME_EN, true, 22),
                TemplateColumn.text(NAME_AR, false, 22),
                TemplateColumn.choice(ROLE, true, List.of("Staff", "Teacher", "Admin"), 12),
                TemplateColumn.choice(DEPARTMENT, false, org.activeNames(OrgKind.DEPARTMENT), 22),
                TemplateColumn.choice(POSITION, false, org.activeNames(OrgKind.POSITION), 22),
                TemplateColumn.choice(GENDER, false, List.of("Female", "Male"), 10),
                TemplateColumn.text(BIRTH, false, 14),
                TemplateColumn.sensitive(PHONE, SensitiveField.PHONE, 16),
                TemplateColumn.text(EMAIL, false, 28),
                TemplateColumn.sensitive(NATIONAL_ID, SensitiveField.NATIONAL_ID, 16),
                TemplateColumn.choice(EMPLOYMENT, false, List.of("Full time", "Part time", "Contract", "Volunteer", "Intern"), 16),
                TemplateColumn.text(START, false, 14));
        templates.write("People", columns,
                "Staff ID empty = the next number. Dates as yyyy-mm-dd. Columns marked * are required.", out);
    }

    /** Checks every row; nothing is saved. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.CREATE)
    public ImportResult preview(String fileName, long size, InputStream content) {
        return run(fileName, size, content, false);
    }

    /** Saves the good rows; the others are listed with their errors. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.CREATE)
    public ImportResult commit(String fileName, long size, InputStream content) {
        return run(fileName, size, content, true);
    }

    // ------------------------------------------------------------------ the work

    private ImportResult run(String fileName, long size, InputStream content, boolean save) {
        if (size > MAX_BYTES || (fileName != null && !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx"))) {
            throw new ApiException(ErrorCode.IMPORT_FILE).with("maxBytes", MAX_BYTES);
        }
        List<SheetRow> rows = reader.read(content, MAX_ROWS);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_TEMPLATE).with("missing", List.of(NAME_KM, NAME_EN, ROLE));
        }
        Map<String, Integer> columns = columns(rows.get(0));
        List<String> missing = new ArrayList<>();
        for (String required : List.of(NAME_KM, NAME_EN, ROLE)) {
            if (!columns.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_TEMPLATE).with("missing", missing);
        }

        List<ImportResult.RowResult> results = new ArrayList<>();
        Set<String> staffIds = new HashSet<>();
        Set<String> nationalIds = new HashSet<>();
        for (SheetRow row : rows.subList(1, rows.size())) {
            results.add(importRow(row, columns, save, staffIds, nationalIds));
        }
        int good = (int) results.stream().filter(r -> r.status().equals(ImportResult.OK)).count();
        return new ImportResult(save, results.size(), good, results.size() - good, results);
    }

    private ImportResult.RowResult importRow(SheetRow row, Map<String, Integer> columns, boolean save, Set<String> staffIds,
            Set<String> nationalIds) {
        String staffId = value(row, columns, STAFF_ID);
        String nameEn = value(row, columns, NAME_EN);
        String role = value(row, columns, ROLE);
        String department = value(row, columns, DEPARTMENT);
        List<ImportResult.Problem> problems = new ArrayList<>();

        // what the file says, turned into the Add person form; words that cannot be understood are row errors
        PersonCommands.Save form = new PersonCommands.Save(staffId,
                new LocalizedName(value(row, columns, NAME_KM), nameEn, value(row, columns, NAME_AR)),
                code(value(row, columns, GENDER)), date(row, columns, BIRTH, problems), null,
                value(row, columns, NATIONAL_ID), null, null, value(row, columns, PHONE), null, value(row, columns, EMAIL),
                null, null, code(role), orgItem(OrgKind.DEPARTMENT, department, DEPARTMENT, problems),
                orgItem(OrgKind.POSITION, value(row, columns, POSITION), POSITION, problems),
                code(value(row, columns, EMPLOYMENT)), date(row, columns, START, problems), null, null, null, null, null,
                null);
        if (staffId != null && !staffIds.add(staffId.toUpperCase(Locale.ROOT))) {
            problems.add(new ImportResult.Problem(STAFF_ID, "staffId", "DuplicateInFile"));
        }
        String nationalId = form.nationalId();
        if (nationalId != null && !nationalIds.add(nationalId.toUpperCase(Locale.ROOT))) {
            problems.add(new ImportResult.Problem(NATIONAL_ID, "nationalId", "DuplicateInFile"));
        }

        PersonView created = null;
        try {
            created = rowTransaction.execute(status -> {
                PersonView view = people.create(form);   // every Add person check
                if (!problems.isEmpty() || !save) {
                    status.setRollbackOnly();             // preview, or the row has errors of its own: nothing kept
                }
                return view;
            });
        } catch (ApiException refused) {
            problems.addAll(problemsOf(refused));
        }
        if (!problems.isEmpty()) {
            return new ImportResult.RowResult(row.number(), staffId, nameEn, role, department, ImportResult.ERROR, problems, null);
        }
        return new ImportResult.RowResult(row.number(), save ? created.staffId() : staffId, nameEn, role, department,
                ImportResult.OK, List.of(), save ? created.id() : null);
    }

    /** The field errors of a refused row, with the column they came from. */
    private static List<ImportResult.Problem> problemsOf(ApiException refused) {
        List<ImportResult.Problem> found = new ArrayList<>();
        Object errors = refused.properties().get("errors");
        if (refused.code() == ErrorCode.VALIDATION_ERROR && errors instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    String field = String.valueOf(m.get("field"));
                    found.add(new ImportResult.Problem(COLUMN_OF.getOrDefault(field, field), field, String.valueOf(m.get("rule"))));
                }
            }
            return found;
        }
        // whole-row refusals: Staff ID / national ID taken, typing a Staff ID without Super Admin...
        String field = switch (refused.code()) {
            case STAFF_ID_TAKEN, FORBIDDEN -> "staffId";
            case NATIONAL_ID_TAKEN -> "nationalId";
            default -> null;
        };
        found.add(new ImportResult.Problem(field == null ? null : COLUMN_OF.get(field), field, refused.code().name()));
        return found;
    }

    // ------------------------------------------------------------------ reading the cells

    /** Header text (without "*", ignoring case and spaces around) → column index. */
    private static Map<String, Integer> columns(SheetRow header) {
        Map<String, String> known = new HashMap<>();
        for (String name : List.of(STAFF_ID, NAME_KM, NAME_EN, NAME_AR, ROLE, DEPARTMENT, POSITION, GENDER, BIRTH, PHONE, EMAIL,
                NATIONAL_ID, EMPLOYMENT, START)) {
            known.put(name.toLowerCase(Locale.ROOT), name);
        }
        Map<String, Integer> found = new HashMap<>();
        for (int c = 0; c < header.cells().size(); c++) {
            String text = header.cell(c);
            if (text != null) {
                String name = known.get(text.replace("*", "").trim().toLowerCase(Locale.ROOT));
                if (name != null) {
                    found.putIfAbsent(name, c);
                }
            }
        }
        return found;
    }

    private static String value(SheetRow row, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        return index == null ? null : row.cell(index);
    }

    /** "Full time" → full_time, "Teacher" → teacher (the form checks the code). */
    private static String code(String text) {
        return text == null ? null : text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    private static LocalDate date(SheetRow row, Map<String, Integer> columns, String column, List<ImportResult.Problem> problems) {
        String text = value(row, columns, column);
        if (text == null) {
            return null;
        }
        for (DateTimeFormatter format : DATES) {
            try {
                return LocalDate.parse(text, format);
            } catch (DateTimeParseException tryNext) {
                // next format
            }
        }
        problems.add(new ImportResult.Problem(column, COLUMN_OF.entrySet().stream().filter(e -> e.getValue().equals(column))
                .map(Map.Entry::getKey).findFirst().orElse(column), "Date"));
        return null;
    }

    private UUID orgItem(OrgKind kind, String name, String column, List<ImportResult.Problem> problems) {
        if (name == null) {
            return null;
        }
        return org.activeByName(kind, name).orElseGet(() -> {
            problems.add(new ImportResult.Problem(column, kind == OrgKind.DEPARTMENT ? "departmentId" : "positionId",
                    kind == OrgKind.DEPARTMENT ? "UnknownDepartment" : "UnknownPosition"));
            return null;
        });
    }
}
