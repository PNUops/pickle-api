package kr.ac.pusan.pickle.publishing.dns;

import java.util.Locale;

/** Owner-name spelling helpers: providers speak absolute names, the platform relative ones. */
public final class DnsNames {

    private DnsNames() {
    }

    /** {@code foo.example.dev.} → {@code foo.example.dev}, lower-cased. */
    public static String relative(String name) {
        if (name == null) {
            return null;
        }
        String value = name.strip().toLowerCase(Locale.ROOT);
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    /** {@code foo.example.dev} → {@code foo.example.dev.} (what a zone API expects). */
    public static String absolute(String name) {
        String value = relative(name);
        return value + ".";
    }
}
