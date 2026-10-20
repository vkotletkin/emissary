package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;
import emissary.util.EmailUtils;

import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Header;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.dom.address.AddressList;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.dom.field.ContentTypeField;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.stream.Field;
import org.apache.james.mime4j.stream.MimeConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Разбирает RFC822 (EML).
 * <ul>
 * <li>Родительский объект сохраняет свои исходные байты {@code data()}, получает метаданные заголовков, фиксирует
 * {@code fileType = EML} и переходит в форму {@code EML-PROCESSED} (для фазы {@code ANALYZE} и {@code IO}).</li>
 * <li>Текстовые тела ({@code text/plain}, {@code text/html}) становятся дочерними объектами с
 * {@code EMAIL_PART_TYPE = BODY} и формами {@code TXT} / {@code HTML}.</li>
 * <li>Файлы-вложения и вложенные {@code message/rfc822} становятся дочерними объектами с
 * {@code EMAIL_PART_TYPE = ATTACHMENT} и формой {@code UNKNOWN}.</li>
 * </ul>
 */
public class EmailPlace extends ServiceProviderPlace {

    private static final String TEXT_PLAIN = "text/plain";
    private static final String TEXT_HTML = "text/html";
    private static final String MULTIPART_ALTERNATIVE = "multipart/alternative";
    private static final String CHARSET_UTF_8 = "UTF-8";

    private static final String PART_TYPE_BODY = "BODY";
    private static final String PART_TYPE_ATTACHMENT = "ATTACHMENT";
    private static final String EXTENDING_FILE_SUFFIX = "-att-";
    private static final String FORWARDED_MESSAGE_NAME = "forwarded.eml";

