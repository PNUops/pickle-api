package kr.ac.pusan.pickle.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the shared HTML layout for outbound mails: a centered emblem and
 * wordmark header above a 600px card whose title sits alone in a PNU-blue
 * band, the converted body below it, an optional call-to-action button, and
 * a send-only footer signed by the operating organization.
 *
 * <p>Email-client constraints shape everything here: table layout with 100%
 * inline styles (no {@code <style>} block — Gmail strips class selectors),
 * both {@code background-color} and the {@code bgcolor} attribute so
 * dark-mode auto-inversion never leaves text invisible, a system font stack
 * (no web fonts), and the emblem as the single permitted remote image with
 * the text wordmark beside it as the images-blocked fallback.</p>
 *
 * <p>Body conversion is line-based and predictable: blank lines (one or
 * more consecutive) separate paragraphs, a single newline inside a
 * paragraph is a soft wrap joined with a space so hard-wrapped plain text
 * reflows naturally, and consecutive lines starting with {@code "- "} are
 * promoted to a {@code <ul>} list. Bare URLs in the body stay plain text —
 * the CTA is deliberately the only anchor in the whole document, because
 * security gateways prefetch every link and a second anchor around a
 * one-time token would consume it before the user clicks.</p>
 */
public final class MailHtmlLayout {

    /** Single call-to-action button placed under the body text. */
    public record Cta(String label, String url) {
    }

    // The official Pusan National University main color (pusan.ac.kr color system).
    private static final String PNU_BLUE = "#005baa";

    // Neutral colors are the console design tokens (console/src/index.css).
    private static final String SLATE_100 = "#f1f5f9";
    private static final String SLATE_200 = "#e2e8f0";
    private static final String SLATE_400 = "#94a3b8";
    private static final String SLATE_500 = "#64748b";
    private static final String SLATE_700 = "#334155";
    private static final String SLATE_900 = "#0f172a";

    private static final String FONT_STACK =
            "-apple-system, BlinkMacSystemFont, 'Apple SD Gothic Neo', 'Malgun Gothic',"
                    + " 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";

    private static final String LOGO_URL = "https://pickle.pusan.ac.kr/pnu-logo.png";

    private static final String BODY_TEXT_STYLE =
            "font-family:" + FONT_STACK + ";font-size:15px;line-height:1.7;color:" + SLATE_700 + ";";

    private static final String FOOTER_TEXT_STYLE =
            "font-family:" + FONT_STACK + ";font-size:12px;line-height:1.6;";

    private MailHtmlLayout() {
    }

