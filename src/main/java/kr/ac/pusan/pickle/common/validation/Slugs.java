package kr.ac.pusan.pickle.common.validation;

/**
 * Shared slug rule for orgs and workspaces (contract: lowercase/digit/hyphen,
 * no leading/trailing hyphen, max 40 chars — slugs feed default subdomains).
 */
public final class Slugs {

    public static final String PATTERN = "^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$";
    public static final String MESSAGE =
            "slug는 소문자·숫자·하이픈만 사용할 수 있습니다 (하이픈으로 시작/끝 불가, 최대 40자).";

    private Slugs() {
    }
}
