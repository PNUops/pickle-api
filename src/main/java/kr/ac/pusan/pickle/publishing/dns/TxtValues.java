package kr.ac.pusan.pickle.publishing.dns;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * TXT record data in the two spellings that have to agree: the value as it was
 * given, and the presentation form a zone API stores and returns.
 *
 * <p>Every other type this platform writes reads back the way it was written,
 * so a provider can compare the desired and current data as plain strings and
 * skip the write when they match. TXT does not: DNS carries it as a
 * character-string, and the presentation form quotes it and escapes the quote
 * and backslash inside. A provider comparing a given value against what the
 * API returned would find them different every time and rewrite the record on
 * every reconcile, which spends quota and bumps a serial for no change.</p>
 *
 * <p><strong>One character-string, so at most 255 octets.</strong> Longer
 * values exist in DNS as several character-strings that readers concatenate,
 * and this class deliberately does not build them. The split is on octets, so
 * a multi-byte character can straddle two chunks; assembling that correctly
 * means escaping at the byte level, and getting it subtly wrong corrupts a
 * value rather than failing. Nothing this platform writes needs it — the
 * values are tokens — so the limit is refused here and again as a field error
 * where the value is accepted, and the day a long value is genuinely needed
 * this becomes a deliberate piece of work instead of a latent bug.</p>
 */
public final class TxtValues {

    /** Maximum octets in one DNS character-string (RFC 1035 §3.3). */
    public static final int MAX_OCTETS = 255;

    private TxtValues() {
    }

    /** Octets a value occupies on the wire — what {@link #MAX_OCTETS} bounds. */
    public static int octets(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** A value in presentation form: quoted, with quote and backslash escaped. */
    public static String encode(String value) {
        int size = octets(value);
        if (size > MAX_OCTETS) {
            throw new DnsProviderException("TXT 값이 " + MAX_OCTETS + "옥텟을 넘습니다(" + size + ").");
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }

    /**
     * Presentation form back to the given value: the quoted parts unescaped and
     * concatenated, so a value some other tool wrote as several
     * character-strings still compares equal to the one string it represents.
     * Input carrying no quotes comes back as it came, which is what makes this
     * safe to run over a value from either side.
     */
    public static String decode(String presentation) {
        String value = presentation.strip();
        if (value.indexOf('"') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean inQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!inQuotes) {
                inQuotes = c == '"';
                continue;
            }
            if (c == '\\' && i + 1 < value.length()) {
                out.append(value.charAt(++i));
            } else if (c == '"') {
                inQuotes = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** {@link #decode} over a provider's data values, order preserved. */
    public static List<String> decodeAll(List<String> presentation) {
        List<String> out = new ArrayList<>(presentation.size());
        for (String value : presentation) {
            out.add(decode(value));
        }
        return out;
    }

    /** {@link #encode} over given values, order preserved. */
    public static List<String> encodeAll(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            out.add(encode(value));
        }
        return out;
    }
}
