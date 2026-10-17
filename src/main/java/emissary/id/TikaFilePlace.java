package emissary.id;

import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;

import org.apache.tika.Tika;
import org.apache.tika.mime.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class TikaFilePlace extends IdPlace {

    protected Tika tika;
    protected boolean useFilenameHint;
    protected boolean setFileType;

    public TikaFilePlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
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

    protected void configurePlace() {
        configureIdPlace();
        if (configG != null) {
            this.useFilenameHint = configG.findBooleanEntry("USE_FILENAME_HINT", false);
            this.setFileType = configG.findBooleanEntry("SET_FILETYPE", true);
        } else {
            this.useFilenameHint = false;
            this.setFileType = true;
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

        String mimeString = this.useFilenameHint && payload.shortName() != null
                ? this.tika.detect(payload.data(), payload.shortName())
                : this.tika.detect(payload.data());

        payload.putParameter("TIKA_MIME_TYPE", mimeString);

        if (this.ignores != null && this.ignores.contains(mimeString)) {
            return;
        }

        // Маппим через ID_RENAME_ из .cfg или берём subtype (напр. "application/pdf" -> "PDF")
        String form = this.renames.getOrDefault(mimeString, toEmissaryForm(mimeString));
        setCurrentForm(payload, form);

        if (this.setFileType) {
            payload.setFileTypeIfEmpty(form);
        }
    }
}
