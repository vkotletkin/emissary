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
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.stream.Field;
import org.apache.james.mime4j.stream.MimeConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class EmailPlace extends ServiceProviderPlace {

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

        try (InputStream is = new ByteArrayInputStream(payload.data())) {
            Message message = builder.parseMessage(is);
            // 1. Заголовки письма
            if (message.getSubject() != null) {
                payload.putParameter("EMAIL_SUBJECT", message.getSubject());
            }
            if (message.getDate() != null) {
                payload.putParameter("EMAIL_DATE", message.getDate().toInstant().toString());
            }
            if (message.getMessageId() != null) {
                payload.putParameter("EMAIL_MESSAGE_ID", message.getMessageId());
            }
            if (message.getFrom() != null) {
                payload.putParameter("EMAIL_FROM", convertToEmailSubjects(message.getFrom()));
            }
            if (message.getTo() != null) {
                payload.putParameter("EMAIL_TO", convertToEmailSubjects(message.getTo().flatten()));
            }
            if (message.getCc() != null) {
                payload.putParameter("EMAIL_CC", convertToEmailSubjects(message.getCc().flatten()));
            }

            // 2. Извлекаем все части как дочерние объекты
            extractParts(message, payload, children);
            payload.putParameter("EMAIL_PARTS_COUNT", Integer.toString(children.size()));
            payload.setCurrentForm("EML_PROCESSED");
            message.dispose();
        } catch (Exception e) {
            logger.error("Failed to parse email {}", payload.shortName(), e);
            payload.addProcessingError("EmailPlace: " + e.getMessage());
            payload.setCurrentForm(Form.ERROR);
        }
        return children;
    }

    private void extractParts(final Entity entity, final IBaseDataObject parent, final List<IBaseDataObject> children) throws IOException {
        Body body = entity.getBody();
        switch (body) {
            // Ветка 1: Multipart-контейнер — рекурсивно обходим части
            case Multipart multipart -> {
                for (Entity part : multipart.getBodyParts()) {
                    extractParts(part, parent, children);
                }
            }
            // Ветка 2: Вложенное пересланное письмо (message/rfc822)
            case Message nestedMessage -> {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                new DefaultMessageWriter().writeMessage(nestedMessage, baos);
                addChild(baos.toByteArray(), entity, "forwarded.eml", "ATTACHMENT", parent, children);
            }
            // Ветка 3: Любая одиночная часть (текст тела, HTML, картинка, файл)
            case SingleBody singleBody -> {
                byte[] bytes;
                try (InputStream in = singleBody.getInputStream()) {
                    bytes = in.readAllBytes();
                }
                String filename = entity.getFilename() != null ? EmailUtils.decodeRaw(entity.getFilename()) : null;
                boolean isBodyPart = (filename == null) && !"attachment".equalsIgnoreCase(entity.getDispositionType())
                        && ("text/plain".equalsIgnoreCase(entity.getMimeType()) || "text/html".equalsIgnoreCase(entity.getMimeType()));
                addChild(bytes, entity, filename, isBodyPart ? "BODY" : "ATTACHMENT", parent, children);
            }
            default -> {
                // Unreal implementation
            }
        }
    }

    private void addChild(final byte[] bytes, final Entity entity, final String filename, final String partType, final IBaseDataObject parent,
            final List<IBaseDataObject> children) {

        String childName = parent.getFilename() + "-att-" + (children.size() + 1);
        IBaseDataObject child = DataObjectFactory.getInstance(bytes, childName, Form.UNKNOWN);
        child.putParameter("EMAIL_PART_TYPE", partType);

        if (entity.getMimeType() != null) {
            child.putParameter("ATTACHMENT_DECLARED_MIME", entity.getMimeType());
        }

        if (filename != null) {
            child.putParameter("ATTACHMENT_FILENAME", filename);
        }

        if (entity.getHeader() != null) {
            Field cid = entity.getHeader().getField("Content-ID");
            if (cid != null) {
                child.putParameter("ATTACHMENT_CONTENT_ID", cid.getBody());
            }
        }

        children.add(child);
    }

    private List<EmailSubject> convertToEmailSubjects(MailboxList mailboxList) {
        List<EmailSubject> emailFromList = new ArrayList<>();
        for (Mailbox mailbox : mailboxList) {
            emailFromList.add(EmailSubject.fromMailBox(mailbox));
        }
        return emailFromList;
    }
}
