package emissary.transform;

import emissary.core.Form;
import emissary.core.IBaseDataObject;
import emissary.core.ResourceException;
import emissary.place.ServiceProviderPlace;

import jakarta.annotation.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Обработчик {@code HTML} (включая HTML-тела писем из {@link EmailPlace} и HTML-вложения) на фазе {@code TRANSFORM} (на
 * базе Jsoup).
 * <ul>
 * <li>Извлекает {@code <title>} и ключевые {@code <meta>} теги в параметры {@code HTML_TITLE},
 * {@code HTML_DESCRIPTION}, {@code HTML_AUTHOR}, {@code HTML_GENERATOR}.</li>
 * <li>Извлекает все ссылки {@code <a href="...">} в параметр {@code HTML_LINK} (чтобы ссылки не терялись после удаления
 * тегов).</li>
 * <li>Удаляет {@code <script>}, {@code <style>}, {@code <noscript>}, {@code <svg>}, {@code <head>} и преобразует
 * блочные теги ({@code <br>
 * }, {@code 
 * <p>
 * }, {@code <div>}, {@code 
 * <tr>
 * }, {@code 
 * <li>}, {@code <h1..h6>}) в читаемый текст с сохранением структуры строк.</li>
 * <li>Заменяет данные объекта на извлечённый UTF-8 текст и меняет форму с {@link Form#HTML} на {@link Form#TXT} (чтобы
 * далее текст прошёл через {@link TextCleanPlace} -&gt; {@link emissary.analyze.TextAnalyzePlace}).</li>
 * </ul>
 */
public class HtmlToTextPlace extends ServiceProviderPlace {

    private static final String CHARSET_UTF_8 = "UTF-8";

    protected boolean extractLinks = true;
    protected int maxLinks = 500;

    public HtmlToTextPlace(final InputStream configStream, final String theDir, final String thePlaceLocation) throws IOException {
        super(configStream, theDir, thePlaceLocation);
        configurePlace();
    }

    public HtmlToTextPlace(final InputStream configStream) throws IOException {
        super(configStream);
        configurePlace();
    }

    public HtmlToTextPlace(final String cfgInfo, final String dir, final String placeLoc) throws IOException {
        super(cfgInfo, dir, placeLoc);
        configurePlace();
    }

    public HtmlToTextPlace(final String cfgInfo) throws IOException {
        super(cfgInfo, "HtmlToTextPlace.example.com:8001");
        configurePlace();
    }

    public HtmlToTextPlace() throws IOException {
        super();
        configurePlace();
    }

    private static void extractHtmlMetadata(final Document doc, final IBaseDataObject payload) {
        String title = trimToNull(doc.title());
        if (title != null) {
            payload.putParameter("HTML_TITLE", title);
        }

        putMetaContent(doc, payload, "description", "HTML_DESCRIPTION");
        putMetaContent(doc, payload, "author", "HTML_AUTHOR");
        putMetaContent(doc, payload, "generator", "HTML_GENERATOR");
        putMetaContent(doc, payload, "keywords", "HTML_KEYWORDS");
    }

    private static void putMetaContent(final Document doc, final IBaseDataObject payload,
            final String metaName, final String paramKey) {
        Element meta = doc.selectFirst("meta[name~=(?i)^" + metaName + "$]");
        if (meta != null) {
            String content = trimToNull(meta.attr("content"));
            if (content != null) {
                payload.putParameter(paramKey, content);
            }
        }
    }

    private static String extractFormattedText(final Document doc) {
        doc.select("script, style, noscript, svg, template, head").remove();

        for (Element br : doc.select("br")) {
            br.after("\n");
        }
        for (Element block : doc.select("p, div, tr, li, h1, h2, h3, h4, h5, h6, blockquote, pre, section, article, header, footer")) {
            block.prepend("\n");
            block.append("\n");
        }
        for (Element cell : doc.select("td, th")) {
            cell.append(" | ");
        }

        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        doc.outputSettings(settings);
        return doc.wholeText();
    }

    @Nullable
    private static String resolveCharsetName(final IBaseDataObject payload) {
        String enc = trimToNull(payload.getFontEncoding());
        if (enc == null) {
            enc = trimToNull(payload.getParameterAsString("BODY_CHARSET"));
        }
        if (enc == null) {
            enc = trimToNull(payload.getParameterAsString("ATTACHMENT_CHARSET"));
        }
        if (enc != null) {
            try {
                return Charset.forName(enc).name();
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static String trimToNull(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    protected void configurePlace() {
        if (configG != null) {
            this.extractLinks = configG.findBooleanEntry("EXTRACT_LINKS", true);
            this.maxLinks = Math.max(1, configG.findIntEntry("MAX_LINKS", 500));
        }
    }

    @Override
    protected List<String> getOutputForms() {
        return List.of(Form.TXT, Form.EMPTY, Form.ERROR);
    }

    @Override
    public void process(final IBaseDataObject payload) throws ResourceException {
        if (payload.dataLength() == 0) {
            payload.setCurrentForm(Form.EMPTY);
            return;
        }

        payload.setFileTypeIfEmpty(Form.HTML);
        String charsetName = resolveCharsetName(payload);

        try (InputStream is = new ByteArrayInputStream(payload.data())) {
            Document doc = Jsoup.parse(is, charsetName, "");

            extractHtmlMetadata(doc, payload);

            if (this.extractLinks) {
                extractHrefLinks(doc, payload);
            }

            String text = extractFormattedText(doc);
            if (text.isBlank()) {
                payload.setCurrentForm(Form.EMPTY);
                return;
            }

            byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
            payload.setData(utf8Bytes);
            payload.setFontEncoding(CHARSET_UTF_8);
            payload.replaceCurrentForm(Form.TXT);
        } catch (Exception e) {
            logger.error("HtmlToTextPlace failed to parse {}", payload.shortName(), e);
            payload.addProcessingError("HtmlToTextPlace: " + e.getMessage());
            payload.replaceCurrentForm(Form.ERROR);
        }
    }

    private void extractHrefLinks(final Document doc, final IBaseDataObject payload) {
        Set<String> links = new LinkedHashSet<>();
        Elements anchors = doc.select("a[href]");
        for (Element a : anchors) {
            if (links.size() >= this.maxLinks) {
                break;
            }
            String href = trimToNull(a.attr("href"));
            if (href != null && (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:"))) {
                links.add(href);
            }
        }
        for (String link : links) {
            payload.appendParameter("HTML_LINK", link);
        }
    }
}