    public EmailPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
    }

    public EmailPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
    }

    public EmailPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "EmailPlace.example.com:8001");
    }

    public EmailPlace() throws IOException {
        super();
    }

    private static byte[] readBytes(final SingleBody singleBody) throws IOException {
        try (InputStream in = singleBody.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static boolean isPlainOrHtml(final Entity entity) {
        String mime = entity.getMimeType();
        return TEXT_PLAIN.equalsIgnoreCase(mime) || TEXT_HTML.equalsIgnoreCase(mime);
    }

    /**
     * Тело письма, а не файл. Имя из Content-Disposition не мешает, если часть внутри {@code multipart/alternative} или
     * явно {@code inline}: клиенты часто ставят filename на HTML-тело. {@code Content-Disposition: attachment} остаётся
     * вложением.
     */
    private static boolean isTextBody(final Entity entity, final boolean inAlternative) {
        if (!isPlainOrHtml(entity)) {
            return false;
        }

        String disposition = entity.getDispositionType();
        if ("attachment".equalsIgnoreCase(disposition)) {
            return false;
        }

        if (inAlternative || "inline".equalsIgnoreCase(disposition)) {
            return true;
        }

        return entity.getFilename() == null;
    }

    private static String childBaseName(final IBaseDataObject parent) {
        String parentName = trimToNull(parent.shortName());
        if (parentName == null) {
            parentName = trimToNull(parent.getFilename());
        }
        return parentName != null ? parentName : "email";
    }

    /**
     * mime4j {@code getFilename()} видит только filename из Content-Disposition. Имя из {@code Content-Type: name} забираем
     * отдельно.
     */
    private static String resolveFilename(final Entity entity) {
        String filename = trimToNull(entity.getFilename());
        if (filename == null && entity.getHeader() != null) {
            Field raw = entity.getHeader().getField("Content-Type");
            if (raw instanceof ContentTypeField contentType) {
                filename = trimToNull(contentType.getParameter("name"));
            }
        }
        return filename == null ? null : EmailUtils.decodeRaw(filename);
    }

    /**
     * Явный charset из Content-Type. {@code Entity.getCharset()} при отсутствии параметра подставляет us-ascii, поэтому его
     * здесь нет.
     */
    private static String declaredCharset(final Entity entity) {
        if (entity.getHeader() == null) {
            return null;
        }

        Field raw = entity.getHeader().getField("Content-Type");
        if (!(raw instanceof ContentTypeField contentType)) {
            return null;
        }

        return trimToNull(contentType.getCharset());
    }

    private static byte[] toUtf8(final String charset, final byte[] bytes) {
        if (charset == null || isUtf8Compatible(charset)) {
            return bytes;
        }
        try {
            return new String(bytes, Charset.forName(charset)).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return bytes;
        }
    }

    private static String plainCharsetLabel(final String charset, final boolean transcoded) {
        if (charset == null) {
            return null;
        }
        if (transcoded || isUtf8Compatible(charset)) {
            return CHARSET_UTF_8;
        }
        return charset;
    }

    private static boolean isUtf8Compatible(final String charset) {
        return "utf-8".equalsIgnoreCase(charset) || "utf8".equalsIgnoreCase(charset) || "us-ascii".equalsIgnoreCase(charset);
    }

    private static String normalizeContentId(final String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.startsWith("<") && value.endsWith(">") && value.length() > 2) {
            value = trimToNull(value.substring(1, value.length() - 1));
        }
        return value;
    }

    private static String formatMailbox(final String name, final String address) {
        if (name != null && address != null) {
            return name + " <" + address + ">";
        }
        return address != null ? address : name;
    }

    private static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(Form.EML_PROCESSED, Form.HTML, Form.TXT, Form.UNKNOWN, Form.ERROR, Form.EMPTY);
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

        DefaultMessageBuilder builder = new DefaultMessageBuilder();
        builder.setMimeEntityConfig(MimeConfig.PERMISSIVE);
        builder.setDecodeMonitor(DecodeMonitor.SILENT);

        Message message = null;
        try (InputStream is = new ByteArrayInputStream(payload.data())) {
            message = builder.parseMessage(is);
            putHeaders(payload, message);

            ExtractionCounters counters = new ExtractionCounters();
            extractParts(message, payload, children, counters, null);

            payload.setFileTypeIfEmpty(Form.EML);
            payload.setCurrentForm(Form.EML_PROCESSED);
            payload.putParameter("EMAIL_ATTACHMENT_COUNT", Integer.toString(counters.attachmentCount));
        } catch (Exception e) {
            children.clear();
            logger.error("Failed to parse email {}", payload.shortName(), e);
            payload.addProcessingError("EmailPlace: " + e.getMessage());
            payload.setCurrentForm(Form.ERROR);
        } finally {
            if (message != null) {
                message.dispose();
            }
        }
        return children;
    }

    private void putHeaders(final IBaseDataObject payload, final Message message) {
        String subject = trimToNull(message.getSubject());
        if (subject != null) {
            payload.putParameter("EMAIL_SUBJECT", EmailUtils.decodeRaw(subject));
        }
        if (message.getDate() != null) {
            payload.putParameter("EMAIL_DATE", message.getDate().toInstant().toString());
        }
        String messageId = trimToNull(message.getMessageId());
        if (messageId != null) {
            payload.putParameter("EMAIL_MESSAGE_ID", messageId);
        }

        putMailboxes(payload, "EMAIL_FROM", message.getFrom());
        putMailboxes(payload, "EMAIL_SENDER", message.getSender() == null ? null : new MailboxList(List.of(message.getSender())));
        putMailboxes(payload, "EMAIL_REPLY_TO", message.getReplyTo());
        putMailboxes(payload, "EMAIL_TO", message.getTo());
        putMailboxes(payload, "EMAIL_CC", message.getCc());
        putMailboxes(payload, "EMAIL_BCC", message.getBcc());

        Header header = message.getHeader();
        if (header != null) {
            putRawHeader(payload, header, "In-Reply-To", "EMAIL_IN_REPLY_TO");
            putRawHeader(payload, header, "References", "EMAIL_REFERENCES");
            putRawHeader(payload, header, "Return-Path", "EMAIL_RETURN_PATH");
        }
    }

    private void putRawHeader(final IBaseDataObject payload, final Header header, final String fieldName, final String paramKey) {
        for (Field field : header.getFields(fieldName)) {
            if (field != null) {
                String body = trimToNull(field.getBody());
                if (body != null) {
                    payload.putParameter(paramKey, body);
                }
            }
        }
    }

    private void putMailboxes(final IBaseDataObject payload, final String key, final MailboxList mailboxes) {
        if (mailboxes == null || mailboxes.isEmpty()) {
            return;
        }
        for (Mailbox mailbox : mailboxes) {
            if (mailbox != null) {
                putSingleMailbox(payload, key, mailbox);
            }
        }
    }

    private void putSingleMailbox(final IBaseDataObject payload, final String key, final Mailbox mailbox) {
        String address = trimToNull(mailbox.getAddress());
        String rawName = trimToNull(mailbox.getName());
        String name = rawName == null ? null : trimToNull(EmailUtils.decodeRaw(rawName));

        if (address != null) {
            payload.putParameter(key + "_ADDRESS", address);
        }

        if (name != null) {
            payload.putParameter(key + "_NAME", name);
        }

        String formatted = formatMailbox(name, address);
        if (formatted != null) {
            payload.putParameter(key, formatted);
        }
    }

    private void putMailboxes(final IBaseDataObject payload, final String key, final AddressList addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return;
        }
        putMailboxes(payload, key, addresses.flatten());
    }

    private void extractParts(final Entity entity, final IBaseDataObject parent, final List<IBaseDataObject> children,
            final ExtractionCounters counters, final Set<String> seenAlternativeMimes) throws IOException {

        Body body = entity.getBody();
        if (body == null) {
            return;
        }

        boolean inAlternative = seenAlternativeMimes != null;

        switch (body) {
            case Multipart multipart -> {
                if (MULTIPART_ALTERNATIVE.equalsIgnoreCase(entity.getMimeType())) {
                    extractAlternative(multipart, parent, children, counters);
                    return;
                }
                for (Entity part : multipart.getBodyParts()) {
                    if (part != null) {
                        extractParts(part, parent, children, counters, seenAlternativeMimes);
                    }
                }
            }
            case Message nestedMessage ->
                addAttachmentChild(DefaultMessageWriter.asBytes(nestedMessage), entity, FORWARDED_MESSAGE_NAME,
                        parent, children, counters);

            case SingleBody singleBody -> {
                byte[] bytes = readBytes(singleBody);
                if (isTextBody(entity, inAlternative)) {
                    addBodyChild(bytes, entity, parent, children, seenAlternativeMimes);
                    return;
                }
                addAttachmentChild(bytes, entity, null, parent, children, counters);
            }
            default ->
                logger.debug("Skipping unsupported MIME body {} on {}", body.getClass().getName(), parent.shortName());
        }
    }

    /**
     * RFC 2046: последняя часть {@code multipart/alternative} — наиболее полное представление, обходим с конца, но
     * сохраняем и {@code text/plain}, и {@code text/html} как отдельные дочерние объекты тела (по одному на MIME-тип).
     */
    private void extractAlternative(final Multipart multipart, final IBaseDataObject parent,
            final List<IBaseDataObject> children, final ExtractionCounters counters) throws IOException {

        List<Entity> parts = multipart.getBodyParts();
        if (parts.isEmpty()) {
            return;
        }

        Set<String> seenAlternativeMimes = new HashSet<>();

        Entity preferred = parts.getLast();
        if (preferred != null) {
            extractParts(preferred, parent, children, counters, seenAlternativeMimes);
        }

        for (int i = 0; i < parts.size() - 1; i++) {
            Entity part = parts.get(i);
            if (part != null) {
                extractParts(part, parent, children, counters, seenAlternativeMimes);
            }
        }
    }

    private void addBodyChild(final byte[] bytes, final Entity entity, final IBaseDataObject parent,
            final List<IBaseDataObject> children, final Set<String> seenAlternativeMimes) {

        if (bytes.length == 0) {
            return;
        }

        boolean htmlBody = TEXT_HTML.equalsIgnoreCase(entity.getMimeType());
        String normalizedMime = htmlBody ? TEXT_HTML : TEXT_PLAIN;

        if (seenAlternativeMimes != null && !seenAlternativeMimes.add(normalizedMime)) {
            return;
        }

        String charset = declaredCharset(entity);
        byte[] childBytes;
        String effectiveCharset;
        String form;

        if (htmlBody) {
            childBytes = bytes;
            effectiveCharset = charset;
            form = Form.HTML;
        } else {
            childBytes = toUtf8(charset, bytes);
            effectiveCharset = plainCharsetLabel(charset, childBytes != bytes);
            form = Form.TXT;
        }

        String childName = childBaseName(parent) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
        IBaseDataObject child = DataObjectFactory.getInstance(childBytes, childName, form);
        child.setFileType(form);
        child.putParameter("EMAIL_PART_TYPE", PART_TYPE_BODY);
        child.putParameter("BODY_MIME_TYPE", normalizedMime);

        if (effectiveCharset != null) {
            child.putParameter("BODY_CHARSET", effectiveCharset);
            child.setFontEncoding(effectiveCharset);
        } else if (!htmlBody) {
            child.setFontEncoding(CHARSET_UTF_8);
        }

        children.add(child);
    }

    private void addAttachmentChild(final byte[] bytes, final Entity entity, final String fallbackFilename,
            final IBaseDataObject parent, final List<IBaseDataObject> children,
            final ExtractionCounters counters) {

        String filename = resolveFilename(entity);
        if (filename == null) {
            filename = fallbackFilename;
        }

        String childName = childBaseName(parent) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
        IBaseDataObject child = DataObjectFactory.getInstance(bytes, childName, Form.UNKNOWN);
        child.putParameter("EMAIL_PART_TYPE", PART_TYPE_ATTACHMENT);
        if (entity.getMimeType() != null) {
            child.putParameter("ATTACHMENT_DECLARED_MIME", entity.getMimeType());
        }
        String charset = declaredCharset(entity);
        if (charset != null) {
            child.putParameter("ATTACHMENT_CHARSET", charset);
        }
        if (filename != null) {
            child.putParameter("ATTACHMENT_FILENAME", filename);
        }
        String disposition = entity.getDispositionType();
        if (disposition != null && !disposition.isBlank()) {
            child.putParameter("ATTACHMENT_DISPOSITION", disposition);
        }
        if (entity.getHeader() != null) {
            Field cid = entity.getHeader().getField("Content-ID");
            String contentId = cid == null ? null : normalizeContentId(cid.getBody());
            if (contentId != null) {
                child.putParameter("ATTACHMENT_CONTENT_ID", contentId);
            }
        }
        children.add(child);
        counters.attachmentCount++;
    }

    private static final class ExtractionCounters {
        int attachmentCount;
    }
}
