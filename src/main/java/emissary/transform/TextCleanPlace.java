package emissary.transform;

import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Очищает и нормализует сырой текст ({@code TXT}) и переводит форму в {@link Form#TEXT} ({@code TEXT}), сохраняя
 * {@code fileType = TXT}.
 * <ul>
 * <li>Удаляет BOM, невидимые/управляющие символы и zero-width мусор.</li>
 * <li>Нормализует переводы строк ({@code \r\n}, {@code \r}, {@code \u2028}, {@code \u2029} -&gt; {@code \n}).</li>
 * <li>Заменяет неразрывные и экзотические пробелы на обычный пробел, схлопывает длинные цепочки пробелов/табов.</li>
 * <li>Схлопывает гигантские серии пустых строк до {@code MAX_CONSECUTIVE_NEWLINES} (по умолчанию 2).</li>
 * <li>Схлопывает длинные псевдографические разделители ({@code ------}, {@code ======}, {@code ******}).</li>
 * <li>Применяет Unicode NFC нормализацию.</li>
 * </ul>
 */
public class TextCleanPlace extends ServiceProviderPlace {

    private static final String CHARSET_UTF_8 = StandardCharsets.UTF_8.name();

    // Zero-width spaces, BOM, soft hyphen, bidi marks, etc.
    private static final Pattern ZERO_WIDTH_AND_FORMAT_CHARS =
            Pattern.compile("[\\uFEFF\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFFF9-\\uFFFD]");

    // Control chars except \t (\x09) and \n (\x0A)
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F]");

    // Various Unicode spaces (NBSP, thin space, ideographic space, etc.) and tabs
    private static final Pattern UNICODE_HORIZONTAL_SPACES =
            Pattern.compile("[\\t\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]");

    // Находит один или более горизонтальных пробелов (пробелы, табы) перед переносом строки
    private static final Pattern TRAILING_LINE_SPACES = Pattern.compile("\\h+(?=\\n)");

    // Repeated horizontal spaces (2 or more)
    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" {2,}");

    // Использование обратной ссылки \1 гарантирует, что все 10+ символов будут одинаковыми
    private static final Pattern GIANT_SEPARATOR_RUNS = Pattern.compile("([\\-=*_~#])\\1{9,}");


    protected int maxConsecutiveNewlines = 2;
    protected boolean normalizeUnicodeNfc = true;
    protected boolean collapseSpaces = true;
    protected boolean collapseSeparatorRuns = true;

    private Pattern excessiveNewlinesPattern = buildExcessiveNewlinesPattern(2);

    public TextCleanPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
        configurePlace();
    }

    public TextCleanPlace(final InputStream configStream) throws IOException {
        super(configStream);
        configurePlace();
    }

    public TextCleanPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
        configurePlace();
    }

    public TextCleanPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "TextCleanPlace.example.com:8001");
        configurePlace();
    }

    public TextCleanPlace() throws IOException {
        super();
        configurePlace();
    }

    private static Pattern buildExcessiveNewlinesPattern(final int maxNewlines) {
        int threshold = Math.max(1, maxNewlines) + 1;
        return Pattern.compile("\\n{" + threshold + ",}");
    }

    private static Charset resolveCharset(final IBaseDataObject payload) {
        String encoding = payload.getFontEncoding();
        if (encoding == null || encoding.isBlank()) {
            encoding = payload.getParameterAsString("BODY_CHARSET");
        }
        if (encoding != null && !encoding.isBlank()) {
            try {
                return Charset.forName(encoding.trim());
            } catch (IllegalArgumentException ignored) {
                // fallback to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    protected void configurePlace() {
        if (configG != null) {
            this.maxConsecutiveNewlines = Math.max(1, configG.findIntEntry("MAX_CONSECUTIVE_NEWLINES", 2));
            this.normalizeUnicodeNfc = configG.findBooleanEntry("NORMALIZE_UNICODE_NFC", true);
            this.collapseSpaces = configG.findBooleanEntry("COLLAPSE_SPACES", true);
            this.collapseSeparatorRuns = configG.findBooleanEntry("COLLAPSE_SEPARATOR_RUNS", true);
        }
        this.excessiveNewlinesPattern = buildExcessiveNewlinesPattern(this.maxConsecutiveNewlines);
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(Form.TEXT, Form.EMPTY, Form.ERROR);
    }

    @Override
    public void process(final IBaseDataObject payload) throws ResourceException {

        if (payload.dataLength() == 0) {
            payload.setCurrentForm(Form.EMPTY);
            return;
        }

        int originalByteLength = payload.dataLength();
        Charset sourceCharset = resolveCharset(payload);
        String rawText = new String(payload.data(), sourceCharset);

        String cleanedText = cleanText(rawText);
        if (cleanedText.isEmpty()) {
            payload.setCurrentForm(Form.EMPTY);
            return;
        }

        byte[] cleanedBytes = cleanedText.getBytes(StandardCharsets.UTF_8);

        payload.setData(cleanedBytes);
        payload.setFontEncoding(CHARSET_UTF_8);

        payload.setFileTypeIfEmpty(Form.TXT);
        payload.putParameter("TEXT_ORIGINAL_BYTE_LENGTH", Integer.toString(originalByteLength));
        payload.putParameter("TEXT_CLEANED_BYTE_LENGTH", Integer.toString(cleanedBytes.length));
        payload.putParameter("TEXT_CHAR_COUNT", Integer.toString(cleanedText.length()));
        payload.replaceCurrentForm(Form.TEXT);
    }

    public String cleanText(final String input) {

        if (input == null || input.isEmpty()) {
            return "";
        }

        String s = input;

        if (this.normalizeUnicodeNfc) {
            s = Normalizer.normalize(s, Normalizer.Form.NFC);
        }

        // 1. Нормализуем все виды переводов строк (\r\n, \r, Unicode line/paragraph separators) в \n
        s = s.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u0085', '\n')
                .replace('\u2028', '\n')
                .replace('\u2029', '\n');

        // 2. Убираем zero-width, BOM, bidi-маркеры и управляющий мусор
        s = ZERO_WIDTH_AND_FORMAT_CHARS.matcher(s).replaceAll("");
        s = CONTROL_CHARS.matcher(s).replaceAll(" ");

        // 3. Приводим все горизонтальные пробельные символы и табы к обычному пробелу
        s = UNICODE_HORIZONTAL_SPACES.matcher(s).replaceAll(" ");

        // 4. Схлопываем гигантские псевдографические разделители (10+ символов подряд -> 3)
        if (this.collapseSeparatorRuns) {
            s = GIANT_SEPARATOR_RUNS.matcher(s).replaceAll("$1$1$1");
        }

        // 5. Схлопываем повторяющиеся пробелы и хвостовые пробелы перед переводом строки
        if (this.collapseSpaces) {
            s = MULTIPLE_SPACES.matcher(s).replaceAll(" ");
        }
        s = TRAILING_LINE_SPACES.matcher(s).replaceAll("");

        // 6. Убираем пробелы в начале каждой строки после перевода
        s = s.replace("\n ", "\n");

        // 7. Схлопываем гигантские переносы строк до maxConsecutiveNewlines
        String replacementNewlines = "\n".repeat(Math.max(1, this.maxConsecutiveNewlines));
        s = this.excessiveNewlinesPattern.matcher(s).replaceAll(replacementNewlines);

        return s.strip();
    }
}
