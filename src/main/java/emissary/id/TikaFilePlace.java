package emissary.id;

import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;

import jakarta.annotation.Nullable;
import org.apache.tika.Tika;
import org.apache.tika.mime.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public class TikaFilePlace extends IdPlace {

    private static final List<String> DEFAULT_FILENAME_PARAM_KEYS = List.of("FILE_NAME");

    protected Tika tika;
    protected boolean useFilenameHint;
    protected boolean setFileType;
    protected List<String> filenameParamKeys = DEFAULT_FILENAME_PARAM_KEYS;

    public TikaFilePlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
        configurePlace();
    }

    public TikaFilePlace(final InputStream configStream) throws IOException {
        super(configStream);
        configurePlace();
    }

    /**
     * Основной конструктор, который вызывает Emissary при старте узла (через PlaceStarter)
     */
    public TikaFilePlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
        configurePlace();
    }

    /**
     * Конструктор для тестов и локального запуска
     */
    public TikaFilePlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "TikaFilePlace.example.com:8001");
        configurePlace();
    }

    public TikaFilePlace() throws IOException {
        super();
        configurePlace();
    }

    private static String toEmissaryForm(final String mimeString) {
        MediaType mediaType = MediaType.parse(mimeString);
        String subType = mediaType != null ? mediaType.getSubtype() : mimeString;
        return subType.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    @Nullable
    private static String trimToNull(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    protected void configurePlace() {
        configureIdPlace();
        if (configG != null) {
            this.useFilenameHint = configG.findBooleanEntry("USE_FILENAME_HINT", true);
            this.setFileType = configG.findBooleanEntry("SET_FILETYPE", true);
            List<String> configuredKeys = configG.findEntries("FILENAME_HINT_PARAM");
            this.filenameParamKeys = configuredKeys.isEmpty() ? DEFAULT_FILENAME_PARAM_KEYS : List.copyOf(configuredKeys);
        } else {
            this.useFilenameHint = true;
            this.setFileType = true;
            this.filenameParamKeys = DEFAULT_FILENAME_PARAM_KEYS;
        }
        this.tika = new Tika();
        logger.info("TikaFilePlace loaded ignores: {}", this.ignores);
        logger.info("TikaFilePlace loaded renames: {}", this.renames);
    }

    @Override
    public void process(final IBaseDataObject payload) throws ResourceException {

        if (payload.dataLength() == 0) {
            payload.setCurrentForm(Form.EMPTY);
            payload.setFileType(Form.EMPTY);
            return;
        }

        String filenameHint = this.useFilenameHint ? resolveFilenameHint(payload) : null;
        String mimeString = filenameHint != null
                ? this.tika.detect(payload.data(), filenameHint)
                : this.tika.detect(payload.data());

        MediaType mediaType = MediaType.parse(mimeString);
        String baseMime = mediaType != null ? mediaType.getBaseType().toString() : mimeString;

        payload.putParameter("TIKA_MIME_TYPE", baseMime);

        if (this.ignores != null && (this.ignores.contains(baseMime) || this.ignores.contains(mimeString))) {
            return;
        }

        // Маппим через ID_RENAME_ из .cfg или берём subtype (напр. "application/pdf" -> "PDF")
        String form = this.renames.getOrDefault(baseMime,
                this.renames.getOrDefault(mimeString, toEmissaryForm(baseMime)));

        if (this.ignores != null && this.ignores.contains(form)) {
            return;
        }

        setCurrentForm(payload, form);

        if (this.setFileType) {
            payload.setFileTypeIfEmpty(form);
        }
    }
}
