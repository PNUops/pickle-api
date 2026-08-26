package kr.ac.pusan.pickle.mail;

/**
 * Outbound mail. {@code textBody} is always present and is what a client
 * unable to render HTML shows; {@code htmlBody} is optional and, when set,
 * makes the send a multipart/alternative carrying both.
 */
public record MailMessage(String to, String subject, String textBody, String htmlBody) {

    public MailMessage {
        htmlBody = htmlBody == null || htmlBody.isBlank() ? null : htmlBody;
    }

    /** Mail with no HTML part. */
    public static MailMessage text(String to, String subject, String textBody) {
        return new MailMessage(to, subject, textBody, null);
    }

    public boolean hasHtml() {
        return htmlBody != null;
    }
}
