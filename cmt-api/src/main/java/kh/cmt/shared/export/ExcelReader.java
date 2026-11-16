package kh.cmt.shared.export;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Reads the first sheet of an uploaded Excel (.xlsx) file as text: every cell as the user sees it, dates as
 * {@code yyyy-MM-dd}, whole numbers without ".0", text trimmed, empty cells as null. Empty rows are left out but keep
 * their row number. Like the exporter, this is the one place that touches Apache POI.
 */
@Component
public class ExcelReader {

    /** One row: its number in the sheet (1 = the header row, as Excel shows it) and its cells. */
    public record SheetRow(int number, List<String> cells) {

        public String cell(int index) {
            return index >= 0 && index < cells.size() ? cells.get(index) : null;
        }
    }

    /**
     * The rows of the first sheet, the header row first. {@code IMPORT_FILE} if it is not a readable .xlsx;
     * {@code IMPORT_TOO_MANY_ROWS} for more than {@code maxDataRows} rows under the header.
     */
    public List<SheetRow> read(InputStream in, int maxDataRows) {
        try (Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<SheetRow> rows = new ArrayList<>();
            int dataRows = 0;
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                boolean any = false;
                for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                    String text = text(row.getCell(c));
                    cells.add(text);
                    any |= text != null;
                }
                if (!any) {
                    continue;
                }
                if (!rows.isEmpty() && ++dataRows > maxDataRows) {
                    throw new ApiException(ErrorCode.IMPORT_TOO_MANY_ROWS).with("maxRows", maxDataRows);
                }
                rows.add(new SheetRow(row.getRowNum() + 1, cells));
            }
            return rows;
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException notExcel) {
            throw new ApiException(ErrorCode.IMPORT_FILE);
        }
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
        if (value == null) {
            return null;
        }
        value = value.strip();
        return value.isEmpty() ? null : value;
    }
}
