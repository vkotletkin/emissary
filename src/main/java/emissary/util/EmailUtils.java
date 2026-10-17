package emissary.util;

import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.codec.DecoderUtil;

public class EmailUtils {

    private EmailUtils() {}

    public static String decodeRaw(final String raw) {
        return raw != null ? DecoderUtil.decodeEncodedWords(raw, DecodeMonitor.SILENT) : null;
    }

}
