package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;

import jakarta.annotation.Nullable;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Обработчик документов {@code PDF} на фазе {@code TRANSFORM} (на базе Apache PDFBox).
 * <ul>
 * <li>За один проход извлекает метаданные документа ({@code PDF_TITLE}, {@code PDF_AUTHOR}, {@code PDF_SUBJECT},
 * {@code PDF_KEYWORDS}, {@code PDF_CREATOR}, {@code PDF_PRODUCER}, {@code PDF_CREATION_DATE},
 * {@code PDF_MODIFICATION_DATE}, {@code PDF_PAGE_COUNT}, {@code PDF_VERSION}) в параметры родителя.</li>
 * <li>Извлекает текстовый слой всех страниц и порождает дочерний текстовый объект с формой {@link Form#TXT} (который
 * далее пойдёт в {@link TextCleanPlace} -&gt; {@link emissary.analyze.TextAnalyzePlace}).</li>
 * <li>Извлекает вложенные в PDF файлы (EmbeddedFiles / Portfolio attachments, кроме картинок) как дочерние объекты с
 * формой {@link Form#UNKNOWN} и параметром {@code ATTACHMENT_FILENAME}.</li>
 * <li>Родительский объект сохраняет исходные байты {@code data()}, получает {@code fileType = PDF} и переходит в форму
 * {@link Form#PDF_PROCESSED} (или {@code PDF-PASSWORD-PROTECTED} для зашифрованных PDF).</li>
 * </ul>
 */
public class PdfPlace extends ServiceProviderPlace {

    private static final String EXTENDING_FILE_SUFFIX = "-att-";
    private static final String CHARSET_UTF_8 = "UTF-8";

    protected int maxPages = 2000;
    protected boolean sortByPosition = false;
    protected boolean extractEmbeddedFiles = true;

    public PdfPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
        configurePlace();
    }

    public PdfPlace(final InputStream configStream) throws IOException {
        super(configStream);
        configurePlace();
    }

    public PdfPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
        configurePlace();
    }

    public PdfPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "PdfPlace.example.com:8001");
        configurePlace();
    }

    public PdfPlace() throws IOException {
        super();
        configurePlace();
    }

    private static boolean isStandardInfoKey(final String key) {
        return "Title".equalsIgnoreCase(key)
                || "Author".equalsIgnoreCase(key)
                || "Subject".equalsIgnoreCase(key)
                || "Keywords".equalsIgnoreCase(key)
                || "Creator".equalsIgnoreCase(key)
                || "Producer".equalsIgnoreCase(key)
                || "CreationDate".equalsIgnoreCase(key)
                || "ModDate".equalsIgnoreCase(key)
                || "Trapped".equalsIgnoreCase(key);
    }

    private static void collectEmbeddedFileSpecs(final PDEmbeddedFilesNameTreeNode node,
            final List<PDComplexFileSpecification> target) throws IOException {
        Map<String, PDComplexFileSpecification> names = node.getNames();
        if (names != null) {
            target.addAll(names.values());
        }
        if (node.getKids() != null) {
            for (var kid : node.getKids()) {
                if (kid instanceof PDEmbeddedFilesNameTreeNode childNode) {
                    collectEmbeddedFileSpecs(childNode, target);
                }
            }
        }
    }

    @Nullable
    private static PDEmbeddedFile getEmbeddedFile(final PDComplexFileSpecification spec) {
        if (spec == null) {
            return null;
        }
        PDEmbeddedFile ef = spec.getEmbeddedFileUnicode();
        if (ef == null) {
            ef = spec.getEmbeddedFile();
        }
        return ef;
    }

    private static String childBaseName(final IBaseDataObject parent) {
        String name = trimToNull(parent.shortName());
        if (name == null) {
            name = trimToNull(parent.getFilename());
        }
        return name != null ? name : "pdf";
    }

    private static void putIfNotBlank(final IBaseDataObject payload, final String key, @Nullable final String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            payload.putParameter(key, trimmed);
        }
    }

    private static void putDateIfPresent(final IBaseDataObject payload, final String key, @Nullable final Calendar calendar) {
        if (calendar != null) {
            payload.putParameter(key, calendar.toInstant().toString());
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
            this.maxPages = Math.max(1, configG.findIntEntry("MAX_PAGES", 2000));
            this.sortByPosition = configG.findBooleanEntry("SORT_BY_POSITION", false);
            this.extractEmbeddedFiles = configG.findBooleanEntry("EXTRACT_EMBEDDED_FILES", true);
        }
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(
                Form.PDF_PROCESSED,
                Form.PDF + Form.SUFFIXES_PASSWORD_PROTECTED,
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

        payload.setFileTypeIfEmpty(Form.PDF);

        try (PDDocument document = Loader.loadPDF(payload.data())) {
            extractMetadata(document, payload);

            extractTextChild(document, payload, children);

            if (this.extractEmbeddedFiles) {
                extractEmbeddedFileChildren(document, payload, children);
            }

            payload.setCurrentForm(Form.PDF_PROCESSED);
        } catch (InvalidPasswordException e) {
            logger.warn("PDF {} is password-protected", payload.shortName());
            payload.putParameter("PDF_ENCRYPTED", "true");
            payload.setCurrentForm(Form.PDF + Form.SUFFIXES_PASSWORD_PROTECTED);
        } catch (Exception e) {
            children.clear();
            logger.error("PdfPlace failed to parse {}", payload.shortName(), e);
            payload.addProcessingError("PdfPlace: " + e.getMessage());
            payload.setCurrentForm(Form.ERROR);
        }

        return children;
    }

    private void extractMetadata(final PDDocument document, final IBaseDataObject payload) {
        int pageCount = document.getNumberOfPages();
        payload.putParameter("PDF_PAGE_COUNT", Integer.toString(pageCount));
        payload.putParameter("PDF_VERSION", Float.toString(document.getVersion()));
        payload.putParameter("PDF_ENCRYPTED", Boolean.toString(document.isEncrypted()));

        PDDocumentInformation info = document.getDocumentInformation();
        if (info == null) {
            return;
        }

        putIfNotBlank(payload, "PDF_TITLE", info.getTitle());
        putIfNotBlank(payload, "PDF_AUTHOR", info.getAuthor());
        putIfNotBlank(payload, "PDF_SUBJECT", info.getSubject());
        putIfNotBlank(payload, "PDF_KEYWORDS", info.getKeywords());
        putIfNotBlank(payload, "PDF_CREATOR", info.getCreator());
        putIfNotBlank(payload, "PDF_PRODUCER", info.getProducer());
        putDateIfPresent(payload, "PDF_CREATION_DATE", info.getCreationDate());
        putDateIfPresent(payload, "PDF_MODIFICATION_DATE", info.getModificationDate());

        for (COSName key : info.getCOSObject().keySet()) {
            String name = key.getName();
            if (!isStandardInfoKey(name)) {
                String customVal = info.getCustomMetadataValue(name);
                putIfNotBlank(payload, "PDF_CUSTOM_" + name.toUpperCase(), customVal);
            }
        }
    }

    private void extractTextChild(final PDDocument document, final IBaseDataObject parent,
            final List<IBaseDataObject> children) throws IOException {
        int totalPages = document.getNumberOfPages();
        if (totalPages <= 0) {
            return;
        }

        int pagesToExtract = Math.min(totalPages, this.maxPages);
        if (totalPages > this.maxPages) {
            parent.putParameter("PDF_TEXT_TRUNCATED_AT_PAGE", Integer.toString(this.maxPages));
        }

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(this.sortByPosition);
        stripper.setStartPage(1);
        stripper.setEndPage(pagesToExtract);

        String text = stripper.getText(document);
        if (text == null || text.isBlank()) {
            return;
        }

        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        String childName = childBaseName(parent) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
        IBaseDataObject textChild = DataObjectFactory.getInstance(textBytes, childName, Form.TXT);
        textChild.setFileType(Form.TXT);
        textChild.setFontEncoding(CHARSET_UTF_8);
        textChild.putParameter("SOURCE_FORMAT", Form.PDF);
        textChild.putParameter("EXTRACTED_PART_TYPE", "DOCUMENT_TEXT");
        children.add(textChild);
    }

    private void extractEmbeddedFileChildren(final PDDocument document, final IBaseDataObject parent,
            final List<IBaseDataObject> children) throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        if (catalog == null) {
            return;
        }
        PDDocumentNameDictionary names = catalog.getNames();
        if (names == null) {
            return;
        }
        PDEmbeddedFilesNameTreeNode efTree = names.getEmbeddedFiles();
        if (efTree == null) {
            return;
        }

        List<PDComplexFileSpecification> specs = new ArrayList<>();
        collectEmbeddedFileSpecs(efTree, specs);

        int embeddedCount = 0;
        for (PDComplexFileSpecification spec : specs) {
            PDEmbeddedFile embeddedFile = getEmbeddedFile(spec);
            if (embeddedFile == null) {
                continue;
            }
            byte[] bytes = embeddedFile.toByteArray();
            if (bytes == null || bytes.length == 0) {
                continue;
            }

            String filename = trimToNull(spec.getFileUnicode());
            if (filename == null) {
                filename = trimToNull(spec.getFilename());
            }
            if (filename == null) {
                filename = trimToNull(spec.getFile());
            }

            String childName = childBaseName(parent) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
            IBaseDataObject child = DataObjectFactory.getInstance(bytes, childName, Form.UNKNOWN);
            child.putParameter("SOURCE_FORMAT", Form.PDF);
            child.putParameter("EXTRACTED_PART_TYPE", "EMBEDDED_FILE");
            if (filename != null) {
                child.putParameter("ATTACHMENT_FILENAME", filename);
            }
            if (embeddedFile.getSubtype() != null) {
                child.putParameter("ATTACHMENT_DECLARED_MIME", embeddedFile.getSubtype());
            }
            children.add(child);
            embeddedCount++;
        }

        if (embeddedCount > 0) {
            parent.putParameter("PDF_EMBEDDED_FILE_COUNT", Integer.toString(embeddedCount));
        }
    }
}
