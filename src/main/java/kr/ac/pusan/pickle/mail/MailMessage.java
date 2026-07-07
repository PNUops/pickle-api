package kr.ac.pusan.pickle.mail;

/** Plain-text outbound mail. */
public record MailMessage(String to, String subject, String body) {
}
