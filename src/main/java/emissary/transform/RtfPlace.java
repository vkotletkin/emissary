package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.Document;
import javax.swing.text.rtf.RTFEditorKit;

/**
 * Обработчик документов {@code RTF} (Rich Text Format) на фазе {@code TRANSFORM}.
 * <ul>
 * <li>Извлекает текст документа и порождает дочерний текстовый объект с формой {@link Form#TXT}.</li>
 * <li>Родительский объект сохраняет исходные байты {@code data()}, получает {@code fileType = RTF} и переходит в форму
 * {@link Form#RTF_PROCESSED}.</li>
 * </ul>
 */
public class RtfPlace extends ServiceProviderPlace {

    private static final String EXTENDING_FILE_SUFFIX = "-att-";
    private static final String CHARSET_UTF_8 = "UTF-8";

    public RtfPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
    }

    public RtfPlace(final InputStream configStream) throws IOException {
        super(configStream);
    }

    public RtfPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
    }

    public RtfPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "RtfPlace.example.com:8001");
    }

    public RtfPlace() throws IOException {
        super();
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(Form.RTF_PROCESSED, Form.TXT, Form.EMPTY, Form.ERROR);
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

        payload.setFileTypeIfEmpty(Form.RTF);

        try (InputStream is = new ByteArrayInputStream(payload.data())) {
            RTFEditorKit rtfKit = new RTFEditorKit();
            Document document = rtfKit.createDefaultDocument();
            rtfKit.read(is, document, 0);

            String text = document.getText(0, document.getLength());
            if (text != null && !text.isBlank()) {
                byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
                String childName = childBaseName(payload) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
                IBaseDataObject textChild = DataObjectFactory.getInstance(textBytes, childName, Form.TXT);
                textChild.setFileType(Form.TXT);
                textChild.setFontEncoding(CHARSET_UTF_8);
                textChild.putParameter("SOURCE_FORMAT", Form.RTF);
                textChild.putParameter("EXTRACTED_PART_TYPE", "DOCUMENT_TEXT");
                children.add(textChild);
            }

            payload.setCurrentForm(Form.RTF_PROCESSED);
        } catch (Exception e) {
            children.clear();
            logger.error("RtfPlace failed to parse {}", payload.shortName(), e);
            payload.addProcessingError("RtfPlace: " + e.getMessage());
            payload.setCurrentForm(Form.ERROR);
        }

        return children;
    }

    private static String childBaseName(final IBaseDataObject parent) {
        String name = parent.shortName();
        if (name == null || name.isBlank()) {
            name = parent.getFilename();
        }
        return (name != null && !name.isBlank()) ? name.trim() : "rtf";
    }
}
