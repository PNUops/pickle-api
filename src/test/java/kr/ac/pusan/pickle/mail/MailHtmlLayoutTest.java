package kr.ac.pusan.pickle.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the email-client survival rules of the shared HTML mail layout: exact
 * escaping, the plain-text-to-HTML conversion (paragraphs, soft-wrap reflow,
 * list promotion), the single-anchor invariant that keeps one-time tokens
 * safe from link-prefetching gateways, and the no-remote-resources rule with
 * the emblem as the sole exception.
 */
class MailHtmlLayoutTest {

    private static final String LOGO_URL = "https://pickle.pusan.ac.kr/pnu-logo.png";

    @Test
    void escapesHtmlSpecialCharacters() {
        String html = MailHtmlLayout.render("제목 <b>", "본문 <script>alert('x')</script> \"끝\"");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(html).contains("제목 &lt;b&gt;");
        assertThat(html).contains("&quot;끝&quot;");
    }

    @Test
    void doesNotDoubleEscapeAmpersand() {
        String html = MailHtmlLayout.render("제목", "A & B");

        assertThat(html).contains("A &amp; B");
        assertThat(html).doesNotContain("&amp;amp;");
        assertThat(html).doesNotContain("&amp;lt;");
    }

    @Test
    void blankLineSplitsParagraphsAndSoftWrapJoinsWithSpace() {
        String html = MailHtmlLayout.render("제목", "첫 문단 첫 줄\n첫 문단 둘째 줄\n\n둘째 문단");

        assertThat(html).contains(">첫 문단 첫 줄 첫 문단 둘째 줄</p>");
        assertThat(html).contains(">둘째 문단</p>");
        assertThat(html).doesNotContain("<br");
    }

    @Test
    void collapsesConsecutiveBlankLinesIntoOneParagraphBoundary() {
        String twoBlankLines = MailHtmlLayout.render("제목", "가\n\n\n나");
        String oneBlankLine = MailHtmlLayout.render("제목", "가\n\n나");

        assertThat(twoBlankLines).isEqualTo(oneBlankLine);
        assertThat(twoBlankLines).contains(">가</p>");
        assertThat(twoBlankLines).contains(">나</p>");
    }

    @Test
    void normalizesCarriageReturnsAndTrimsEdges() {
        String crlf = MailHtmlLayout.render("제목", "\n가\r\n\r\n나\r\n");
        String lf = MailHtmlLayout.render("제목", "가\n\n나");

        assertThat(crlf).isEqualTo(lf);
    }

    @Test
    void promotesConsecutiveDashLinesToList() {
        String html = MailHtmlLayout.render("제목",
                "신청이 접수되었습니다.\n\n- 신청 목적: 딥러닝 학습\n- 기간: 30일");

        assertThat(html).contains(">신청이 접수되었습니다.</p>");
        assertThat(countOccurrences(html, "<ul ")).isEqualTo(1);
        assertThat(countOccurrences(html, "<li ")).isEqualTo(2);
        assertThat(html).contains(">신청 목적: 딥러닝 학습</li>");
        assertThat(html).contains(">기간: 30일</li>");
        assertThat(html).doesNotContain("- 신청 목적");
    }

    @Test
    void splitsMixedBlockIntoTextAndListRuns() {
        String html = MailHtmlLayout.render("제목", "안내 문구\n- 항목 하나\n마무리 문구");

        assertThat(html).contains(">안내 문구</p>");
        assertThat(html).contains(">항목 하나</li>");
        assertThat(html).contains(">마무리 문구</p>");
        assertThat(countOccurrences(html, "<ul ")).isEqualTo(1);
    }

    @Test
    void withCtaRendersExactlyOneAnchorAndSpanFallback() {
        String url = "https://pickle.pusan.ac.kr/verify?token=abc123";
        String html = MailHtmlLayout.render("이메일 인증", "인증을 진행해 주세요.",
                new MailHtmlLayout.Cta("이메일 인증하기", url));

        assertThat(countOccurrences(html, "<a ")).isEqualTo(1);
        assertThat(html).contains(">이메일 인증하기</a>");
        assertThat(countOccurrences(html, url)).isEqualTo(2);
        assertThat(html).contains("word-break:break-all");
        assertThat(html).contains(url + "</span>");
    }

    @Test
    void withoutCtaRendersNoAnchorAtAll() {
        String html = MailHtmlLayout.render("제목", "본문");

        assertThat(html).doesNotContain("<a ");
    }

    @Test
    void doesNotAutoLinkBareUrlsInBody() {
        String html = MailHtmlLayout.render("제목",
                "자세한 내용은 https://pickle.pusan.ac.kr/docs 를 참고해 주세요.");

        assertThat(html).doesNotContain("<a ");
        assertThat(html).contains("https://pickle.pusan.ac.kr/docs");
    }

    @Test
    void rendersDocumentSkeleton() {
        String html = MailHtmlLayout.render("제목", "본문");

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<html lang=\"ko\">");
        assertThat(html).contains("<meta charset=\"UTF-8\" />");
        assertThat(html).contains("<meta name=\"viewport\"");
        assertThat(html).contains("<meta name=\"color-scheme\" content=\"light dark\" />");
        assertThat(html).contains("width=\"600\"");
        assertThat(html).contains("max-width:600px");
    }

    @Test
    void pairsBackgroundColorStyleWithBgcolorAttribute() {
        String html = MailHtmlLayout.render("제목", "본문");

        assertThat(html).contains("bgcolor=\"#f1f5f9\"");
        assertThat(html).contains("bgcolor=\"#ffffff\"");
        assertThat(html).contains("bgcolor=\"#005baa\"");
        assertThat(html).contains("background-color:#f1f5f9");
        assertThat(html).contains("background-color:#ffffff");
        assertThat(html).contains("background-color:#005baa");
    }

    @Test
    void putsHeadingOnTitleBandNotInBody() {
        String html = MailHtmlLayout.render("사용 종료 안내", "본문");

        assertThat(html).contains("color:#ffffff;\">사용 종료 안내</div>");
        assertThat(html).doesNotContain("<h1");
    }

    @Test
    void allowsOnlyTheEmblemAsRemoteResource() {
        String html = MailHtmlLayout.render("제목", "본문",
                new MailHtmlLayout.Cta("열기", "https://pickle.pusan.ac.kr/x"));

        assertThat(countOccurrences(html, "<img")).isEqualTo(1);
        assertThat(html).contains("src=\"" + LOGO_URL + "\"");
        assertThat(html).doesNotContain("<link");
        assertThat(html).doesNotContain("@import");
        assertThat(html).doesNotContain("<style");
        assertThat(html).doesNotContain("fonts.googleapis");
    }

    @Test
    void includesWordmarkFooterAndSendOnlyNotice() {
        String html = MailHtmlLayout.render("제목", "본문");

        assertThat(html).contains(">Pickle</div>");
        assertThat(html).contains("부산대학교 클라우드 플랫폼");
        assertThat(html)
                .contains("부산대학교 정보컴퓨터공학부 PNUops</p>")
                .contains("본 메일은 발신 전용입니다. 회신하신 내용은 확인되지 않습니다.</p>")
                .doesNotContain("Pickle 운영팀")
                .doesNotContain("운영: 부산대학교 정보컴퓨터공학부 PNUops");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
