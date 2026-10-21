package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.test.core.junit5.UnitTest;

import jakarta.annotation.Nullable;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TikaTransformPlaceTest extends UnitTest {

    @Nullable
    private TikaTransformPlace place;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        this.place = new TikaTransformPlace();
    }

    @Override
    @AfterEach
    public void tearDown() throws Exception {
        if (this.place != null) {
            this.place.shutDown();
            this.place = null;
        }
        super.tearDown();
    }

    @Test
    void testIsDeniedPreventsLoopsAndSystemForms() {
        assertTrue(this.place.isDenied(Form.TXT));
        assertTrue(this.place.isDenied(Form.TEXT));
        assertTrue(this.place.isDenied(Form.EMPTY));
        assertTrue(this.place.isDenied(Form.ERROR));
        assertTrue(this.place.isDenied(Form.DONE));
        assertTrue(this.place.isDenied(Form.EML_PROCESSED));
        assertTrue(this.place.isDenied(Form.ZIP_PROCESSED));
        assertTrue(this.place.isDenied("ODT-PROCESSED"));
        assertTrue(this.place.isDenied("UNKNOWN-PROCESSED"));
        assertTrue(this.place.isDenied("PDF-PASSWORD-PROTECTED"));
        assertTrue(this.place.isDenied("DOCX-BROKEN"));

        assertFalse(this.place.isDenied(Form.UNKNOWN));
        assertFalse(this.place.isDenied(Form.ODT));
        assertFalse(this.place.isDenied(Form.MSG));
        assertFalse(this.place.isDenied(Form.TAR));
        assertFalse(this.place.isDenied(Form.CSV));
    }

    @Test
    void testExtractsBodyTextAsTxtChildFromDocument() throws Exception {
        byte[] docxBytes = createSampleDocx("Привет из документа Word! Контакт: user@example.com");
        IBaseDataObject payload = DataObjectFactory.getInstance(docxBytes, "report.docx", Form.UNKNOWN);

        List<IBaseDataObject> children = this.place.processHeavyDuty(payload);

        assertEquals(Form.DOCX_PROCESSED, payload.currentForm());
        assertEquals(Form.DOCX, payload.getFileType());
        assertEquals("true", payload.getParameterAsString("TIKA_EXTRACTED_TEXT"));
        assertEquals(1, children.size());

        IBaseDataObject textChild = children.get(0);
        assertEquals(Form.TXT, textChild.currentForm());
        assertEquals(Form.TXT, textChild.getFileType());
        assertEquals("DOCUMENT_TEXT", textChild.getParameterAsString("EXTRACTED_PART_TYPE"));
        String extractedText = new String(textChild.data(), StandardCharsets.UTF_8);
        assertTrue(extractedText.contains("user@example.com"));
    }

    @Test
    void testExtractsEmbeddedEntriesFromTarArchiveAsUnknownChildren() throws Exception {
        byte[] tarBytes = createSampleTar(
                "notes/readme.txt", "First tar entry text".getBytes(StandardCharsets.UTF_8),
                "data/second.csv", "a,b,c\n1,2,3".getBytes(StandardCharsets.UTF_8));

        IBaseDataObject payload = DataObjectFactory.getInstance(tarBytes, "bundle.tar", Form.TAR);

        List<IBaseDataObject> children = this.place.processHeavyDuty(payload);

        assertEquals(Form.TAR + Form.SUFFIXES_PROCESSED, payload.currentForm());
        assertEquals("2", payload.getParameterAsString("TIKA_ATTACHMENT_COUNT"));
        assertEquals(2, children.size());

        IBaseDataObject first = children.get(0);
        assertEquals(Form.UNKNOWN, first.currentForm());
        assertEquals("EMBEDDED_FILE", first.getParameterAsString("EXTRACTED_PART_TYPE"));
        assertEquals("readme.txt", first.getParameterAsString("ATTACHMENT_FILENAME"));
        assertEquals("First tar entry text", new String(first.data(), StandardCharsets.UTF_8));

        IBaseDataObject second = children.get(1);
        assertEquals(Form.UNKNOWN, second.currentForm());
        assertEquals("second.csv", second.getParameterAsString("ATTACHMENT_FILENAME"));
    }

    @Test
    void testFallbackTextDetectionOnUnknownReadablePayload() throws Exception {
        byte[] rawBytes = "Custom unmapped text format with phone +7 (999) 123-45-67".getBytes(StandardCharsets.UTF_8);
        IBaseDataObject payload = DataObjectFactory.getInstance(rawBytes, "mystery.dat", Form.UNKNOWN);

        List<IBaseDataObject> children = this.place.processHeavyDuty(payload);

        assertEquals(Form.TXT + Form.SUFFIXES_PROCESSED, payload.currentForm());
        assertEquals(Form.TXT, payload.getFileType());
        assertEquals(1, children.size());
        assertEquals(Form.TXT, children.get(0).currentForm());
        assertTrue(new String(children.get(0).data(), StandardCharsets.UTF_8).contains("+7 (999) 123-45-67"));
    }

    @Test
    void testTextLimitTruncatesWithoutFailingPayload() throws Exception {
        this.place.maxTextChars = 32;
        byte[] rawBytes = ("Custom unmapped text format with phone +7 (999) 123-45-67 and more trailing words")
                .getBytes(StandardCharsets.UTF_8);
        IBaseDataObject payload = DataObjectFactory.getInstance(rawBytes, "mystery.dat", Form.UNKNOWN);

        List<IBaseDataObject> children = this.place.processHeavyDuty(payload);

        assertEquals(Form.TXT + Form.SUFFIXES_PROCESSED, payload.currentForm());
        assertEquals("true", payload.getParameterAsString("TIKA_TEXT_TRUNCATED"));
        assertEquals("true", payload.getParameterAsString("TIKA_EXTRACTED_TEXT"));
        assertEquals(1, children.size());
        String extracted = new String(children.get(0).data(), StandardCharsets.UTF_8);
        assertTrue(extracted.startsWith("Custom unmapped"));
        assertTrue(extracted.length() <= this.place.maxTextChars);
    }

    @Test
    void testLooksLikeReadableTextRejectsBinaryAndAcceptsProse() {
        assertFalse(TikaTransformPlace.looksLikeReadableText(new byte[0]));
        assertFalse(TikaTransformPlace.looksLikeReadableText(new byte[] {0x00, 0x41}));
        assertFalse(TikaTransformPlace.looksLikeReadableText(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05}));
        assertTrue(TikaTransformPlace.looksLikeReadableText("hello\tworld\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testExtractFileNameStripsDirectoriesAndTrailingSeparator() {
        assertEquals("readme.txt", TikaTransformPlace.extractFileName("notes/readme.txt"));
        assertEquals("readme.txt", TikaTransformPlace.extractFileName("notes\\readme.txt"));
        assertEquals("readme.txt", TikaTransformPlace.extractFileName("notes/readme.txt/"));
        assertNull(TikaTransformPlace.extractFileName("/"));
        assertNull(TikaTransformPlace.extractFileName("   "));
    }

    @Test
    void testBinaryUnknownDoesNotProduceGarbageTextChild() throws Exception {
        byte[] binaryBytes = new byte[] {0x00, 0x01, 0x02, 0x03, (byte) 0xFF, 0x00, 0x7F, (byte) 0x80};
        IBaseDataObject payload = DataObjectFactory.getInstance(binaryBytes, "blob.bin", Form.UNKNOWN);

        List<IBaseDataObject> children = this.place.processHeavyDuty(payload);

        assertEquals(Form.UNKNOWN + Form.SUFFIXES_PROCESSED, payload.currentForm());
        assertEquals("false", payload.getParameterAsString("TIKA_EXTRACTED_TEXT"));
        assertTrue(children.isEmpty());
    }

    private static byte[] createSampleDocx(final String paragraphText) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText(paragraphText);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] createSampleTar(final String name1, final byte[] bytes1,
            final String name2, final byte[] bytes2) throws Exception {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            addTarEntry(tos, name1, bytes1);
            addTarEntry(tos, name2, bytes2);
            tos.finish();
            return bos.toByteArray();
        }
    }

    private static void addTarEntry(final TarArchiveOutputStream tos, final String name, final byte[] bytes) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tos.putArchiveEntry(entry);
        tos.write(bytes);
        tos.closeArchiveEntry();
    }
}
