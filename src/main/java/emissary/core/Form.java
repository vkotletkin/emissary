package emissary.core;

/**
 * Keep a list of common current form and filetype values These could be built into some ontological interrelated
 * monstrosity in the future.
 */
public class Form {

    // =========================================================================
    // Системные и служебные формы жизненного цикла в Emissary
    // =========================================================================

    /** Неизвестная форма (начальное состояние до прохождения стадии ID) */
    public static final String UNKNOWN = "UNKNOWN";

    /** Ошибка обработки объекта (маршрутизируется в обработчики ошибок / финальный вывод) */
    public static final String ERROR = "ERROR";

    /** Обработка объекта полностью завершена (агент останавливает маршрутизацию) */
    public static final String DONE = "DONE";

    /** Пустой payload (0 байт или пустой текст после очистки) */
    public static final String EMPTY = "EMPTY_SESSION";

    // =========================================================================
    // Текстовые и размеченные формы
    // =========================================================================

    /** Сырой необработанный текст (до нормализации и очистки в TextCleanPlace) */
    public static final String TXT = "TXT";

    /** Очищенный и нормализованный текст (готов для стадии ANALYZE в TextAnalyzePlace) */
    public static final String TEXT = "TEXT";

    /** HTML-документ или HTML-часть письма */
    public static final String HTML = "HTML";

    /** XML-документ */
    public static final String XML = "XML";

    /** JSON / NDJSON / LD+JSON документ */
    public static final String JSON = "JSON";

    /** YAML-конфигурация или документ */
    public static final String YAML = "YAML";

    /** Таблица разделённых запятыми значений (CSV) */
    public static final String CSV = "CSV";

    /** Таблица разделённых табуляцией значений (TSV) */
    public static final String TSV = "TSV";

    // =========================================================================
    // Почтовые форматы и календари/контакты
    // =========================================================================

    /** Почтовое сообщение RFC 822 (.eml) */
    public static final String EML = "EML";

    /** Почтовое сообщение Microsoft Outlook (.msg) */
    public static final String MSG = "MSG";

    /** Почтовый ящик формата MBOX */
    public static final String MBOX = "MBOX";

    /** Контейнер вложений Microsoft Transport Neutral Encapsulation Format (winmail.dat) */
    public static final String TNEF = "TNEF";

    /** Календарное событие iCalendar (.ics) */
    public static final String ICS = "ICS";

    /** Карточка контакта vCard (.vcf) */
    public static final String VCARD = "VCARD";

    // =========================================================================
    // Архивы и контейнеры сжатия
    // =========================================================================

    /** Архив ZIP */
    public static final String ZIP = "ZIP";

    /** Архив TAR / GTAR */
    public static final String TAR = "TAR";

    /** Сжатый поток GZIP */
    public static final String GZIP = "GZIP";

    /** Сжатый поток BZIP2 */
    public static final String BZIP2 = "BZIP2";

    /** Сжатый поток XZ */
    public static final String XZ = "XZ";

    /** Архив 7-Zip (.7z) */
    public static final String SEVENZ = "SEVENZ";

    /** Архив RAR */
    public static final String RAR = "RAR";

    /** Сжатый поток Zstandard (.zst) */
    public static final String ZSTD = "ZSTD";

    /** Java-архив (.jar) */
    public static final String JAR = "JAR";

    // =========================================================================
    // Документы, электронные книги и офисные форматы
    // =========================================================================

    /** Документ Adobe PDF */
    public static final String PDF = "PDF";

    /** Документ PostScript */
    public static final String PS = "PS";

    /** Электронная книга EPUB */
    public static final String EPUB = "EPUB";

    /** Документ DjVu */
    public static final String DJVU = "DJVU";

    /** Документ Microsoft Word OOXML (.docx, .docm, .dotx) */
    public static final String DOCX = "DOCX";

    /** Документ Microsoft Word старого формата OLE2 (.doc) */
    public static final String DOC = "DOC";

    /** Таблица Microsoft Excel OOXML (.xlsx, .xlsm, .xltx) */
    public static final String XLSX = "XLSX";

    /** Двоичная книга Microsoft Excel (.xlsb) */
    public static final String XLSB = "XLSB";

    /** Таблица Microsoft Excel старого формата OLE2 (.xls) */
    public static final String XLS = "XLS";

    /** Презентация Microsoft PowerPoint OOXML (.pptx, .pptm, .ppsx) */
    public static final String PPTX = "PPTX";

    /** Презентация Microsoft PowerPoint старого формата OLE2 (.ppt) */
    public static final String PPT = "PPT";

    /** Схема Microsoft Visio старого формата (.vsd) */
    public static final String VSD = "VSD";

    /** Схема Microsoft Visio OOXML (.vsdx) */
    public static final String VSDX = "VSDX";

    /** Проект Microsoft Project (.mpp) */
    public static final String MPP = "MPP";

    /** Записная книжка Microsoft OneNote (.one) */
    public static final String ONENOTE = "ONENOTE";

    /** Текстовый документ OpenDocument (.odt) */
    public static final String ODT = "ODT";

    /** Электронная таблица OpenDocument (.ods) */
    public static final String ODS = "ODS";

