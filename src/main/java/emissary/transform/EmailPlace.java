package emissary.transform;

import emissary.core.DataObjectFactory;
import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;
import emissary.transform.model.EmailSubject;
import emissary.util.EmailUtils;

import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
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
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Разбирает RFC822. Вложения и вложенные {@code message/rfc822} становятся детьми. Текстовое тело подменяет байты
 * родителя: {@code text/plain} получает форму {@code TXT}, иначе {@code text/html} получает форму {@code HTML}. Второй
 * вид остаётся alternate view. Без текстового тела форма становится {@code EML-PROCESSED}, байты остаются письмом.
 */
public class EmailPlace extends ServiceProviderPlace {

    private static final String TEXT_PLAIN = "text/plain";
    private static final String TEXT_HTML = "text/html";
    private static final String MULTIPART_ALTERNATIVE = "multipart/alternative";
    private static final String CHARSET_UTF_8 = "UTF-8";
    private static final String FORM_TXT = "TXT";
    private static final String FORM_EML_PROCESSED = "EML-PROCESSED";
    private static final String ATTACHMENT = "ATTACHMENT";
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

        String parentName = parent.getFilename();
        if (parentName == null || parentName.isBlank()) {
            parentName = parent.shortName();
        }

        if (parentName == null || parentName.isBlank()) {
            return "email";
        }

