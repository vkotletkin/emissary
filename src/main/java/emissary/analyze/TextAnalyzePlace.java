package emissary.analyze;

import com.google.i18n.phonenumbers.PhoneNumberMatch;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.Leniency;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import emissary.config.Configurator;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Анализатор текста на фазе {@code ANALYZE} для формы {@code TEXT}.
 * <p>
 * Извлекает из текста и складывает в параметры {@link IBaseDataObject}:
 * <ul>
 * <li>{@code EXTRACTED_EMAIL} / {@code EXTRACTED_EMAIL_DOMAIN} — email-адреса и их домены</li>
 * <li>{@code EXTRACTED_PHONE} — номера телефонов (международные и российские, с нормализацией)</li>
 * <li>{@code EXTRACTED_IPV4} / {@code EXTRACTED_IPV6} — IPv4 и IPv6 адреса</li>
 * <li>{@code EXTRACTED_URL} — веб-ссылки ({@code http://}, {@code https://})</li>
 * </ul>
 * Форма объекта ({@code TEXT}) не изменяется, чтобы агент мог продолжить цепочку {@code ANALYZE} и перейти в {@code IO}.
 */
public class TextAnalyzePlace extends Analyzer {

    // --- Email ---
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}._%+-])([a-zA-Z0-9._%+\\-]{1,64}@([a-zA-Z0-9\\-]+\\.)+[a-zA-Z]{2,24})(?![\\p{L}\\p{N}_\\-])");

    // --- URL ---
    private static final Pattern URL_PATTERN = Pattern.compile(
            "\\bhttps?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
            Pattern.CASE_INSENSITIVE);

    // --- IPv4 (strict 0..255 per octet, not preceded/followed by digits or dots) ---
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "(?<![0-9.])(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}"
                    + "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(?![0-9.])");

    // --- IPv6 (standard and compressed forms) ---
    private static final Pattern IPV6_CANDIDATE_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}:])(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}(?![\\p{L}\\p{N}:])"
                    + "|(?<![\\p{L}\\p{N}:])(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}(?![\\p{L}\\p{N}:])");

    protected boolean extractEmails = true;
    protected boolean extractPhones = true;
    protected boolean extractIpAddresses = true;
    protected boolean extractUrls = true;
    protected String defaultPhoneRegion = "RU";
    protected Leniency phoneLeniency = Leniency.VALID;
    protected int maxMatchesPerCategory = 500;

    public TextAnalyzePlace(final String configInfo, final String dir, final String placeLoc) throws IOException {
        super(configInfo, dir, placeLoc);
        configurePlace();
    }

    public TextAnalyzePlace(final String configInfo, final String placeLoc) throws IOException {
        super(configInfo, placeLoc);
        configurePlace();
    }

    public TextAnalyzePlace(final String configInfo) throws IOException {
        super(configInfo, "TextAnalyzePlace.example.com:8001");
        configurePlace();
    }

    public TextAnalyzePlace(final InputStream configInfo) throws IOException {
        super(configInfo);
        configurePlace();
    }

    public TextAnalyzePlace(final Configurator configs) throws IOException {
        super(configs);
        configurePlace();
    }

    public TextAnalyzePlace() throws IOException {
        super();
        configurePlace();
    }

    private static boolean isValidIpv6(final String candidate) {
        try {
            return InetAddress.getByName(candidate).getAddress().length == 16;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailingPunctuation(final String value) {
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == ')' || c == ']' || c == '>' || c == '\'' || c == '"') {
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }

    private static void putAllParameters(final IBaseDataObject payload, final String key, final Set<String> values) {
        if (values.isEmpty()) {
            return;
        }
        for (String val : values) {
            payload.appendParameter(key, val);
        }
    }

    protected void configurePlace() {
        if (configG != null) {
            this.extractEmails = configG.findBooleanEntry("EXTRACT_EMAILS", true);
            this.extractPhones = configG.findBooleanEntry("EXTRACT_PHONES", true);
            this.extractIpAddresses = configG.findBooleanEntry("EXTRACT_IP_ADDRESSES", true);
            this.extractUrls = configG.findBooleanEntry("EXTRACT_URLS", true);
            this.defaultPhoneRegion = configG.findStringEntry("DEFAULT_PHONE_REGION", "RU");
            String leniencyStr = configG.findStringEntry("PHONE_LENIENCY", "VALID");
            try {
                this.phoneLeniency = Leniency.valueOf(leniencyStr.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                this.phoneLeniency = Leniency.VALID;
            }
            this.maxMatchesPerCategory = Math.max(1, configG.findIntEntry("MAX_MATCHES_PER_CATEGORY", 500));
        }
    }

    @Override
    public void process(final IBaseDataObject payload) throws ResourceException {
        byte[] data = getPreferredData(payload);
        if (data == null || data.length == 0) {
            return;
        }

        String text = new String(data, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return;
        }

        if (this.extractEmails) {
            extractEmailsAndDomains(text, payload);
        }
        if (this.extractPhones) {
            extractPhoneNumbers(text, payload);
        }
        if (this.extractIpAddresses) {
            extractIps(text, payload);
        }
        if (this.extractUrls) {
            extractUrls(text, payload);
        }
        if (this.extractEntities) {
            extractNamedEntities(text, payload);
        }
        if (this.extractRussianIds) {
            extractInnAndSnils(text, payload);
        }
    }

    private void extractEmailsAndDomains(final String text, final IBaseDataObject payload) {
        Set<String> emails = new LinkedHashSet<>();
        Set<String> domains = new LinkedHashSet<>();

        Matcher matcher = EMAIL_PATTERN.matcher(text);
        while (matcher.find() && emails.size() < this.maxMatchesPerCategory) {
            String email = stripTrailingPunctuation(matcher.group(1)).toLowerCase(Locale.ROOT);
            int atIdx = email.indexOf('@');
            if (atIdx > 0 && atIdx < email.length() - 1) {
                emails.add(email);
                domains.add(email.substring(atIdx + 1));
            }
        }

        putAllParameters(payload, "EXTRACTED_EMAIL", emails);
        putAllParameters(payload, "EXTRACTED_EMAIL_DOMAIN", domains);
    }

    private void extractPhoneNumbers(final String text, final IBaseDataObject payload) {
        Set<String> phones = new LinkedHashSet<>();
        PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();

        Iterable<PhoneNumberMatch> matches = phoneUtil.findNumbers(
                text,
                this.defaultPhoneRegion,
                this.phoneLeniency,
                this.maxMatchesPerCategory);

        for (PhoneNumberMatch match : matches) {
            if (phones.size() >= this.maxMatchesPerCategory) {
                break;
            }
            String formatted = phoneUtil.format(match.number(), PhoneNumberFormat.E164);
            if (formatted != null && !formatted.isEmpty()) {
                phones.add(formatted);
            }
        }

        putAllParameters(payload, "EXTRACTED_PHONE", phones);
    }

    private void extractIps(final String text, final IBaseDataObject payload) {
        Set<String> ipv4s = new LinkedHashSet<>();
        Set<String> ipv6s = new LinkedHashSet<>();

        Matcher v4Matcher = IPV4_PATTERN.matcher(text);
        while (v4Matcher.find() && ipv4s.size() < this.maxMatchesPerCategory) {
            ipv4s.add(v4Matcher.group());
        }

        Matcher v6Matcher = IPV6_CANDIDATE_PATTERN.matcher(text);
        while (v6Matcher.find() && ipv6s.size() < this.maxMatchesPerCategory) {
            String candidate = v6Matcher.group();
            if (isValidIpv6(candidate)) {
                ipv6s.add(candidate.toLowerCase(Locale.ROOT));
            }
        }

        putAllParameters(payload, "EXTRACTED_IPV4", ipv4s);
        putAllParameters(payload, "EXTRACTED_IPV6", ipv6s);
    }

    private void extractUrls(final String text, final IBaseDataObject payload) {
        Set<String> urls = new LinkedHashSet<>();

        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find() && urls.size() < this.maxMatchesPerCategory) {
            String url = stripTrailingPunctuation(matcher.group());
            if (url.length() > 8) {
                urls.add(url);
            }
        }

        putAllParameters(payload, "EXTRACTED_URL", urls);
    }

    private void extractNamedEntities(final String text, final IBaseDataObject payload) {
        Set<String> persons = new LinkedHashSet<>();
        Set<String> orgs = new LinkedHashSet<>();

        collectGroupMatches(PERSON_FULL_FIO_PATTERN, text, persons);
        collectGroupMatches(PERSON_INITIALS_AFTER_PATTERN, text, persons);
        collectGroupMatches(PERSON_INITIALS_BEFORE_PATTERN, text, persons);

        collectGroupMatches(ORG_RU_PATTERN, text, orgs);
        collectGroupMatches(ORG_INTL_PATTERN, text, orgs);

        putAllParameters(payload, "EXTRACTED_ENTITY_PERSON", persons);
        putAllParameters(payload, "EXTRACTED_ENTITY_ORG", orgs);
    }

    private void collectGroupMatches(final Pattern pattern, final String text, final Set<String> target) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && target.size() < this.maxMatchesPerCategory) {
            String match = matcher.group(1).trim().replaceAll("\\s{2,}", " ");
            if (!match.isEmpty()) {
                target.add(match);
            }
        }
    }

    private void extractInnAndSnils(final String text, final IBaseDataObject payload) {
        Set<String> inns = new LinkedHashSet<>();
        Set<String> snilsSet = new LinkedHashSet<>();

        Matcher innMatcher = INN_CANDIDATE_PATTERN.matcher(text);
        while (innMatcher.find() && inns.size() < this.maxMatchesPerCategory) {
            String candidate = innMatcher.group(1);
            if (isValidInnChecksum(candidate)) {
                inns.add(candidate);
            }
        }

        Matcher snilsMatcher = SNILS_PATTERN.matcher(text);
        while (snilsMatcher.find() && snilsSet.size() < this.maxMatchesPerCategory) {
            String candidate = snilsMatcher.group(1);
            if (isValidSnilsChecksum(candidate)) {
                snilsSet.add(candidate.replace(' ', '-'));
            }
        }

        putAllParameters(payload, "EXTRACTED_INN", inns);
        putAllParameters(payload, "EXTRACTED_SNILS", snilsSet);
    }
}
