package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Обработчик презентаций Microsoft PowerPoint ({@code PPTX} и {@code PPT}) на фазе {@code TRANSFORM} (на базе Apache
 * POI).
 * <ul>
 * <li>За один проход извлекает метаданные презентации (автор, тема, компания, даты, количество слайдов) в параметры
 * родителя.</li>
 * <li>Извлекает текст всех слайдов, заметок докладчика и комментариев в дочерний объект с формой {@link Form#TXT}.</li>
 * <li>Переводит родительский объект в форму {@link Form#PPTX_PROCESSED} или {@link Form#PPT_PROCESSED}.</li>
 * </ul>
 */
public class PresentationPlace extends ServiceProviderPlace {

    private static final String EXTENDING_FILE_SUFFIX = "-att-";
    private static final String CHARSET_UTF_8 = "UTF-8";

    protected boolean includeSlideNotes = true;
    protected boolean includeComments = true;

    public PresentationPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
        configurePlace();
    }

    public PresentationPlace(final InputStream configStream) throws IOException {
        super(configStream);
        configurePlace();
    }

    public PresentationPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
        configurePlace();
    }

    public PresentationPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "PresentationPlace.example.com:8001");
        configurePlace();
    }

    public PresentationPlace() throws IOException {
        super();
        configurePlace();
    }

    private static void extractMetadata(final SlideShow<?, ?> slideShow, final IBaseDataObject payload) {
        payload.putParameter("PPT_SLIDE_COUNT", Integer.toString(slideShow.getSlides().size()));

        if (slideShow instanceof XMLSlideShow xslf) {
            DocxPlace.extractOoxmlMetadata(xslf.getProperties(), payload);
        } else if (slideShow instanceof HSLFSlideShow hslf) {
            DocxPlace.extractOleMetadata(hslf.getSummaryInformation(), hslf.getDocumentSummaryInformation(), payload);
        }
    }

    private static String childBaseName(final IBaseDataObject parent) {
        String name = parent.shortName();
        if (name == null || name.isBlank()) {
            name = parent.getFilename();
        }
        return (name != null && !name.isBlank()) ? name.trim() : "presentation";
    }

    protected void configurePlace() {
        if (configG != null) {
            this.includeSlideNotes = configG.findBooleanEntry("INCLUDE_SLIDE_NOTES", true);
            this.includeComments = configG.findBooleanEntry("INCLUDE_COMMENTS", true);
        }
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(
                Form.PPTX_PROCESSED,
                Form.PPT_PROCESSED,
                Form.PPTX + Form.SUFFIXES_PASSWORD_PROTECTED,
                Form.PPT + Form.SUFFIXES_PASSWORD_PROTECTED,
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

        boolean isLegacyPpt = Form.PPT.equalsIgnoreCase(payload.currentForm())
                || Form.PPT.equalsIgnoreCase(payload.getFileType());
        String baseForm = isLegacyPpt ? Form.PPT : Form.PPTX;
        payload.setFileTypeIfEmpty(baseForm);

        try (InputStream is = new ByteArrayInputStream(payload.data());
                SlideShow<?, ?> slideShow = SlideShowFactory.create(is);
                SlideShowExtractor<?, ?> extractor = new SlideShowExtractor<>(slideShow)) {

            extractMetadata(slideShow, payload);

            extractor.setSlidesByDefault(true);
            extractor.setNotesByDefault(this.includeSlideNotes);
            extractor.setCommentsByDefault(this.includeComments);

            String text = extractor.getText();
            if (text != null && !text.isBlank()) {
                byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
                String childName = childBaseName(payload) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
                IBaseDataObject textChild = DataObjectFactory.getInstance(textBytes, childName, Form.TXT);
                textChild.setFileType(Form.TXT);
                textChild.setFontEncoding(CHARSET_UTF_8);
                textChild.putParameter("SOURCE_FORMAT", baseForm);
                textChild.putParameter("EXTRACTED_PART_TYPE", "PRESENTATION_TEXT");
                children.add(textChild);
            }

            payload.setCurrentForm(isLegacyPpt ? Form.PPT_PROCESSED : Form.PPTX_PROCESSED);
        } catch (EncryptedDocumentException e) {
            logger.warn("Presentation {} is password-protected", payload.shortName());
            payload.putParameter("DOC_ENCRYPTED", "true");
            payload.setCurrentForm(baseForm + Form.SUFFIXES_PASSWORD_PROTECTED);
        } catch (Exception e) {
            children.clear();
            logger.error("PresentationPlace failed to parse {}", payload.shortName(), e);
            payload.addProcessingError("PresentationPlace: " + e.getMessage());
            payload.setCurrentForm(Form.ERROR);
        }

        return children;
    }
}
