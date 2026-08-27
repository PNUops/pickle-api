package kr.ac.pusan.pickle.notice;

import java.util.Optional;

/**
 * What a notice image is allowed to be, decided by reading the bytes.
 *
 * <p>The client's declared {@code Content-Type} is a claim, not evidence:
 * anything can be uploaded as {@code image/png}, and a browser that later
 * renders the stored bytes decides what they are by sniffing them, not by
 * believing us. So the type is determined here from the leading bytes, the
 * result is what gets stored and served, and anything outside the whitelist is
 * refused rather than stored under a corrected name.</p>
 *
 * <p>The four formats are the ones a notice body needs and every browser
 * renders. SVG is deliberately absent — it is a document that can carry script,
 * and serving one from the platform's own origin would be a stored XSS.</p>
 */
final class NoticeImageTypes {

    /** One image, matching the {@code notice_images_byte_size_check} constraint. */
    static final int MAX_BYTES = 2 * 1024 * 1024;

    /** Images per notice. A body needs a handful; a hundred is an accident. */
    static final int MAX_PER_NOTICE = 5;

    private static final byte[] PNG =
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87 = {'G', 'I', 'F', '8', '7', 'a'};
    private static final byte[] GIF89 = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

    private NoticeImageTypes() {
    }

    /** The media type these bytes actually are, or empty when unrecognised. */
    static Optional<String> sniff(byte[] bytes) {
        if (startsWith(bytes, 0, PNG)) {
            return Optional.of("image/png");
        }
        if (startsWith(bytes, 0, JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(bytes, 0, GIF87) || startsWith(bytes, 0, GIF89)) {
            return Optional.of("image/gif");
        }
        // A WebP file is a RIFF container whose form type sits at offset 8.
        if (startsWith(bytes, 0, RIFF) && startsWith(bytes, 8, WEBP)) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] magic) {
        if (bytes.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
