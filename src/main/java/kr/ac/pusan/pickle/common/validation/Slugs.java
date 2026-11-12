package kr.ac.pusan.pickle.common.validation;

/**
 * Shared slug rule for orgs and workspaces (contract: lowercase/digit/hyphen,
 * no leading/trailing hyphen, max 40 chars — slugs feed default subdomains).
 */
public final class Slugs {

    public static final String PATTERN = "^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$";
    public static final String MESSAGE =
            "slug는 영문 소문자와 숫자, 하이픈으로 최대 40자까지 쓸 수 있어요. 하이픈으로 시작하거나 끝날 수는 없어요.";

    private Slugs() {
    }
}
