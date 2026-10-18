package emissary.transform.model;

import emissary.util.EmailUtils;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;
import java.util.Optional;

@JsonNaming(PropertyNamingStrategies.UpperSnakeCaseStrategy.class)
public record EmailSubject(String address, String name, String domain) implements Comparable<EmailSubject>, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Comparator<EmailSubject> ORDER = Comparator
            .comparing(EmailSubject::address, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(EmailSubject::name, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(EmailSubject::domain, Comparator.nullsFirst(Comparator.naturalOrder()));

    public static EmailSubject fromMailBox(Mailbox mailbox) {
        String address = Optional.ofNullable(mailbox.getAddress()).orElse("");
        String domain = Optional.ofNullable(mailbox.getDomain()).orElse("");
        String name = Optional.ofNullable(mailbox.getName()).orElse("");
        return new EmailSubject(EmailUtils.decodeRaw(address),
                EmailUtils.decodeRaw(name),
                EmailUtils.decodeRaw(domain));
    }

    @Override
    public int compareTo(@NonNull EmailSubject other) {
        return ORDER.compare(this, other);
    }
}