    /**
     * Renders the full HTML document for one mail.
     *
     * @param heading  mail title without the {@code "[Pickle] "} prefix
     * @param textBody Korean plain text: blank lines between paragraphs,
     *                 soft-wrapped lines inside them, optional {@code "- "} list lines
     * @param cta      optional single action button, or {@code null} for none
     * @return a self-contained HTML document
     */
    public static String render(String heading, String textBody, Cta cta) {
        String escapedHeading = escape(heading);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n")
                .append("<html lang=\"ko\">\n")
                .append("<head>\n")
                .append("<meta charset=\"UTF-8\" />\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n")
                .append("<meta name=\"color-scheme\" content=\"light dark\" />\n")
                .append("<title>").append(escapedHeading).append("</title>\n")
                .append("</head>\n")
                .append("<body style=\"margin:0;padding:0;background-color:").append(SLATE_100)
                .append(";\" bgcolor=\"").append(SLATE_100).append("\">\n")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"")
                .append(" border=\"0\" style=\"background-color:").append(SLATE_100)
                .append(";\" bgcolor=\"").append(SLATE_100).append("\">\n")
                .append("<tr>\n<td align=\"center\" style=\"padding:0 16px;\">\n")
                .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\"")
                .append(" border=\"0\" style=\"width:100%;max-width:600px;\">\n");
        appendHeader(html);
        appendTitleBand(html, escapedHeading);
        appendCardBody(html, textBody, cta);
        appendFooter(html);
        html.append("</table>\n</td>\n</tr>\n</table>\n</body>\n</html>\n");
        return html.toString();
    }

    /**
     * Renders the layout without a call-to-action button.
     *
     * @param heading  mail title without the {@code "[Pickle] "} prefix
     * @param textBody Korean plain text body
     * @return a self-contained HTML document
     */
    public static String render(String heading, String textBody) {
        return render(heading, textBody, null);
    }

    /**
     * Centered lockup above the card: emblem and wordmark side by side, the
     * subtitle beneath. The wordmark text is the images-blocked fallback.
     *
     * <p>The three sizes are measured against each other, not picked: a 30px
     * wordmark, a 30px-tall emblem and a 12px gap make the top line 130px
     * wide, against 131px for the name beneath it, so the two rows end
     * together and the lockup reads as one block. Changing any one of them
     * means measuring the pair again.</p>
     */
    private static void appendHeader(StringBuilder html) {
        html.append("<tr>\n<td align=\"center\" style=\"padding:36px 0 20px;\">\n")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"")
                .append(" align=\"center\" style=\"margin:0 auto;\">\n<tr>\n")
                .append("<td style=\"vertical-align:middle;padding-right:12px;\">")
                // PNU emblem, original ratio 125x122, matched to the wordmark's height
                .append("<img src=\"").append(LOGO_URL)
                .append("\" width=\"31\" height=\"30\" alt=\"\" style=\"display:block;border:0;\" />")
                .append("</td>\n<td style=\"vertical-align:middle;\">")
                .append("<div style=\"font-family:").append(FONT_STACK)
                .append(";font-size:30px;font-weight:bold;line-height:1.1;letter-spacing:0.5px;color:")
                .append(SLATE_900).append(";\">Pickle</div>")
                .append("</td>\n</tr>\n</table>\n")
                .append("<div style=\"margin-top:7px;font-family:").append(FONT_STACK)
                .append(";font-size:12px;line-height:1.4;color:").append(SLATE_500)
                .append(";\">부산대학교 클라우드 플랫폼</div>\n")
                .append("</td>\n</tr>\n");
    }

    /** The card's title zone: the heading alone on a PNU-blue band. */
    private static void appendTitleBand(StringBuilder html, String escapedHeading) {
        html.append("<tr>\n<td style=\"background-color:").append(PNU_BLUE)
                .append(";border-radius:12px 12px 0 0;padding:22px 28px;\" bgcolor=\"")
                .append(PNU_BLUE).append("\">\n")
                .append("<div style=\"font-family:").append(FONT_STACK)
                .append(";font-size:22px;font-weight:bold;line-height:1.35;color:#ffffff;\">")
                .append(escapedHeading).append("</div>\n")
                .append("</td>\n</tr>\n");
    }

    private static void appendCardBody(StringBuilder html, String textBody, Cta cta) {
        html.append("<tr>\n<td style=\"background-color:#ffffff;border-left:1px solid ").append(SLATE_200)
                .append(";border-right:1px solid ").append(SLATE_200)
                .append(";border-bottom:1px solid ").append(SLATE_200)
                // Bottom padding is 16px: the last block element always carries a
                // 16px bottom margin, so the visible gap matches the 30px top.
                .append(";border-radius:0 0 12px 12px;padding:30px 28px 16px;\" bgcolor=\"#ffffff\">\n");
        appendBody(html, textBody);
        if (cta != null) {
            appendCta(html, cta);
        }
        html.append("</td>\n</tr>\n");
    }

    /**
     * Converts the plain-text body: blank-line-separated blocks become
     * paragraphs, soft-wrapped lines inside a block are joined with a
     * space, and consecutive {@code "- "} lines become a {@code <ul>} list.
     */
    private static void appendBody(StringBuilder html, String textBody) {
        String normalized = textBody.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return;
        }
        for (String block : normalized.split("\n{2,}")) {
            appendBlock(html, block);
        }
    }

    /** One blank-line-delimited block: runs of list lines and text lines. */
    private static void appendBlock(StringBuilder html, String block) {
        List<String> textRun = new ArrayList<>();
        List<String> listRun = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (line.startsWith("- ")) {
                flushTextRun(html, textRun);
                listRun.add(line.substring(2));
            } else {
                flushListRun(html, listRun);
                textRun.add(line);
            }
        }
        flushTextRun(html, textRun);
        flushListRun(html, listRun);
    }

    private static void flushTextRun(StringBuilder html, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        html.append("<p style=\"margin:0 0 16px;").append(BODY_TEXT_STYLE).append("\">")
                .append(escape(String.join(" ", lines)))
                .append("</p>\n");
        lines.clear();
    }

    private static void flushListRun(StringBuilder html, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        html.append("<ul style=\"margin:0 0 16px;padding:0 0 0 22px;\">\n");
        for (String item : items) {
            html.append("<li style=\"margin:0 0 6px;").append(BODY_TEXT_STYLE).append("\">")
                    .append(escape(item)).append("</li>\n");
        }
        html.append("</ul>\n");
        items.clear();
    }

    /**
     * Table-based button plus the copy-paste fallback URL. The fallback is a
     * {@code <span>}, never an anchor: the document must contain exactly one
     * anchor so link-prefetching mail gateways cannot consume a one-time
     * token through a duplicate link.
     */
    private static void appendCta(StringBuilder html, Cta cta) {
        String escapedUrl = escape(cta.url());
        html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"")
                .append(" align=\"center\" style=\"margin:28px auto 8px;\">\n")
                .append("<tr>\n<td style=\"background-color:").append(PNU_BLUE)
                .append(";border-radius:8px;\" bgcolor=\"").append(PNU_BLUE).append("\">")
                .append("<a href=\"").append(escapedUrl)
                .append("\" style=\"display:inline-block;padding:14px 44px;font-family:").append(FONT_STACK)
                .append(";font-size:15px;font-weight:bold;line-height:1;color:#ffffff;text-decoration:none;\">")
                .append(escape(cta.label())).append("</a>")
                .append("</td>\n</tr>\n</table>\n")
                .append("<p style=\"margin:20px 0 16px;").append(FOOTER_TEXT_STYLE)
                .append("color:").append(SLATE_500).append(";text-align:center;\">")
                .append("버튼이 열리지 않으면 아래 주소를 복사해 브라우저 주소창에 붙여넣어 주세요.<br />\n")
                .append("<span style=\"display:inline-block;margin-top:6px;padding:8px 12px;background-color:")
                .append(SLATE_100).append(";border-radius:6px;word-break:break-all;color:")
                .append(PNU_BLUE).append(";\">").append(escapedUrl).append("</span></p>\n");
    }

    private static void appendFooter(StringBuilder html) {
        html.append("<tr>\n<td align=\"center\" style=\"padding:28px 4px 40px;\">\n")
                .append("<p style=\"margin:0 0 6px;").append(FOOTER_TEXT_STYLE)
                .append("color:").append(SLATE_500).append(";\">")
                .append("부산대학교 정보컴퓨터공학부 PNUops</p>\n")
                .append("<p style=\"margin:0;").append(FOOTER_TEXT_STYLE)
                .append("color:").append(SLATE_400).append(";\">")
                .append("본 메일은 발신 전용입니다. 회신하신 내용은 확인되지 않습니다.</p>\n")
                .append("</td>\n</tr>\n");
    }

    /** HTML-escapes text; {@code &} first so nothing double-escapes. */
    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
