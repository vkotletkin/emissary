package emissary.analyze;

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
 * <li>{@code EXTRACTED_ENTITY_PERSON} — ФИО (русские полные и с инициалами)</li>
 * <li>{@code EXTRACTED_ENTITY_ORG} — организации (ООО, АО, ПАО, ЗАО, ИП, LLC, Inc., Ltd., GmbH и др.)</li>
 * <li>{@code EXTRACTED_INN} / {@code EXTRACTED_SNILS} — ИНН (с проверкой контрольной суммы) и СНИЛС</li>
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

    // --- Телефоны (RU +7/8 и международные с разделителями/скобками) ---
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}+])"
                    + "(?:(?:\\+7|8)[\\s\\-()]*\\d{3}[\\s\\-()]*\\d{3}[\\s\\-]*\\d{2}[\\s\\-]*\\d{2}"
                    + "|\\+[1-9]\\d{0,2}[\\s\\-()]*\\d{2,4}[\\s\\-()]*\\d{3,4}[\\s\\-]*\\d{2,4}(?:[\\s\\-]*\\d{1,4})?)"
                    + "(?![\\p{L}\\p{N}])");

    // --- ФИО (Фамилия Имя Отчество ИЛИ Фамилия И. О. / И.О. Фамилия) ---
    private static final Pattern PERSON_FULL_FIO_PATTERN = Pattern.compile(
            "(?<![\\p{L}])([А-ЯЁ][а-яё]{2,25}(?:-[А-ЯЁ][а-яё]{2,25})?\\s+"
                    + "[А-ЯЁ][а-яё]{1,20}\\s+"
                    + "[А-ЯЁ][а-яё]{3,25}(?:вич|вна|тич|чна|ьич|ich|vna))(?![\\p{L}])");

    private static final Pattern PERSON_INITIALS_AFTER_PATTERN = Pattern.compile(
            "(?<![\\p{L}])([А-ЯЁ][а-яё]{2,25}(?:-[А-ЯЁ][а-яё]{2,25})?\\s+"
                    + "[А-ЯЁ]\\.\\s?[А-ЯЁ]\\.)");

    private static final Pattern PERSON_INITIALS_BEFORE_PATTERN = Pattern.compile(
            "(?<![\\p{L}])([А-ЯЁ]\\.\\s?[А-ЯЁ]\\.\\s+[А-ЯЁ][а-яё]{2,25}(?:-[А-ЯЁ][а-яё]{2,25})?)(?![\\p{L}])");

    // --- Организации (РФ: ООО, АО, ПАО, ЗАО, ОАО, ФГУП, МУП, АНО, НКО, ИП + международные: LLC, Inc., Ltd., GmbH, Corp.) ---
    private static final Pattern ORG_RU_PATTERN = Pattern.compile(
            "(?<![\\p{L}])((?:ООО|ПАО|АО|ЗАО|ОАО|ФГУП|МУП|ГУП|АНО|НКО|ФГБУ|ИП)\\s+"
                    + "(?:[«\"„“][\\p{L}\\p{N}\\s\\-.,&+№/]{2,60}[»\"”]"
                    + "|[А-ЯЁA-Z][\\p{L}\\p{N}\\-]{1,35}(?:\\s+[А-ЯЁA-Zа-яё][\\p{L}\\p{N}\\-]{1,35}){0,2}))");

    private static final Pattern ORG_INTL_PATTERN = Pattern.compile(
            "(?<![\\p{L}])([A-Z][A-Za-z0-9&\\-]{1,30}(?:\\s+[A-Z][A-Za-z0-9&\\-]{1,30}){0,3}\\s+"
                    + "(?:LLC|Inc\\.|Ltd\\.|GmbH|Corp\\.|PLC|S\\.A\\.|B\\.V\\.))");

    // --- ИНН (10 или 12 цифр) и СНИЛС (XXX-XXX-XXX YY) ---
    private static final Pattern INN_CANDIDATE_PATTERN = Pattern.compile("(?<!\\d)(\\d{10}|\\d{12})(?!\\d)");
    private static final Pattern SNILS_PATTERN = Pattern.compile("(?<!\\d)(\\d{3}-\\d{3}-\\d{3}[\\s\\-]\\d{2})(?!\\d)");

    private static final int[] INN_WEIGHTS_10 = {2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] INN_WEIGHTS_11 = {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] INN_WEIGHTS_12 = {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8};

    protected boolean extractEmails = true;
    protected boolean extractPhones = true;
    protected boolean extractIpAddresses = true;
    protected boolean extractUrls = true;
    protected boolean extractEntities = true;
    protected boolean extractRussianIds = true;
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

    protected void configurePlace() {
        if (configG != null) {
            this.extractEmails = configG.findBooleanEntry("EXTRACT_EMAILS", true);
            this.extractPhones = configG.findBooleanEntry("EXTRACT_PHONES", true);
            this.extractIpAddresses = configG.findBooleanEntry("EXTRACT_IP_ADDRESSES", true);
            this.extractUrls = configG.findBooleanEntry("EXTRACT_URLS", true);
            this.extractEntities = configG.findBooleanEntry("EXTRACT_ENTITIES", true);
            this.extractRussianIds = configG.findBooleanEntry("EXTRACT_RUSSIAN_IDS", true);
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

        Matcher matcher = PHONE_PATTERN.matcher(text);
        while (matcher.find() && phones.size() < this.maxMatchesPerCategory) {
            String raw = matcher.group();
            String normalized = normalizePhone(raw);
            if (normalized != null) {
                phones.add(normalized);
            }
        }

        putAllParameters(payload, "EXTRACTED_PHONE", phones);
    }

    private static String normalizePhone(final String raw) {
        StringBuilder digits = new StringBuilder();
        boolean hasLeadingPlus = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '+' && digits.isEmpty()) {
                hasLeadingPlus = true;
            } else if (Character.isDigit(c)) {
                digits.append(c);
            }
        }

        int len = digits.length();
        if (len < 10 || len > 15) {
            return null;
        }

        String d = digits.toString();
        // Нормализация российских номеров 8XXXXXXXXXX -> +7XXXXXXXXXX
        if (len == 11 && (d.charAt(0) == '8' || d.charAt(0) == '7')) {
            return "+7" + d.substring(1);
        }
        if (hasLeadingPlus) {
            return "+" + d;
        }
        return d;
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

    private static boolean isValidIpv6(final String candidate) {
        try {
            return InetAddress.getByName(candidate).getAddress().length == 16;
        } catch (Exception e) {
            return false;
        }
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

    public static boolean isValidInnChecksum(final String inn) {
        if (inn == null || inn.chars().distinct().count() <= 1) {
            return false;
        }
        if (inn.length() == 10) {
            return innControlDigit(inn, INN_WEIGHTS_10) == (inn.charAt(9) - '0');
        }
        if (inn.length() == 12) {
            int d11 = innControlDigit(inn, INN_WEIGHTS_11);
            int d12 = innControlDigit(inn, INN_WEIGHTS_12);
            return d11 == (inn.charAt(10) - '0') && d12 == (inn.charAt(11) - '0');
        }
        return false;
    }

    private static int innControlDigit(final String inn, final int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (inn.charAt(i) - '0') * weights[i];
        }
        return (sum % 11) % 10;
    }

    public static boolean isValidSnilsChecksum(final String snilsRaw) {
        String digits = snilsRaw.replaceAll("\\D", "");
        if (digits.length() != 11 || digits.chars().distinct().count() <= 1) {
            return false;
        }
        int numberPart = Integer.parseInt(digits.substring(0, 9));
        if (numberPart <= 1001998) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (digits.charAt(i) - '0') * (9 - i);
        }
        int check = sum < 100 ? sum : (sum == 100 || sum == 101 ? 0 : (sum % 101) % 100);
        int declared = Integer.parseInt(digits.substring(9, 11));
        return check == declared;
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
}
