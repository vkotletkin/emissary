package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;

import jakarta.annotation.Nullable;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParseRecord;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.txt.TXTParser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ToTextContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Универсальный фоллбэк-обработчик на фазе {@code TRANSFORM} (на базе Apache Tika {@link AutoDetectParser}) с высоким
 * {@code SERVICE_COST}.
 * <ul>
 * <li>Принимает любые объекты ({@code SERVICE_PROXY = "*"}), для которых не нашлось специализированного модуля с
 * меньшей стоимостью (включая нераспознанные {@link Form#UNKNOWN}).</li>
 * <li>За один проход извлекает текст тела документа через Tika (а если тип не распознан, но тело является текстовым —
 * дополнительно извлекает текст через {@link TXTParser}) и порождает дочерний объект с формой {@link Form#TXT}, который
 * далее проходит очистку в {@link TextCleanPlace} и анализ в {@link emissary.analyze.TextAnalyzePlace}.</li>
 * <li>Извлекает все вложенные файлы/архивные записи через {@link EmbeddedDocumentExtractor} с защитой по лимиту
 * количества и суммарного объёма байт и отправляет их как дочерние объекты с формой {@link Form#UNKNOWN}.</li>
 * <li>Записывает метаданные Tika на родительский объект и переводит его в форму {@code <FORM>-PROCESSED}, исключая
 * повторное попадание в {@code TRANSFORM}.</li>
 * </ul>
 */
public class TikaTransformPlace extends ServiceProviderPlace {

    private static final String ATTACHMENT_CHILD_SUFFIX = "-att-";
    private static final String TEXT_CHILD_SUFFIX = "-txt-";
    private static final String CHARSET_UTF_8 = "UTF-8";
    private static final String TIKA_TRUNCATED_PARAM = "TIKA_TRUNCATED";
    private static final String TIKA_TEXT_TRUNCATED_PARAM = "TIKA_TEXT_TRUNCATED";
    private static final String PROCESSING_ERROR_PREFIX = "TikaTransformPlace: ";
    private static final String POI_ENCRYPTED_EXCEPTION = "org.apache.poi.EncryptedDocumentException";
    private static final String PDFBOX_INVALID_PASSWORD_EXCEPTION = "org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException";
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final int TEXT_SAMPLE_BYTES = 8192;
    /**
     * Доля подозрительных управляющих байтов не больше 1/20 (5%).
     */
    private static final int SUSPICIOUS_CONTROL_BUDGET = 20;
    private static final int MAX_DERIVED_FORM_LENGTH = 64;
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]+");
    private static final Set<String> TERMINAL_FORMS = Set.of(
            Form.TXT,
            Form.TEXT,
            Form.EMPTY,
            Form.ERROR,
            Form.DONE);
    private static final Set<String> GENERIC_DETECTOR_MIMES = Set.of(
            "application/octet-stream",
            "application/x-tika-msoffice",
            "application/x-tika-ooxml",
            "application/x-tika-ooxml-protected");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp", "ico", "heic", "heif", "svg", "emf", "wmf");
    private static final List<String> DEFAULT_FILENAME_PARAM_KEYS = List.of(
            "ATTACHMENT_FILENAME",
            "ARCHIVE_ENTRY_NAME",
            "Original-Filename",
            "FILE_NAME");

    private static final Map<String, String> DEFAULT_MIME_TO_FORM = Map.ofEntries(
            Map.entry("message/rfc822", Form.EML),
            Map.entry("application/mbox", Form.MBOX),
            Map.entry("application/vnd.ms-outlook", Form.MSG),
            Map.entry("application/vnd.ms-tnef", Form.TNEF),
            Map.entry("application/zip", Form.ZIP),
            Map.entry("application/x-tar", Form.TAR),
            Map.entry("application/x-gtar", Form.TAR),
            Map.entry("application/gzip", Form.GZIP),
            Map.entry("application/x-gzip", Form.GZIP),
            Map.entry("application/x-bzip2", Form.BZIP2),
            Map.entry("application/x-xz", Form.XZ),
            Map.entry("application/x-7z-compressed", Form.SEVENZ),
            Map.entry("application/x-rar-compressed", Form.RAR),
            Map.entry("application/vnd.rar", Form.RAR),
            Map.entry("application/zstd", Form.ZSTD),
            Map.entry("application/java-archive", Form.JAR),
            Map.entry("application/pdf", Form.PDF),
            Map.entry("application/postscript", Form.PS),
            Map.entry("application/epub+zip", Form.EPUB),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Form.DOCX),
            Map.entry("application/msword", Form.DOC),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Form.XLSX),
            Map.entry("application/vnd.ms-excel.sheet.binary.macroenabled.12", Form.XLSB),
            Map.entry("application/vnd.ms-excel", Form.XLS),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", Form.PPTX),
            Map.entry("application/vnd.ms-powerpoint", Form.PPT),
            Map.entry("application/vnd.oasis.opendocument.text", Form.ODT),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", Form.ODS),
            Map.entry("application/vnd.oasis.opendocument.presentation", Form.ODP),
            Map.entry("application/rtf", Form.RTF),
            Map.entry("text/rtf", Form.RTF),
            Map.entry("text/plain", Form.TXT),
            Map.entry("text/csv", Form.CSV),
            Map.entry("text/tab-separated-values", Form.TSV),
            Map.entry("text/html", Form.HTML),
            Map.entry("application/xhtml+xml", Form.HTML),
            Map.entry("application/xml", Form.XML),
            Map.entry("text/xml", Form.XML),
            Map.entry("application/json", Form.JSON),
            Map.entry("text/x-yaml", Form.YAML),
            Map.entry("application/yaml", Form.YAML),
            Map.entry("text/calendar", Form.ICS),
            Map.entry("text/vcard", Form.VCARD),
            Map.entry("application/x-sqlite3", Form.SQLITE),
            Map.entry("application/vnd.sqlite3", Form.SQLITE),
            Map.entry("application/x-msaccess", Form.MDB));

    protected AutoDetectParser parser;
    protected TXTParser txtFallbackParser;
    protected int maxTextChars = 10_000_000;
    protected boolean extractEmbeddedAttachments = true;
    protected boolean skipImageAttachments = false;
    protected boolean detectTextFallback = true;
    protected boolean extractMetadata = true;
    protected int maxAttachments = 500;
    protected long maxTotalAttachmentBytes = 100L * 1024 * 1024;
    protected int maxMetadataKeys = 100;
    protected int maxMetadataValueLength = 2000;
    protected List<String> filenameParamKeys = DEFAULT_FILENAME_PARAM_KEYS;

    public TikaTransformPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
        configurePlace();
    }

    public TikaTransformPlace(final InputStream configStream) throws IOException {
        super(configStream);
        configurePlace();
    }

    public TikaTransformPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
        configurePlace();
    }

    public TikaTransformPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "TikaTransformPlace.example.com:8001");
        configurePlace();
    }

    public TikaTransformPlace() throws IOException {
        super();
        configurePlace();
    }

    private static boolean copyBounded(final InputStream in, final ByteArrayOutputStream out, final long maxBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            // Сравнение через остаток, чтобы total + read не переполнил long на огромном лимите.
            if (read > maxBytes - total) {
                return false;
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return true;
    }

    private static boolean isEncryptedException(@Nullable final Throwable throwable) {
        Throwable curr = throwable;
        while (curr != null) {
            if (curr instanceof EncryptedDocumentException || isNamedEncryptionFailure(curr)) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    private static boolean isNamedEncryptionFailure(final Throwable throwable) {
        String className = throwable.getClass().getName();
        return POI_ENCRYPTED_EXCEPTION.equals(className) || PDFBOX_INVALID_PASSWORD_EXCEPTION.equals(className);
    }

    /**
     * Проверяет, выглядит ли буфер байтов как читаемый текст: нет нулевых байтов, а подозрительных управляющих символов в
     * первых {@value #TEXT_SAMPLE_BYTES} байтах не больше 5%.
     */
    static boolean looksLikeReadableText(final byte[] data) {
        if (data.length == 0) {
            return false;
        }
        int sampleLen = Math.min(data.length, TEXT_SAMPLE_BYTES);
        int suspiciousControlCount = 0;
        for (int i = 0; i < sampleLen; i++) {
            int unsignedByte = data[i] & 0xFF;
            if (unsignedByte == 0x00) {
                return false;
            }
            if (isSuspiciousControl(unsignedByte)) {
                suspiciousControlCount++;
            }
        }
        return suspiciousControlCount * SUSPICIOUS_CONTROL_BUDGET <= sampleLen;
    }

    private static boolean isSuspiciousControl(final int unsignedByte) {
        if (unsignedByte == 0x7F) {
            return true;
        }
        // ESC оставляем: он встречается в ANSI-тексте, а не только в бинарных форматах.
        if (unsignedByte == 0x1B) {
            return false;
        }
        return unsignedByte < 0x09 || (unsignedByte > 0x0D && unsignedByte < 0x20);
    }

    private static boolean isTextLikeMime(@Nullable final String baseMime) {
        if (baseMime == null) {
            return false;
        }
        String lower = baseMime.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
                || lower.endsWith("+json")
                || lower.endsWith("+xml")
                || "application/json".equals(lower)
                || "application/x-ndjson".equals(lower)
                || "application/xml".equals(lower)
                || "application/yaml".equals(lower)
                || "application/x-yaml".equals(lower)
                || "application/sql".equals(lower)
                || "application/javascript".equals(lower)
                || "application/x-sh".equals(lower);
    }

    @Nullable
    private static String normalizeBaseMime(@Nullable final String mimeString) {
        String trimmed = trimToNull(mimeString);
        if (trimmed == null) {
            return null;
        }
        MediaType mediaType = MediaType.parse(trimmed);
        return mediaType != null ? mediaType.getBaseType().toString() : trimmed;
    }

    private static String resolveBaseForm(final IBaseDataObject payload, @Nullable final String baseMime) {
        String currentForm = trimToNull(payload.currentForm());
        if (currentForm != null && !Form.UNKNOWN.equalsIgnoreCase(currentForm)) {
            return currentForm;
        }
        String fileType = trimToNull(payload.getFileType());
        if (fileType != null && !Form.UNKNOWN.equalsIgnoreCase(fileType)) {
            return fileType;
        }
        if (baseMime != null) {
            String mapped = DEFAULT_MIME_TO_FORM.get(baseMime.toLowerCase(Locale.ROOT));
            if (mapped != null) {
                return mapped;
            }
            if (!GENERIC_DETECTOR_MIMES.contains(baseMime.toLowerCase(Locale.ROOT))) {
                MediaType mediaType = MediaType.parse(baseMime);
                String subType = mediaType != null ? mediaType.getSubtype() : baseMime;
                String candidate = toAlphanumericToken(subType);
                if (candidate != null) {
                    return candidate.length() <= MAX_DERIVED_FORM_LENGTH
                            ? candidate
                            : candidate.substring(0, MAX_DERIVED_FORM_LENGTH);
                }
            }
        }
        return Form.UNKNOWN;
    }

    private static String childBaseName(final IBaseDataObject parent) {
        String name = trimToNull(parent.shortName());
        if (name == null) {
            name = trimToNull(parent.getFilename());
        }
        return name != null ? name : "tika";
    }

    @Nullable
    static String extractFileName(@Nullable final String path) {
        String trimmed = trimToNull(path);
        if (trimmed == null) {
            return null;
        }
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        trimmed = trimToNull(trimmed);
        if (trimmed == null) {
            return null;
        }
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    @Nullable
    private static String trimToNull(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Оставляет в токене только {@code A-Z} и цифры. Пустая строка и строка из одних подчёркиваний дают {@code null}, чтобы
     * разные не-ASCII ключи не схлопывались в один параметр.
     */
    @Nullable
    private static String toAlphanumericToken(@Nullable final String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String token = NON_ALNUM.matcher(trimmed.toUpperCase(Locale.ROOT)).replaceAll("_");
        int start = 0;
        int end = token.length();
        while (start < end && token.charAt(start) == '_') {
            start++;
        }
        while (end > start && token.charAt(end - 1) == '_') {
            end--;
        }
        return start == end ? null : token.substring(start, end);
    }

    private static boolean textWasTruncated(final ParseContext context) {
        ParseRecord record = context.get(ParseRecord.class);
        return record != null && record.isWriteLimitReached();
    }

    private static boolean isSkippedMetadataKey(@Nullable final String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        return name.regionMatches(true, 0, "X-TIKA:", 0, "X-TIKA:".length())
                || "Content-Type".equalsIgnoreCase(name)
                || "resourceName".equalsIgnoreCase(name)
                || "Content-Length".equalsIgnoreCase(name);
    }

    private static boolean isImageAttachment(final Metadata metadata) {
        String baseMime = normalizeBaseMime(metadata.get(HttpHeaders.CONTENT_TYPE));
        if (baseMime != null && baseMime.startsWith("image/")) {
            return true;
        }
        String fileName = extractFileName(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
        if (fileName == null) {
            fileName = extractFileName(metadata.get(TikaCoreProperties.ORIGINAL_RESOURCE_NAME));
        }
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    protected void configurePlace() {
        if (configG != null) {
            this.maxTextChars = Math.max(1024, configG.findIntEntry("MAX_TEXT_CHARS", 10_000_000));
            this.extractEmbeddedAttachments = configG.findBooleanEntry("EXTRACT_EMBEDDED_ATTACHMENTS", true);
            this.skipImageAttachments = configG.findBooleanEntry("SKIP_IMAGE_ATTACHMENTS", false);
            this.detectTextFallback = configG.findBooleanEntry("DETECT_TEXT_FALLBACK", true);
            this.extractMetadata = configG.findBooleanEntry("EXTRACT_METADATA", true);
            this.maxAttachments = Math.max(1, configG.findIntEntry("MAX_ATTACHMENTS", 500));
            this.maxTotalAttachmentBytes = Math.max(1024L, configG.findLongEntry("MAX_TOTAL_ATTACHMENT_BYTES", 100L * 1024 * 1024));
            this.maxMetadataKeys = Math.max(1, configG.findIntEntry("MAX_METADATA_KEYS", 100));
            this.maxMetadataValueLength = Math.max(64, configG.findIntEntry("MAX_METADATA_VALUE_LENGTH", 2000));
            List<String> configuredKeys = new ArrayList<>();
            for (String key : configG.findEntries("FILENAME_HINT_PARAM")) {
                String trimmed = trimToNull(key);
                if (trimmed != null) {
                    configuredKeys.add(trimmed);
                }
            }
            this.filenameParamKeys = configuredKeys.isEmpty() ? DEFAULT_FILENAME_PARAM_KEYS : List.copyOf(configuredKeys);
        } else {
            this.filenameParamKeys = DEFAULT_FILENAME_PARAM_KEYS;
        }
        this.parser = new AutoDetectParser();
        this.txtFallbackParser = new TXTParser();
    }

    @Override
    public boolean isDenied(final String form) {
        if (form == null || form.isBlank()) {
            return true;
        }
        String upper = form.trim().toUpperCase(Locale.ROOT);
        return super.isDenied(form)
                || TERMINAL_FORMS.contains(upper)
                || upper.endsWith(Form.SUFFIXES_PROCESSED)
                || upper.endsWith(Form.SUFFIXES_PASSWORD_PROTECTED)
                || upper.endsWith(Form.SUFFIXES_BROKEN)
                || upper.endsWith(Form.SUFFIXES_ERROR)
                || upper.endsWith(Form.SUFFIXES_UNWRAPPED);
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(
                Form.UNKNOWN + Form.SUFFIXES_PROCESSED,
                Form.TXT,
                Form.UNKNOWN,
                Form.EMPTY,
                Form.ERROR);
    }

    @Override
    protected boolean sproutsChildren() {
        return true;
    }

    @Nullable
    protected String resolveFilenameHint(final IBaseDataObject payload) {
        for (String key : this.filenameParamKeys) {
            String value = trimToNull(payload.getParameterAsString(key));
            if (value != null) {
                return value;
            }
        }
        String filename = trimToNull(payload.getFilename());
        if (filename != null) {
            return filename;
        }
        return trimToNull(payload.shortName());
    }

    @Override
    public List<IBaseDataObject> processHeavyDuty(final IBaseDataObject payload) throws ResourceException {

        List<IBaseDataObject> children = new ArrayList<>();

        if (payload.dataLength() == 0) {
            payload.setCurrentForm(Form.EMPTY);
            return children;
        }

        byte[] data = payload.data();

        // Race over-condition statement. Payload data length must correctly filter data
        if (data == null || data.length == 0) {
            addProcessingError(payload, "payload data is unavailable");
            payload.setCurrentForm(Form.ERROR);
            return children;
        }

        // Get filename for hint on detector
        Metadata metadata = new Metadata();
        String filenameHint = resolveFilenameHint(payload);
        if (filenameHint != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filenameHint);
        }

        // Tika initialize
        ParseContext context = new ParseContext();
        BodyContentHandler bodyHandler = newBodyHandler(context);
        List<EmbeddedItem> embeddedItems = new ArrayList<>();
        AttachmentCollectorExtractor extractor = new AttachmentCollectorExtractor(payload, embeddedItems);
        context.set(EmbeddedDocumentExtractor.class, extractor);
        context.set(Parser.class, this.parser);

        Exception parseFailure = null;
        boolean textTruncated;
        try {
            textTruncated = parseWithTika(data, metadata, bodyHandler, context);
        } catch (ResourceException e) {
            throw e;
        } catch (Exception e) {
            if (isEncryptedException(e)) {
                markPasswordProtected(payload, metadata);
                return children;
            }
            logger.error("TikaTransformPlace failed to parse {}", payload.shortName(), e);
            addProcessingError(payload, e);
            parseFailure = e;
            textTruncated = textWasTruncated(context);
        }
        if (textTruncated) {
            noteTextTruncated(payload);
        }

        String baseMime = normalizeBaseMime(metadata.get(Metadata.CONTENT_TYPE));
        if (baseMime != null) {
            payload.putParameter("TIKA_MIME_TYPE", baseMime);
        }

        String baseForm = resolveBaseForm(payload, baseMime);
        payload.setFileTypeIfEmpty(baseForm);

        if (this.extractMetadata) {
            populateMetadataParameters(metadata, payload);
        }

        String bodyText = trimToNull(bodyHandler.toString());
        if (bodyText == null && embeddedItems.isEmpty() && this.detectTextFallback) {
            bodyText = tryExtractFallbackText(data, baseMime, payload);
        }

        if (bodyText != null) {
            children.add(createTextChild(bodyText, baseForm, payload));
            payload.putParameter("TIKA_EXTRACTED_TEXT", "true");
        } else {
            payload.putParameter("TIKA_EXTRACTED_TEXT", "false");
        }

        for (EmbeddedItem item : embeddedItems) {
            children.add(createAttachmentChild(item, baseForm, payload, children.size() + 1));
        }

        payload.putParameter("TIKA_ATTACHMENT_COUNT", Integer.toString(embeddedItems.size()));
        if (extractor.totalUnpackedBytes > 0) {
            payload.putParameter("TIKA_TOTAL_ATTACHMENT_BYTES", Long.toString(extractor.totalUnpackedBytes));
        }

        // Пустой результат после исключения — ошибка. Если текст или вложения уже собраны, отдаём их
        // и помечаем усечение, а не выбрасываем частичный разбор.
        if (parseFailure != null && children.isEmpty()) {
            payload.setCurrentForm(Form.ERROR);
            return children;
        }
        if (parseFailure != null) {
            payload.putParameter(TIKA_TRUNCATED_PARAM, "PARSE_FAILED");
        }

        // Если Tika смогла превратить нераспознанный текст в дочерний TXT, но MIME был octet-stream,
        // помечаем тип родителя как TXT, если он оставался UNKNOWN.
        if (Form.UNKNOWN.equalsIgnoreCase(baseForm) && bodyText != null && embeddedItems.isEmpty()) {
            payload.setFileType(Form.TXT);
            payload.setCurrentForm(Form.TXT + Form.SUFFIXES_PROCESSED);
        } else {
            payload.setCurrentForm(baseForm + Form.SUFFIXES_PROCESSED);
        }

        return children;
    }

    private void markPasswordProtected(final IBaseDataObject payload, final Metadata metadata) {
        String baseMime = normalizeBaseMime(metadata.get(Metadata.CONTENT_TYPE));
        String baseForm = resolveBaseForm(payload, baseMime);
        payload.setFileTypeIfEmpty(baseForm);
        if (baseMime != null) {
            payload.putParameter("TIKA_MIME_TYPE", baseMime);
        }
        logger.warn("Document {} is password-protected", payload.shortName());
        payload.putParameter("DOCUMENT_ENCRYPTED", "true");
        payload.setCurrentForm(baseForm + Form.SUFFIXES_PASSWORD_PROTECTED);
    }

    // Лимит символов не бросает исключение: иначе Tika прекращает parse() и теряет вложения после текста.
    private BodyContentHandler newBodyHandler(final ParseContext context) {
        if (context.get(ParseRecord.class) == null) {
            context.set(ParseRecord.class, new ParseRecord());
        }
        WriteOutContentHandler limited = new WriteOutContentHandler(
                new ToTextContentHandler(), this.maxTextChars, false, context);
        return new BodyContentHandler(limited);
    }

    private void noteTextTruncated(final IBaseDataObject payload) {
        if ("true".equals(payload.getParameterAsString(TIKA_TEXT_TRUNCATED_PARAM))) {
            return;
        }
        payload.putParameter(TIKA_TEXT_TRUNCATED_PARAM, "true");
        logger.warn("TikaTransformPlace reached MAX_TEXT_CHARS ({}) on {}", this.maxTextChars, payload.shortName());
    }

    private boolean parseWithTika(final byte[] data, final Metadata metadata,
                                  final BodyContentHandler bodyHandler, final ParseContext context) throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(data, metadata)) {
            this.parser.parse(tis, bodyHandler, metadata, context);
            return textWasTruncated(context);
        } catch (Exception e) {
            if (WriteLimitReachedException.isWriteLimitReached(e) || textWasTruncated(context)) {
                return true;
            }
            throw e;
        }
    }

    @Nullable
    private String tryExtractFallbackText(final byte[] data, @Nullable final String baseMime, final IBaseDataObject payload) {
        if (!isTextLikeMime(baseMime) && !looksLikeReadableText(data)) {
            return null;
        }
        ParseContext fallbackContext = new ParseContext();
        BodyContentHandler fallbackHandler = newBodyHandler(fallbackContext);
        Metadata fallbackMeta = new Metadata();
        try (InputStream is = new ByteArrayInputStream(data)) {
            this.txtFallbackParser.parse(is, fallbackHandler, fallbackMeta, fallbackContext);
        } catch (Exception e) {
            if (!WriteLimitReachedException.isWriteLimitReached(e) && !textWasTruncated(fallbackContext)) {
                logger.debug("Fallback TXTParser could not parse bytes as text", e);
                return null;
            }
        }
        if (textWasTruncated(fallbackContext)) {
            noteTextTruncated(payload);
        }
        return trimToNull(fallbackHandler.toString());
    }

    private IBaseDataObject createTextChild(final String text, final String sourceFormat,
                                            final IBaseDataObject parent) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        String childName = childBaseName(parent) + TEXT_CHILD_SUFFIX + 1;
        IBaseDataObject textChild = DataObjectFactory.getInstance(textBytes, childName, Form.TXT);
        textChild.setFileType(Form.TXT);
        textChild.setFontEncoding(CHARSET_UTF_8);
        textChild.putParameter("SOURCE_FORMAT", sourceFormat);
        textChild.putParameter("EXTRACTED_PART_TYPE", "DOCUMENT_TEXT");
        return textChild;
    }

    private IBaseDataObject createAttachmentChild(final EmbeddedItem item, final String sourceFormat,
                                                  final IBaseDataObject parent, final int childIndex) {
        String childName = childBaseName(parent) + ATTACHMENT_CHILD_SUFFIX + childIndex;
        IBaseDataObject child = DataObjectFactory.getInstance(item.bytes, childName, Form.UNKNOWN);
        child.putParameter("SOURCE_FORMAT", sourceFormat);
        child.putParameter("EXTRACTED_PART_TYPE", "EMBEDDED_FILE");
        if (item.filename != null) {
            child.putParameter("ATTACHMENT_FILENAME", item.filename);
        }
        if (item.contentType != null) {
            child.putParameter("ATTACHMENT_DECLARED_MIME", item.contentType);
        }
        if (item.embeddedResourceType != null) {
            child.putParameter("EMBEDDED_RESOURCE_TYPE", item.embeddedResourceType);
        }
        return child;
    }

    private void populateMetadataParameters(final Metadata metadata, final IBaseDataObject payload) {
        putMetadataIfPresent(payload, "DOC_TITLE", metadata.get(TikaCoreProperties.TITLE));
        putMetadataIfPresent(payload, "DOC_AUTHOR", metadata.get(TikaCoreProperties.CREATOR));
        putMetadataIfPresent(payload, "DOC_LAST_MODIFIED_BY", metadata.get(TikaCoreProperties.MODIFIER));
        putMetadataIfPresent(payload, "DOC_DESCRIPTION", metadata.get(TikaCoreProperties.DESCRIPTION));
        putMetadataIfPresent(payload, "DOC_CREATED", metadata.get(TikaCoreProperties.CREATED));
        putMetadataIfPresent(payload, "DOC_MODIFIED", metadata.get(TikaCoreProperties.MODIFIED));

        int storedKeys = 0;
        for (String name : metadata.names()) {
            if (storedKeys >= this.maxMetadataKeys) {
                break;
            }
            if (isSkippedMetadataKey(name)) {
                continue;
            }
            String token = toAlphanumericToken(name);
            if (token == null) {
                continue;
            }
            String sanitizedKey = "TIKA_META_" + token;
            int storedValues = 0;
            boolean stored = false;
            for (String value : metadata.getValues(name)) {
                if (storedValues >= this.maxMetadataKeys) {
                    break;
                }
                String limited = limitParameterValue(value);
                if (limited == null) {
                    continue;
                }
                payload.appendParameter(sanitizedKey, limited);
                stored = true;
                storedValues++;
            }
            if (stored) {
                storedKeys++;
            }
        }
    }

    private void putMetadataIfPresent(final IBaseDataObject payload, final String key, @Nullable final String value) {
        String limited = limitParameterValue(value);
        if (limited != null) {
            payload.putParameter(key, limited);
        }
    }

    @Nullable
    private String limitParameterValue(@Nullable final String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null || trimmed.length() <= this.maxMetadataValueLength) {
            return trimmed;
        }
        return trimmed.substring(0, this.maxMetadataValueLength);
    }

    private void addProcessingError(final IBaseDataObject payload, final String detail) {
        String message = trimToNull(detail);
        payload.addProcessingError(PROCESSING_ERROR_PREFIX + (message != null ? message : "unknown error"));
    }

    private void addProcessingError(final IBaseDataObject payload, final Exception error) {
        String message = trimToNull(error.getMessage());
        addProcessingError(payload, message != null ? message : error.getClass().getSimpleName());
    }

    private static final class EmbeddedItem {

        final byte[] bytes;
        @Nullable
        final String filename;
        @Nullable
        final String contentType;
        @Nullable
        final String embeddedResourceType;

        EmbeddedItem(final byte[] bytes, @Nullable final String filename,
                     @Nullable final String contentType, @Nullable final String embeddedResourceType) {
            this.bytes = bytes;
            this.filename = filename;
            this.contentType = contentType;
            this.embeddedResourceType = embeddedResourceType;
        }
    }

    private final class AttachmentCollectorExtractor implements EmbeddedDocumentExtractor {
        private final IBaseDataObject parentPayload;
        private final List<EmbeddedItem> items;
        private long totalUnpackedBytes;
        private int attemptedCount;
        private boolean truncated;

        AttachmentCollectorExtractor(final IBaseDataObject parentPayload, final List<EmbeddedItem> items) {
            this.parentPayload = parentPayload;
            this.items = items;
        }

        @Override
        public boolean shouldParseEmbedded(final Metadata metadata) {
            if (!TikaTransformPlace.this.extractEmbeddedAttachments || this.truncated) {
                return false;
            }
            return !TikaTransformPlace.this.skipImageAttachments || !isImageAttachment(metadata);
        }

        @Override
        public void parseEmbedded(final InputStream stream, final ContentHandler handler,
                                  final Metadata metadata, final boolean outputHtml) throws IOException {

            if (!shouldParseEmbedded(metadata)) {
                return;
            }

            this.attemptedCount++;
            if (this.attemptedCount > TikaTransformPlace.this.maxAttachments) {
                addProcessingError(this.parentPayload,
                        "exceeded MAX_ATTACHMENTS (" + TikaTransformPlace.this.maxAttachments + ")");
                this.parentPayload.putParameter(TIKA_TRUNCATED_PARAM, "MAX_ATTACHMENTS_REACHED");
                this.truncated = true;
                return;
            }

            long remainingBudget = TikaTransformPlace.this.maxTotalAttachmentBytes - this.totalUnpackedBytes;
            if (remainingBudget <= 0) {
                addProcessingError(this.parentPayload,
                        "exceeded MAX_TOTAL_ATTACHMENT_BYTES (" + TikaTransformPlace.this.maxTotalAttachmentBytes + ")");
                this.parentPayload.putParameter(TIKA_TRUNCATED_PARAM, "MAX_BYTES_REACHED");
                this.truncated = true;
                return;
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            // Остаток записи не дочитываем: это и есть защита от архивной бомбы.
            if (!copyBounded(stream, buffer, remainingBudget)) {
                addProcessingError(this.parentPayload, "embedded attachment exceeded remaining byte budget");
                this.parentPayload.putParameter(TIKA_TRUNCATED_PARAM, "MAX_BYTES_REACHED");
                this.truncated = true;
                return;
            }

            byte[] bytes = buffer.toByteArray();
            if (bytes.length == 0) {
                return;
            }

            this.totalUnpackedBytes += bytes.length;

            String rawName = trimToNull(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
            if (rawName == null) {
                rawName = trimToNull(metadata.get(TikaCoreProperties.ORIGINAL_RESOURCE_NAME));
            }

            String filename = limitParameterValue(extractFileName(rawName));
            String contentType = limitParameterValue(normalizeBaseMime(metadata.get(Metadata.CONTENT_TYPE)));
            String resourceType = limitParameterValue(metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE));

            this.items.add(new EmbeddedItem(bytes, filename, contentType, resourceType));
            String limitedName = limitParameterValue(rawName);
            if (limitedName != null) {
                this.parentPayload.appendParameter("TIKA_ATTACHMENT_NAMES", limitedName);
            }
        }
    }
}
