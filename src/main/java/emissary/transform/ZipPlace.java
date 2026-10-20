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
    private static final String ZIP_TRUNCATED_PARAM_NAME = "ZIP_TRUNCATED";
    private static final String EXTENDING_FILE_SUFFIX = "-att-";
    protected int maxEntries = 500;
    protected long maxTotalUnpackedBytes = 100L * 1024 * 1024;
    protected String encoding = "UTF-8";
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

    private static boolean isSkippableEntry(final ZipArchiveInputStream zis, final ZipArchiveEntry entry, final IBaseDataObject payload) {

        // Check on Directory
        if (entry.isDirectory()) {
            return true;
        }

        // Check on encrypted
        if (!zis.canReadEntryData(entry)) {
            payload.appendParameter("ZIP_UNREADABLE_ENTRY", entry.getName());
            payload.putParameter("ZIP_ENCRYPTED_OR_UNSUPPORTED", "true");
            return true;
        }
        return false;
    }

    /**
     * Копирует байты из потока в буфер, но не более maxBytes. Возвращает true, если запись поместилась в лимит, и false,
     * если лимит превышен.
     */
    private static boolean copyBounded(final InputStream in, final ByteArrayOutputStream out, final long maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) {
                return false;
            }
            out.write(buffer, 0, n);
        }
        return true;
    }

    private static IBaseDataObject createChildPayload(final ZipArchiveEntry entry, final byte[] childBytes,
            final IBaseDataObject parentPayload, final int childIndex) {
        String childShortName = parentPayload.getFilename() + EXTENDING_FILE_SUFFIX + childIndex;
        IBaseDataObject child = DataObjectFactory.getInstance(childBytes, childShortName, Form.UNKNOWN);
        child.putParameter("ARCHIVE_ENTRY_NAME", entry.getName());
        child.putParameter("PARENT_ARCHIVE_TYPE", Form.ZIP);
        if (entry.getSize() >= 0) {
            child.putParameter("ARCHIVE_DECLARED_SIZE", Long.toString(entry.getSize()));
        }
        if (entry.getLastModifiedDate() != null) {
            child.putParameter("ARCHIVE_ENTRY_MTIME", Instant.ofEpochMilli(entry.getLastModifiedDate().getTime()).toString());
        }
        if (entry.getComment() != null && !entry.getComment().isBlank()) {
            child.putParameter("ARCHIVE_ENTRY_COMMENT", entry.getComment());
        }
        return child;
    }

    protected void configurePlace() {
        if (configG != null) {
            this.maxEntries = configG.findIntEntry("MAX_ENTRIES", 500);
            this.maxTotalUnpackedBytes = configG.findLongEntry("MAX_TOTAL_UNPACKED_BYTES", 100L * 1024 * 1024);
            this.encoding = configG.findStringEntry("ZIP_ENCODING", "UTF-8");
            this.clearParentData = configG.findBooleanEntry("CLEAR_PARENT_DATA", false);
        }
    }

    @Override
    public List<IBaseDataObject> processHeavyDuty(final IBaseDataObject payload) throws ResourceException {

        List<IBaseDataObject> children = new ArrayList<>();

        if (payload.dataLength() == 0) {
            payload.setCurrentForm(Form.EMPTY);
            return children;
        }

        try (InputStream bais = new ByteArrayInputStream(payload.data());
                ZipArchiveInputStream zis = new ZipArchiveInputStream(bais, this.encoding, true, true)) {
            long totalUnpackedBytes = extractEntries(zis, payload, children);
            payload.putParameter("ZIP_EXTRACTED_COUNT", Integer.toString(children.size()));
            payload.putParameter("ZIP_TOTAL_UNPACKED_BYTES", Long.toString(totalUnpackedBytes));
            if (this.clearParentData && !children.isEmpty()) {
                payload.setData(new byte[0]);
            }
            payload.setFileTypeIfEmpty(Form.ZIP);
            payload.setCurrentForm(Form.ZIP_PROCESSED);
        } catch (Exception e) {
            logger.error("ZipPlace failed to unpack {}", payload.shortName(), e);
            payload.addProcessingError("ZipPlace: " + e.getMessage());
            payload.setCurrentForm(Form.ERROR);
        }
        return children;
    }

    private long extractEntries(final ZipArchiveInputStream zis, final IBaseDataObject payload, final List<IBaseDataObject> children)
            throws IOException {
        long totalUnpackedBytes = 0;
        int entryCount = 0;
        ZipArchiveEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (!isSkippableEntry(zis, entry, payload)) {
                entryCount++;
                long remainingBudget = this.maxTotalUnpackedBytes - totalUnpackedBytes;
                ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
                if (!readEntryWithinLimits(zis, entry, entryBuffer, entryCount, remainingBudget, payload)) {
                    break;
                }
                byte[] childBytes = entryBuffer.toByteArray();
                totalUnpackedBytes += childBytes.length;
                children.add(createChildPayload(entry, childBytes, payload, children.size() + 1));
                payload.appendParameter("ZIP_ENTRY_NAMES", entry.getName());
            }
        }
        return totalUnpackedBytes;
    }

    private boolean readEntryWithinLimits(final ZipArchiveInputStream zis, final ZipArchiveEntry entry, final ByteArrayOutputStream entryBuffer,
            final int entryCount, final long remainingBudget, final IBaseDataObject payload) throws IOException {

        if (!isWithinLimits(entryCount, remainingBudget, payload)) {
            return false;
        }

        if (!copyBounded(zis, entryBuffer, remainingBudget)) {
            payload.addProcessingError("ZipPlace: entry '" + entry.getName() + "' exceeded remaining byte budget");
            payload.putParameter(ZIP_TRUNCATED_PARAM_NAME, "MAX_BYTES_REACHED");
            return false;
        }
        return true;
    }

    private boolean isWithinLimits(final int entryCount, final long remainingBudget, final IBaseDataObject payload) {

        if (entryCount > this.maxEntries) {
            payload.addProcessingError("ZipPlace: exceeded MAX_ENTRIES (" + this.maxEntries + ")");
            payload.putParameter(ZIP_TRUNCATED_PARAM_NAME, "MAX_ENTRIES_REACHED");
            return false;
        }

        if (remainingBudget <= 0) {
            payload.addProcessingError("ZipPlace: exceeded MAX_TOTAL_UNPACKED_BYTES (" + this.maxTotalUnpackedBytes + ")");
            payload.putParameter(ZIP_TRUNCATED_PARAM_NAME, "MAX_BYTES_REACHED");
            return false;
        }

        return true;
    }
}
