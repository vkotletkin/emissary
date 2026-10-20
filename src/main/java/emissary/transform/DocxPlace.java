package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;

import jakarta.annotation.Nullable;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Обработчик документов Microsoft Word ({@code DOCX} и {@code DOC}) на фазе {@code TRANSFORM} (на базе Apache POI).
 * <ul>
 * <li>За один проход извлекает метаданные документа ({@code DOC_TITLE}, {@code DOC_AUTHOR},
 * {@code DOC_LAST_MODIFIED_BY}, {@code DOC_COMPANY}, {@code DOC_CREATED}, {@code DOC_MODIFIED}, {@code DOC_PAGE_COUNT},
 * {@code DOC_WORD_COUNT}) в параметры родителя.</li>
 * <li>Извлекает полный текст (абзацы, таблицы, колонтитулы, сноски) и порождает дочерний текстовый объект с формой
 * {@link Form#TXT}.</li>
 * <li>Для {@code DOCX} извлекает вложенные документы/объекты (OLE/package embeddings, кроме картинок) как дочерние
 * объекты с формой {@link Form#UNKNOWN} и параметром {@code ATTACHMENT_FILENAME}.</li>
 * <li>Переводит родительский объект в форму {@link Form#DOCX_PROCESSED} или {@link Form#DOC_PROCESSED}.</li>
 * </ul>
 */
public class DocxPlace extends ServiceProviderPlace {

    private static final String EXTENDING_FILE_SUFFIX = "-att-";
    private static final String CHARSET_UTF_8 = "UTF-8";

    protected boolean extractEmbeddedObjects = true;

    public DocxPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
        configurePlace();
    }

    public DocxPlace(final InputStream configStream) throws IOException {
        super(configStream);
        configurePlace();
    }

    public DocxPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
        configurePlace();
    }

    public DocxPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "DocxPlace.example.com:8001");
        configurePlace();
    }

    public DocxPlace() throws IOException {
        super();
        configurePlace();
    }

    public static void extractOoxmlMetadata(@Nullable final POIXMLProperties props, final IBaseDataObject payload) {
        if (props == null) {
            return;
        }
        POIXMLProperties.CoreProperties core = props.getCoreProperties();
        if (core != null) {
            putIfNotBlank(payload, "DOC_TITLE", core.getTitle());
            putIfNotBlank(payload, "DOC_SUBJECT", core.getSubject());
            putIfNotBlank(payload, "DOC_AUTHOR", core.getCreator());
            putIfNotBlank(payload, "DOC_LAST_MODIFIED_BY", core.getLastModifiedByUser());
            putIfNotBlank(payload, "DOC_KEYWORDS", core.getKeywords());
            putIfNotBlank(payload, "DOC_DESCRIPTION", core.getDescription());
            putIfNotBlank(payload, "DOC_CATEGORY", core.getCategory());
            putIfNotBlank(payload, "DOC_REVISION", core.getRevision());
            putDateIfPresent(payload, "DOC_CREATED", core.getCreated());
            putDateIfPresent(payload, "DOC_MODIFIED", core.getModified());
            putDateIfPresent(payload, "DOC_LAST_PRINTED", core.getLastPrinted());
        }

        POIXMLProperties.ExtendedProperties ext = props.getExtendedProperties();
        if (ext != null) {
            putIfNotBlank(payload, "DOC_COMPANY", ext.getCompany());
            putIfNotBlank(payload, "DOC_MANAGER", ext.getManager());
            putIfNotBlank(payload, "DOC_APPLICATION", ext.getApplication());
            putIfNotBlank(payload, "DOC_APP_VERSION", ext.getAppVersion());
            putIfNotBlank(payload, "DOC_TEMPLATE", ext.getTemplate());
            if (ext.getPages() > 0) {
                payload.putParameter("DOC_PAGE_COUNT", Integer.toString(ext.getPages()));
            }
            if (ext.getWords() > 0) {
                payload.putParameter("DOC_WORD_COUNT", Integer.toString(ext.getWords()));
            }
        }
    }

    public static void extractOleMetadata(@Nullable final SummaryInformation si,
            @Nullable final DocumentSummaryInformation dsi, final IBaseDataObject payload) {
        if (si != null) {
            putIfNotBlank(payload, "DOC_TITLE", si.getTitle());
            putIfNotBlank(payload, "DOC_SUBJECT", si.getSubject());
            putIfNotBlank(payload, "DOC_AUTHOR", si.getAuthor());
            putIfNotBlank(payload, "DOC_LAST_MODIFIED_BY", si.getLastAuthor());
            putIfNotBlank(payload, "DOC_KEYWORDS", si.getKeywords());
            putIfNotBlank(payload, "DOC_DESCRIPTION", si.getComments());
            putIfNotBlank(payload, "DOC_APPLICATION", si.getApplicationName());
            putIfNotBlank(payload, "DOC_TEMPLATE", si.getTemplate());
            putIfNotBlank(payload, "DOC_REVISION", si.getRevNumber());
            putDateIfPresent(payload, "DOC_CREATED", si.getCreateDateTime());
            putDateIfPresent(payload, "DOC_MODIFIED", si.getEditTime() > 0 ? si.getLastSaveDateTime() : null);
            if (si.getPageCount() > 0) {
                payload.putParameter("DOC_PAGE_COUNT", Integer.toString(si.getPageCount()));
            }
            if (si.getWordCount() > 0) {
                payload.putParameter("DOC_WORD_COUNT", Integer.toString(si.getWordCount()));
            }
        }
        if (dsi != null) {
            putIfNotBlank(payload, "DOC_COMPANY", dsi.getCompany());
            putIfNotBlank(payload, "DOC_MANAGER", dsi.getManager());
            putIfNotBlank(payload, "DOC_CATEGORY", dsi.getCategory());
        }
    }

    private static void addTextChild(@Nullable final String text, final String sourceFormat,
            final IBaseDataObject parent, final List<IBaseDataObject> children) {
        if (text == null || text.isBlank()) {
            return;
        }
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        String childName = childBaseName(parent) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
        IBaseDataObject textChild = DataObjectFactory.getInstance(textBytes, childName, Form.TXT);
        textChild.setFileType(Form.TXT);
        textChild.setFontEncoding(CHARSET_UTF_8);
        textChild.putParameter("SOURCE_FORMAT", sourceFormat);
        textChild.putParameter("EXTRACTED_PART_TYPE", "DOCUMENT_TEXT");
        children.add(textChild);
    }

    private static String childBaseName(final IBaseDataObject parent) {
        String name = trimToNull(parent.shortName());
        if (name == null) {
            name = trimToNull(parent.getFilename());
        }
        return name != null ? name : "doc";
    }

    @Nullable
    private static String extractFileName(@Nullable final String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    private static void putIfNotBlank(final IBaseDataObject payload, final String key, @Nullable final String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            payload.putParameter(key, trimmed);
        }
    }

    private static void putDateIfPresent(final IBaseDataObject payload, final String key, @Nullable final Date date) {
        if (date != null) {
            payload.putParameter(key, date.toInstant().toString());
        }
    }

    @Nullable
    private static String trimToNull(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    protected void configurePlace() {
        if (configG != null) {
            this.extractEmbeddedObjects = configG.findBooleanEntry("EXTRACT_EMBEDDED_OBJECTS", true);
        }
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(
                Form.DOCX_PROCESSED,
                Form.DOC_PROCESSED,
                Form.DOCX + Form.SUFFIXES_PASSWORD_PROTECTED,
                Form.DOC + Form.SUFFIXES_PASSWORD_PROTECTED,
                Form.TXT,
                Form.UNKNOWN,
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

        boolean isLegacyDoc = Form.DOC.equalsIgnoreCase(payload.currentForm())
                || Form.DOC.equalsIgnoreCase(payload.getFileType());
        String baseForm = isLegacyDoc ? Form.DOC : Form.DOCX;
        payload.setFileTypeIfEmpty(baseForm);

        try {
            if (isLegacyDoc) {
                processLegacyDoc(payload, children);
                payload.setCurrentForm(Form.DOC_PROCESSED);
            } else {
                processOoxmlDocx(payload, children);
                payload.setCurrentForm(Form.DOCX_PROCESSED);
            }
        } catch (EncryptedDocumentException e) {
            logger.warn("Word document {} is password-protected", payload.shortName());
            payload.putParameter("DOC_ENCRYPTED", "true");
            payload.setCurrentForm(baseForm + Form.SUFFIXES_PASSWORD_PROTECTED);
        } catch (Exception e) {
            children.clear();
            logger.error("DocxPlace failed to parse {}", payload.shortName(), e);
            payload.addProcessingError("DocxPlace: " + e.getMessage());
            payload.setCurrentForm(Form.ERROR);
        }

        return children;
    }

    private void processOoxmlDocx(final IBaseDataObject payload, final List<IBaseDataObject> children) throws Exception {
        try (InputStream is = new ByteArrayInputStream(payload.data());
                XWPFDocument doc = new XWPFDocument(is);
                XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {

            extractOoxmlMetadata(doc.getProperties(), payload);

            String text = extractor.getText();
            addTextChild(text, Form.DOCX, payload, children);

            if (this.extractEmbeddedObjects) {
                extractDocxEmbeddings(doc, payload, children);
            }
        }
    }

    private void processLegacyDoc(final IBaseDataObject payload, final List<IBaseDataObject> children) throws Exception {
        try (InputStream is = new ByteArrayInputStream(payload.data());
                HWPFDocument doc = new HWPFDocument(is);
                WordExtractor extractor = new WordExtractor(doc)) {

            extractOleMetadata(doc.getSummaryInformation(), doc.getDocumentSummaryInformation(), payload);

            String text = extractor.getText();
            addTextChild(text, Form.DOC, payload, children);
        }
    }

    private void extractDocxEmbeddings(final XWPFDocument doc, final IBaseDataObject parent,
            final List<IBaseDataObject> children) throws Exception {
        List<PackagePart> embeddedParts = doc.getAllEmbeddedParts();
        if (embeddedParts == null || embeddedParts.isEmpty()) {
            return;
        }

        int embeddedCount = 0;
        for (PackagePart part : embeddedParts) {
            if (part == null) {
                continue;
            }
            String contentType = part.getContentType();
            // Картинки пока не трогаем
            if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
                continue;
            }

            byte[] bytes;
            try (InputStream partStream = part.getInputStream()) {
                bytes = partStream.readAllBytes();
            }
            if (bytes.length == 0) {
                continue;
            }

            String partPath = part.getPartName().getName();
            String filename = extractFileName(partPath);

            String childName = childBaseName(parent) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
            IBaseDataObject child = DataObjectFactory.getInstance(bytes, childName, Form.UNKNOWN);
            child.putParameter("SOURCE_FORMAT", Form.DOCX);
            child.putParameter("EXTRACTED_PART_TYPE", "EMBEDDED_OBJECT");
            if (filename != null) {
                child.putParameter("ATTACHMENT_FILENAME", filename);
            }
            if (contentType != null) {
                child.putParameter("ATTACHMENT_DECLARED_MIME", contentType);
            }
            children.add(child);
            embeddedCount++;
        }

        if (embeddedCount > 0) {
            parent.putParameter("DOC_EMBEDDED_OBJECT_COUNT", Integer.toString(embeddedCount));
        }
    }
}
