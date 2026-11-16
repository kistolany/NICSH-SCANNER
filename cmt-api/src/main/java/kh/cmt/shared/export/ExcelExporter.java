package kh.cmt.shared.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import kh.cmt.shared.security.FieldMask;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Writes Excel (.xlsx) exports. Every exporter goes through here, so the columns of sensitive data the caller may
 * not see are always left out (not just emptied): see {@link FieldMask#visibleColumns}. Rows are streamed, so large
 * exports do not fill the memory.
 */
@Component
public class ExcelExporter {

    private static final int ROWS_IN_MEMORY = 200;

    private final FieldMask mask;

    ExcelExporter(FieldMask mask) {
        this.mask = mask;
    }

    /** The columns that will be written for the current caller. */
    public <T> List<ExportColumn<T>> columnsFor(List<ExportColumn<T>> columns) {
        return mask.visibleColumns(columns, ExportColumn::sensitive);
    }

    public <T> void write(String sheetName, List<ExportColumn<T>> columns, Iterable<T> rows, OutputStream out) {
        List<ExportColumn<T>> visible = columnsFor(columns);
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(ROWS_IN_MEMORY)) {
            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));

            Row header = sheet.createRow(0);
            for (int c = 0; c < visible.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(visible.get(c).header());
                cell.setCellStyle(headerStyle);
            }
            int r = 1;
            for (T row : rows) {
                Row line = sheet.createRow(r++);
                for (int c = 0; c < visible.size(); c++) {
                    write(line.createCell(c), visible.get(c).value().apply(row), dateStyle, dateTimeStyle);
                }
            }
            sheet.createFreezePane(0, 1);
            workbook.write(out);   // closing the workbook removes its temporary files
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Cell cell, Object value, CellStyle dateStyle, CellStyle dateTimeStyle) {
        switch (value) {
            case null -> cell.setBlank();
            case Number n -> cell.setCellValue(n.doubleValue());
            case Boolean b -> cell.setCellValue(b);
            case LocalDate d -> {
                cell.setCellValue(d);
                cell.setCellStyle(dateStyle);
            }
            case LocalDateTime t -> {
                cell.setCellValue(t);
                cell.setCellStyle(dateTimeStyle);
            }
            default -> cell.setCellValue(value.toString());
        }
    }
}
