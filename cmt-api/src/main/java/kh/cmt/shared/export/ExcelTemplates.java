package kh.cmt.shared.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import kh.cmt.shared.security.FieldMask;
import kh.cmt.shared.security.SensitiveField;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Writes import templates (.xlsx): a header row (required columns marked), a sheet "Lists" with the allowed values,
 * and drop-downs that use them. Columns of sensitive data the caller may not see are left out, as in exports.
 */
@Component
public class ExcelTemplates {

    /**
     * One column of a template.
     *
     * @param choices   allowed values (a drop-down), or empty for free input
     * @param sensitive the group of sensitive data, or null
     */
    public record TemplateColumn(String header, boolean required, List<String> choices, SensitiveField sensitive, int width) {

        public static TemplateColumn text(String header, boolean required, int width) {
            return new TemplateColumn(header, required, List.of(), null, width);
        }

        public static TemplateColumn choice(String header, boolean required, List<String> choices, int width) {
            return new TemplateColumn(header, required, choices, null, width);
        }

        public static TemplateColumn sensitive(String header, SensitiveField field, int width) {
            return new TemplateColumn(header, false, List.of(), field, width);
        }
    }

    private static final int TEMPLATE_ROWS = 1000;

    private final FieldMask mask;

    ExcelTemplates(FieldMask mask) {
        this.mask = mask;
    }

    /** The columns the caller gets (sensitive ones they may not see are left out). */
    public List<TemplateColumn> columnsFor(List<TemplateColumn> columns) {
        return mask.visibleColumns(columns, TemplateColumn::sensitive);
    }

    public void write(String sheetName, List<TemplateColumn> columns, String note, OutputStream out) {
        List<TemplateColumn> visible = columnsFor(columns);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            Sheet lists = workbook.createSheet("Lists");
            CellStyle header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            CellStyle required = workbook.createCellStyle();
            required.cloneStyleFrom(header);
            Font requiredFont = workbook.createFont();
            requiredFont.setBold(true);
            requiredFont.setColor(IndexedColors.DARK_RED.getIndex());
            required.setFont(requiredFont);

            Row head = sheet.createRow(0);
            DataValidationHelper validations = sheet.getDataValidationHelper();
            int listColumn = 0;
            for (int c = 0; c < visible.size(); c++) {
                TemplateColumn column = visible.get(c);
                Cell cell = head.createCell(c);
                cell.setCellValue(column.required() ? column.header() + " *" : column.header());
                cell.setCellStyle(column.required() ? required : header);
                sheet.setColumnWidth(c, Math.max(8, column.width()) * 256);
                if (!column.choices().isEmpty()) {
                    // the values go on the Lists sheet; the drop-down points at them through a name
                    for (int i = 0; i < column.choices().size(); i++) {
                        Row row = lists.getRow(i) == null ? lists.createRow(i) : lists.getRow(i);
                        row.createCell(listColumn).setCellValue(column.choices().get(i));
                    }
                    String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(listColumn);
                    Name name = workbook.createName();
                    name.setNameName("list_" + c);
                    name.setRefersToFormula("Lists!$" + letter + "$1:$" + letter + "$" + column.choices().size());
                    DataValidation dropDown = validations.createValidation(validations.createFormulaListConstraint("list_" + c),
                            new CellRangeAddressList(1, TEMPLATE_ROWS, c, c));
                    dropDown.setShowErrorBox(false);   // other spellings are still checked on import
                    sheet.addValidationData(dropDown);
                    listColumn++;
                }
            }
            if (note != null) {
                Row noteRow = lists.getRow(0) == null ? lists.createRow(0) : lists.getRow(0);
                noteRow.createCell(listColumn + 1).setCellValue(note);
            }
            sheet.createFreezePane(0, 1);
            workbook.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
