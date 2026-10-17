package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ZipPlace extends ServiceProviderPlace {

    private static final String EXTENDING_FILE_SUFFIX = "-att-";
    private static final String ZIP_TRUNCATED_PARAM_NAME = "ZIP_TRUNCATED";
    private static final String UTF_8_ENCODING_NAME = "UTF-8";

    protected int maxEntries = 500;
    protected long maxTotalUnpackedBytes = 500L * 1024 * 1024; // 500 MB по умолчанию
    protected String encoding = UTF_8_ENCODING_NAME;
    protected boolean clearParentData = false;

    public ZipPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
        configurePlace();
    }

    public ZipPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
        configurePlace();
    }

    public ZipPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "ZipPlace.example.com:8001");
        configurePlace();
    }

    public ZipPlace() throws IOException {
        super();
        configurePlace();
    }

    private static byte[] readBounded(final InputStream in, final long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    protected void configurePlace() {
        if (configG != null) {
            this.maxEntries = configG.findIntEntry("MAX_ENTRIES", 500);
            this.maxTotalUnpackedBytes = configG.findLongEntry("MAX_TOTAL_UNPACKED_BYTES", 500L * 1024 * 1024);
            this.encoding = configG.findStringEntry("ZIP_ENCODING", UTF_8_ENCODING_NAME);
            this.clearParentData = configG.findBooleanEntry("CLEAR_PARENT_DATA", false);
        } else {
            this.maxEntries = 500;
            this.maxTotalUnpackedBytes = 500L * 1024 * 1024;
            this.encoding = UTF_8_ENCODING_NAME;
            this.clearParentData = false;
        }
    }

    @Override
    public List<IBaseDataObject> processHeavyDuty(final IBaseDataObject payload) throws ResourceException {

        List<IBaseDataObject> children = new ArrayList<>();
        if (payload.dataLength() == 0) {
            payload.setCurrentForm(Form.EMPTY);
            return children;
        }

        long totalUnpackedBytes = 0;
        int entryCount = 0;

        try (InputStream bais = new ByteArrayInputStream(payload.data());
                ZipArchiveInputStream zis = new ZipArchiveInputStream(bais, this.encoding, true, true)) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (!zis.canReadEntryData(entry)) {
                    // Например, если файл внутри архива зашифрован паролем или использует редкий метод сжатия
                    payload.appendParameter("ZIP_UNREADABLE_ENTRY", entry.getName());
                    payload.putParameter("ZIP_ENCRYPTED_OR_UNSUPPORTED", "true");
                    continue;
                }
                entryCount++;
                if (entryCount > this.maxEntries) {
                    payload.addProcessingError("ZipPlace: exceeded MAX_ENTRIES (" + this.maxEntries + ")");
                    payload.putParameter(ZIP_TRUNCATED_PARAM_NAME, "MAX_ENTRIES_REACHED");
                    break;
                }
                // Читаем байты файла с контролем лимита (защита от Zip Bomb)
                long remainingBudget = this.maxTotalUnpackedBytes - totalUnpackedBytes;
                if (remainingBudget <= 0) {
                    payload.addProcessingError("ZipPlace: exceeded MAX_TOTAL_UNPACKED_BYTES (" + this.maxTotalUnpackedBytes + ")");
                    payload.putParameter(ZIP_TRUNCATED_PARAM_NAME, "MAX_BYTES_REACHED");
                    break;
                }
                byte[] childBytes = readBounded(zis, remainingBudget);
                if (childBytes == null) {
                    payload.addProcessingError("ZipPlace: entry '" + entry.getName() + "' exceeded remaining byte budget");
                    payload.putParameter(ZIP_TRUNCATED_PARAM_NAME, "MAX_BYTES_REACHED");
                    break;
                }
                totalUnpackedBytes += childBytes.length;
                // Формируем корректный индекс -att-1, -att-2...
                String childShortName = payload.getFilename() + EXTENDING_FILE_SUFFIX + (children.size() + 1);
                IBaseDataObject child = DataObjectFactory.getInstance(
                        childBytes,
                        childShortName,
                        Form.UNKNOWN // Отправляем распакованный файл обратно в TikaFilePlace!
                );
                // Записываем метаданные файла из архива в ребёнка
                child.putParameter("ARCHIVE_ENTRY_NAME", entry.getName());
                child.putParameter("PARENT_ARCHIVE_TYPE", "ZIP");
                if (entry.getSize() >= 0) {
                    child.putParameter("ARCHIVE_DECLARED_SIZE", Long.toString(entry.getSize()));
                }
                if (entry.getLastModifiedDate() != null) {
                    child.putParameter("ARCHIVE_ENTRY_MTIME", Instant.ofEpochMilli(entry.getLastModifiedDate().getTime()).toString());
                }
                if (entry.getComment() != null && !entry.getComment().isBlank()) {
                    child.putParameter("ARCHIVE_ENTRY_COMMENT", entry.getComment());
                }
                children.add(child);
                payload.appendParameter("ZIP_ENTRY_NAMES", entry.getName());
            }
            payload.putParameter("ZIP_EXTRACTED_COUNT", Integer.toString(children.size()));
            payload.putParameter("ZIP_TOTAL_UNPACKED_BYTES", Long.toString(totalUnpackedBytes));
            // Очищаем байты самого архива после успешной распаковки, чтобы не держать дубликаты в памяти
            if (this.clearParentData && !children.isEmpty()) {
                payload.setData(new byte[0]);
            }
            // Меняем форму родительского архива с ZIP на ZIP_UNPACKED, чтобы он не зациклился!
            payload.setCurrentForm("ZIP_UNPACKED");
        } catch (Exception e) {
            logger.error("ZipPlace failed to unpack {}", payload.shortName(), e);
            payload.addProcessingError("ZipPlace: " + e.getMessage());
            payload.setCurrentForm(Form.ERROR);
        }
        return children;
    }

}