        return parentName;
    }

    /**
     * mime4j {@code getFilename()} видит только filename из Content-Disposition. Имя из {@code Content-Type: name} забираем
     * отдельно.
     */
    private static String resolveFilename(final Entity entity) {
        String filename = entity.getFilename();
        if ((filename == null || filename.isBlank()) && entity.getHeader() != null) {
            Field raw = entity.getHeader().getField("Content-Type");
            if (raw instanceof ContentTypeField contentType) {
                filename = contentType.getParameter("name");
            }
        }
        if (filename == null || filename.isBlank()) {
            return null;
        }
        return EmailUtils.decodeRaw(filename);
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

        String charset = contentType.getCharset();
        if (charset == null || charset.isBlank()) {
            return null;
        }

        return charset.trim();
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
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("<") && value.endsWith(">") && value.length() > 2) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.isEmpty() ? null : value;
    }

    private static String viewName(final Entity entity) {
        return TEXT_HTML.equalsIgnoreCase(entity.getMimeType()) ? TEXT_HTML : TEXT_PLAIN;
    }

    private static boolean hasView(final List<TextView> textViews, final IBaseDataObject parent, final String name) {
        if (parent.getAlternateView(name) != null) {
            return true;
        }
        for (TextView view : textViews) {
            if (name.equals(view.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Plain, если он есть, становится содержимым письма. Иначе эту роль берёт HTML. Второй вид остаётся alternate view. Без
     * текстового тела форма {@code EML-PROCESSED}: байты остаются письмом, а {@code EML} повторно не разберётся. Суффикс
     * {@code -PROCESSED} не обрывает маршрут, и родитель доходит до вывода.
     */
    private static void publishBody(final IBaseDataObject parent, final List<TextView> textViews) {

        TextView html = findView(textViews, TEXT_HTML);
        TextView plain = findView(textViews, TEXT_PLAIN);
        TextView primary = plain != null ? plain : html;

        if (primary == null) {
            parent.setCurrentForm(FORM_EML_PROCESSED);
            return;
        }

        parent.setData(primary.data());
        boolean htmlBody = TEXT_HTML.equals(primary.name());
        String form = htmlBody ? Form.HTML : FORM_TXT;
        parent.setCurrentForm(form);
        parent.setFileType(form);
        rememberCharset(parent, primary);
        if (htmlBody) {
            if (primary.charsetValue() != null) {
                parent.setFontEncoding(primary.charsetValue());
            }
        } else {
            parent.setFontEncoding(CHARSET_UTF_8);
        }
        TextView secondary = htmlBody ? plain : html;
        if (secondary != null) {
            parent.addAlternateView(secondary.name(), secondary.data());
            rememberCharset(parent, secondary);
        }
    }

    private static TextView findView(final List<TextView> textViews, final String name) {
        for (TextView view : textViews) {
            if (name.equals(view.name())) {
                return view;
            }
        }
        return null;
    }

    private static void rememberCharset(final IBaseDataObject parent, final TextView view) {
        if (view.charsetParameter() != null) {
            parent.putParameter(view.charsetParameter(), view.charsetValue());
        }
    }

    @Override
    protected List<String> getAlternateViewsModified() {
        return List.of(TEXT_PLAIN, TEXT_HTML);
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(Form.HTML, FORM_TXT, FORM_EML_PROCESSED, Form.ERROR, Form.EMPTY);
    }

    @Override
    protected boolean changesPrimaryView() {
        return true;
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

            List<TextView> textViews = new ArrayList<>();
            extractParts(message, payload, children, textViews, false);
            publishBody(payload, textViews);
            payload.putParameter("EMAIL_ATTACHMENT_COUNT", Integer.toString(children.size()));
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
        if (message.getSubject() != null) {
            payload.putParameter("EMAIL_SUBJECT", message.getSubject());
        }
        if (message.getDate() != null) {
            payload.putParameter("EMAIL_DATE", message.getDate().toInstant().toString());
        }
        if (message.getMessageId() != null) {
            payload.putParameter("EMAIL_MESSAGE_ID", message.getMessageId());
        }
        putMailboxes(payload, "EMAIL_FROM", message.getFrom());
        putMailboxes(payload, "EMAIL_TO", message.getTo());
        putMailboxes(payload, "EMAIL_CC", message.getCc());
        putMailboxes(payload, "EMAIL_BCC", message.getBcc());
    }

    private void putMailboxes(final IBaseDataObject payload, final String key, final MailboxList mailboxes) {
        if (mailboxes == null || mailboxes.isEmpty()) {
            return;
        }
        payload.putParameter(key, convertToEmailSubjects(mailboxes));
    }

    private void putMailboxes(final IBaseDataObject payload, final String key, final AddressList addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return;
        }
        putMailboxes(payload, key, addresses.flatten());
    }

    private void extractParts(final Entity entity, final IBaseDataObject parent, final List<IBaseDataObject> children,
            final List<TextView> textViews, final boolean inAlternative) throws IOException {

        Body body = entity.getBody();
        if (body == null) {
            return;
        }

        switch (body) {
            case Multipart multipart -> {
                if (MULTIPART_ALTERNATIVE.equalsIgnoreCase(entity.getMimeType())) {
                    extractAlternative(multipart, parent, children, textViews);
                    return;
                }
                for (Entity part : multipart.getBodyParts()) {
                    if (part != null) {
                        extractParts(part, parent, children, textViews, inAlternative);
                    }
                }
            }
            case Message nestedMessage ->
                addAttachment(DefaultMessageWriter.asBytes(nestedMessage), entity, FORWARDED_MESSAGE_NAME, parent,
                        children);

            case SingleBody singleBody -> {
                byte[] bytes = readBytes(singleBody);
                boolean textBody = isTextBody(entity, inAlternative);
                if (textBody && addTextView(textViews, parent, entity, bytes)) {
                    return;
                }
                if (textBody && inAlternative) {
                    return;
                }
                addAttachment(bytes, entity, null, parent, children);
            }
            default ->
                logger.debug("Skipping unsupported MIME body {} on {}", body.getClass().getName(), parent.shortName());
        }
    }

    /**
     * RFC 2046: последняя часть {@code multipart/alternative} — предпочтительное представление и занимает view первой.
     * Более ранние текстовые части дописывают только ещё пустые view. Пустое тело слот не занимает.
     */
    private void extractAlternative(final Multipart multipart, final IBaseDataObject parent, final List<IBaseDataObject> children,
            final List<TextView> textViews) throws IOException {

        List<Entity> parts = multipart.getBodyParts();
        if (parts.isEmpty()) {
            return;
        }

        Entity preferred = parts.getLast();
        if (preferred != null) {
            extractParts(preferred, parent, children, textViews, true);
        }

        for (int i = 0; i < parts.size() - 1; i++) {
            Entity part = parts.get(i);
            if (part != null) {
                extractParts(part, parent, children, textViews, true);
            }
        }
    }

    /**
     * @return true, если байты приняты как тело и их не нужно делать вложением
     */
    private boolean addTextView(final List<TextView> textViews, final IBaseDataObject parent, final Entity entity, final byte[] bytes) {

        if (bytes.length == 0) {
            return true;
        }

        String name = viewName(entity);
        if (hasView(textViews, parent, name)) {
            return false;
        }

        if (TEXT_PLAIN.equals(name)) {
            String charset = declaredCharset(entity);
            byte[] encoded = toUtf8(charset, bytes);
            String label = plainCharsetLabel(charset, encoded != bytes);
            textViews.add(new TextView(name, encoded, label == null ? null : "EMAIL_PLAIN_CHARSET", label));
            return true;
        }

        String charset = declaredCharset(entity);
        textViews.add(new TextView(name, bytes, charset == null ? null : "EMAIL_HTML_CHARSET", charset));
        return true;
    }

    private void addAttachment(final byte[] bytes, final Entity entity, final String fallbackFilename, final IBaseDataObject parent,
            final List<IBaseDataObject> children) {

        String filename = resolveFilename(entity);
        if (filename == null) {
            filename = fallbackFilename;
        }

        String childName = childBaseName(parent) + EXTENDING_FILE_SUFFIX + (children.size() + 1);
        IBaseDataObject child = DataObjectFactory.getInstance(bytes, childName, Form.UNKNOWN);
        child.putParameter("EMAIL_PART_TYPE", ATTACHMENT);
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
    }

    private List<EmailSubject> convertToEmailSubjects(final MailboxList mailboxList) {
        List<EmailSubject> emailFromList = new ArrayList<>();
        for (Mailbox mailbox : mailboxList) {
            if (mailbox != null) {
                emailFromList.add(EmailSubject.fromMailBox(mailbox));
            }
        }
        return emailFromList;
    }

    private record TextView(String name, byte[] data, String charsetParameter, String charsetValue) {

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TextView(String name1, byte[] data1, String parameter, String value))) {
                return false;
            }
            return Objects.equals(name, name1)
                    && Arrays.equals(data, data1)
                    && Objects.equals(charsetParameter, parameter)
                    && Objects.equals(charsetValue, value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, Arrays.hashCode(data), charsetParameter, charsetValue);
        }

        @Override
        public @NonNull String toString() {
            return "TextView[name=" + name
                    + ", data=" + Arrays.toString(data)
                    + ", charsetParameter=" + charsetParameter
                    + ", charsetValue=" + charsetValue + "]";
        }
    }
}