    /** Презентация OpenDocument (.odp) */
    public static final String ODP = "ODP";

    /** Документ Rich Text Format (.rtf) */
    public static final String RTF = "RTF";

    // =========================================================================
    // Изображения (для OCR и анализа метаданных)
    // =========================================================================

    /** Изображение PNG */
    public static final String PNG = "PNG";

    /** Изображение JPEG / JPG */
    public static final String JPEG = "JPEG";

    /** Изображение TIFF (часто многостраничные сканы для OCR) */
    public static final String TIFF = "TIFF";

    /** Растровое изображение Bitmap (.bmp) */
    public static final String BMP = "BMP";

    /** Изображение WebP */
    public static final String WEBP = "WEBP";

    /** Изображение GIF */
    public static final String GIF = "GIF";

    /** Изображение Apple HEIC */
    public static final String HEIC = "HEIC";

    /** Изображение HEIF */
    public static final String HEIF = "HEIF";

    /** Векторное изображение SVG */
    public static final String SVG = "SVG";

    // =========================================================================
    // Аудио, видео и базы данных
    // =========================================================================

    /** Аудиофайл MP3 */
    public static final String MP3 = "MP3";

    /** Аудиофайл M4A / MP4 Audio */
    public static final String M4A = "M4A";

    /** Аудиофайл WAV */
    public static final String WAV = "WAV";

    /** Аудиофайл OGG */
    public static final String OGG = "OGG";

    /** Видеофайл MP4 */
    public static final String MP4 = "MP4";

    /** Видеофайл Apple QuickTime (.mov) */
    public static final String MOV = "MOV";

    /** Видеофайл AVI */
    public static final String AVI = "AVI";

    /** Видеоконтейнер Matroska (.mkv) */
    public static final String MKV = "MKV";

    /** Видеофайл WebM */
    public static final String WEBM = "WEBM";

    /** Файл базы данных SQLite3 */
    public static final String SQLITE = "SQLITE";

    /** Файл базы данных Microsoft Access (.mdb / .accdb) */
    public static final String MDB = "MDB";

    // =========================================================================
    // Префиксы форм (Form prefixes)
    // =========================================================================

    /** Префикс формы для обозначения определённого языка текста (например, LANG-RU, LANG-EN) */
    public static final String PREFIXES_LANG = "LANG-";

    // =========================================================================
    // Суффиксы состояний форм (Form suffixes)
    // =========================================================================

    /** Суффикс формы после декодирования HTML-сущностей */
    public static final String SUFFIXES_HTMLESC = "-HTMLESC";

    /** Суффикс формы при ошибке обработки конкретного формата */
    public static final String SUFFIXES_ERROR = "-ERROR";

    /** Суффикс родительского контейнера после успешной распаковки/обработки (например, EML-PROCESSED, ZIP-PROCESSED) */
    public static final String SUFFIXES_PROCESSED = "-PROCESSED";

    /** Суффикс снятия внешней обёртки контейнера */
    public static final String SUFFIXES_UNWRAPPED = "-UNWRAPPED";

    /** Суффикс повреждённого файла/контейнера */
    public static final String SUFFIXES_BROKEN = "-BROKEN";

    /** Суффикс архива или документа, защищённого паролем */
    public static final String SUFFIXES_PASSWORD_PROTECTED = "-PASSWORD-PROTECTED";

    // =========================================================================
    // Готовые составные формы обработанных контейнеров (<FORM> + SUFFIXES_PROCESSED)
    // =========================================================================

    /** Форма родительского EML-письма после извлечения заголовков, тела и вложений в EmailPlace */
    public static final String EML_PROCESSED = EML + SUFFIXES_PROCESSED;

    /** Форма родительского ZIP-архива после распаковки всех дочерних записей в ZipPlace */
    public static final String ZIP_PROCESSED = ZIP + SUFFIXES_PROCESSED;

    /** Форма родительского DOCX-документа после извлечения текста и вложений */
    public static final String DOCX_PROCESSED = DOCX + SUFFIXES_PROCESSED;

    /** Форма родительского DOC-документа после извлечения текста */
    public static final String DOC_PROCESSED = DOC + SUFFIXES_PROCESSED;

    /** Форма родительского PDF-документа после извлечения текста и картинок */
    public static final String PDF_PROCESSED = PDF + SUFFIXES_PROCESSED;

    /** Форма родительской таблицы XLSX после извлечения текста листов и вложений */
    public static final String XLSX_PROCESSED = XLSX + SUFFIXES_PROCESSED;

    /** Форма родительской таблицы XLS после извлечения текста листов */
    public static final String XLS_PROCESSED = XLS + SUFFIXES_PROCESSED;

    /** Форма родительской презентации PPTX после извлечения текста слайдов и вложений */
    public static final String PPTX_PROCESSED = PPTX + SUFFIXES_PROCESSED;

    /** Форма родительской презентации PPT после извлечения текста слайдов */
    public static final String PPT_PROCESSED = PPT + SUFFIXES_PROCESSED;

    /** Форма родительского RTF-документа после извлечения текста */
    public static final String RTF_PROCESSED = RTF + SUFFIXES_PROCESSED;

    /** This class is not meant to be instantiated. */
    private Form() {}
}
