package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;

import jakarta.annotation.Nullable;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Обработчик электронных таблиц Microsoft Excel ({@code XLSX} и {@code XLS}) на фазе {@code TRANSFORM} (на базе Apache
 * POI).
 * <ul>
 * <li>За один проход извлекает метаданные книги (автор, компания, даты создания/изменения, количество и названия
 * листов) в параметры родителя.</li>
 * <li>Форматирует значения всех непустых ячеек (и комментариев) по листам и порождает дочерний текстовый объект с
 * формой {@link Form#TXT}.</li>
 * <li>Переводит родительский объект в форму {@link Form#XLSX_PROCESSED} или {@link Form#XLS_PROCESSED}.</li>
 * </ul>
 */
public class SpreadsheetPlace extends ServiceProviderPlace {

    private static final String EXTENDING_FILE_SUFFIX = "-att-";
    private static final String CHARSET_UTF_8 = "UTF-8";

    protected int maxRowsPerSheet = 50000;
    protected int maxColsPerRow = 500;
    protected boolean includeSheetNames = true;
    protected boolean includeCellComments = true;

    public SpreadsheetPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
        configurePlace();
    }

    public SpreadsheetPlace(final InputStream configStream) throws IOException {
        super(configStream);
        configurePlace();
    }

    public SpreadsheetPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
        configurePlace();
    }

    public SpreadsheetPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "SpreadsheetPlace.example.com:8001");
        configurePlace();
    }

    public SpreadsheetPlace() throws IOException {
        super();
        configurePlace();
    }

    private static void extractMetadata(final Workbook workbook, final IBaseDataObject payload) {
        int sheetCount = workbook.getNumberOfSheets();
        payload.putParameter("XLS_SHEET_COUNT", Integer.toString(sheetCount));
        for (int i = 0; i < sheetCount; i++) {
            String sheetName = workbook.getSheetName(i);
            if (sheetName != null && !sheetName.isBlank()) {
                payload.appendParameter("XLS_SHEET_NAME", sheetName.trim());
            }
        }

        if (workbook instanceof XSSFWorkbook xssf) {
            DocxPlace.extractOoxmlMetadata(xssf.getProperties(), payload);
        } else if (workbook instanceof HSSFWorkbook hssf) {
            DocxPlace.extractOleMetadata(hssf.getSummaryInformation(), hssf.getDocumentSummaryInformation(), payload);
        }
    }

    private static String childBaseName(final IBaseDataObject parent) {
        String name = parent.shortName();
        if (name == null || name.isBlank()) {
            name = parent.getFilename();
        }
        return (name != null && !name.isBlank()) ? name.trim() : "spreadsheet";
    }

    protected void configurePlace() {
        if (configG != null) {
            this.maxRowsPerSheet = Math.max(1, configG.findIntEntry("MAX_ROWS_PER_SHEET", 50000));
            this.maxColsPerRow = Math.max(1, configG.findIntEntry("MAX_COLS_PER_ROW", 500));
            this.includeSheetNames = configG.findBooleanEntry("INCLUDE_SHEET_NAMES", true);
            this.includeCellComments = configG.findBooleanEntry("INCLUDE_CELL_COMMENTS", true);
        }
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(
                Form.XLSX_PROCESSED,
                Form.XLS_PROCESSED,
                Form.XLSX + Form.SUFFIXES_PASSWORD_PROTECTED,
                Form.XLS + Form.SUFFIXES_PASSWORD_PROTECTED,
                Form.TXT,
                Form.EMPTY,
                Form.ERROR);
    }

    @Override
    protected boolean sproutsChildren() {
        return true;
    }

    @Override
    public List<IBaseDataObject> processHeavyDuty(final IBaseDataObject payload) throws ResourceException {
        List<IBaseDataObject> children = new ArrayList<>();

        if (payload.dataLength() == 0) {
            payload.setCurrentForm(Form.EMPTY);
            return children;
        }

        boolean isLegacyXls = Form.XLS.equalsIgnoreCase(payload.currentForm())
                || Form.XLS.equalsIgnoreCase(payload.getFileType());
        String baseForm = isLegacyXls ? Form.XLS : Form.XLSX;
        payload.setFileTypeIfEmpty(baseForm);

        try (InputStream is = new ByteArrayInputStream(payload.data());
                Workbook workbook = WorkbookFactory.create(is)) {

            extractMetadata(workbook, payload);

            String text = extractWorkbookText(workbook);
            if (!text.isBlank()) {
                byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
                String childName = childBaseName(payload) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
                IBaseDataObject textChild = DataObjectFactory.getInstance(textBytes, childName, Form.TXT);
                textChild.setFileType(Form.TXT);
                textChild.setFontEncoding(CHARSET_UTF_8);
                textChild.putParameter("SOURCE_FORMAT", baseForm);
                textChild.putParameter("EXTRACTED_PART_TYPE", "SPREADSHEET_TEXT");
                children.add(textChild);
            }

            payload.setCurrentForm(isLegacyXls ? Form.XLS_PROCESSED : Form.XLSX_PROCESSED);
        } catch (EncryptedDocumentException e) {
            logger.warn("Spreadsheet {} is password-protected", payload.shortName());
            payload.putParameter("DOC_ENCRYPTED", "true");
            payload.setCurrentForm(baseForm + Form.SUFFIXES_PASSWORD_PROTECTED);
        } catch (Exception e) {
            children.clear();
            logger.error("SpreadsheetPlace failed to parse {}", payload.shortName(), e);
            payload.addProcessingError("SpreadsheetPlace: " + e.getMessage());
            payload.setCurrentForm(Form.ERROR);
        }

        return children;
    }

    private String extractWorkbookText(final Workbook workbook) {
        StringBuilder sb = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = null;
        try {
            evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        } catch (Exception ignored) {
            // fallback to cached formula values
        }

        int sheetCount = workbook.getNumberOfSheets();
        for (int s = 0; s < sheetCount; s++) {
            Sheet sheet = workbook.getSheetAt(s);
            if (sheet == null) {
                continue;
            }
            if (this.includeSheetNames && sheet.getSheetName() != null) {
                sb.append("=== ").append(sheet.getSheetName()).append(" ===\n");
            }

            int rowCount = 0;
            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }
                if (++rowCount > this.maxRowsPerSheet) {
                    break;
                }
                appendRowText(row, formatter, evaluator, sb);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void appendRowText(final Row row, final DataFormatter formatter,
            @Nullable final FormulaEvaluator evaluator, final StringBuilder sb) {
        short lastCellNum = row.getLastCellNum();
        if (lastCellNum <= 0) {
            return;
        }

        int limit = Math.min(lastCellNum, this.maxColsPerRow);
        boolean rowHasValues = false;
        StringBuilder rowBuffer = new StringBuilder();

        for (int c = 0; c < limit; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) {
                continue;
            }

            String val;
            try {
                val = formatter.formatCellValue(cell, evaluator);
            } catch (Exception ignored) {
                val = formatter.formatCellValue(cell);
            }

            if (val != null && !val.isBlank()) {
                if (rowHasValues) {
                    rowBuffer.append(" | ");
                }
                rowBuffer.append(val.trim());
                rowHasValues = true;
            }

            if (this.includeCellComments) {
                Comment comment = cell.getCellComment();
                if (comment != null && comment.getString() != null) {
                    String commentStr = comment.getString().getString();
                    if (commentStr != null && !commentStr.isBlank()) {
                        rowBuffer.append(" [").append(commentStr.trim()).append(']');
                        rowHasValues = true;
                    }
                }
            }
        }

        if (rowHasValues) {
            sb.append(rowBuffer).append('\n');
        }
    }
}
