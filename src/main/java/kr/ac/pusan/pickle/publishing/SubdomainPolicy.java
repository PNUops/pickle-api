package kr.ac.pusan.pickle.publishing;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.stereotype.Component;

/**
 * Server-side validation of a platform subdomain label and its root domain
 * Shared by request submission and admin approval so the rules —
 * RFC 1123 label, reserved list, profanity denylist, allowed root — live in one
 * place. Uniqueness against live domains is checked at publish time (the final
 * gate is the partial unique index).
 */
@Component
public class SubdomainPolicy {

    /** RFC 1123 label, lowercase alnum + hyphen, 3–40 chars, no leading/trailing hyphen. */
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$");

    private final SettingsService settingsService;

    public SubdomainPolicy(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** Validates a subdomain label, appending field errors under {@code field}. */
    public void validateLabel(String label, String field, List<FieldValidationError> errors) {
        if (label == null) {
            return;
        }
        String value = label.toLowerCase(Locale.ROOT);
        if (!LABEL.matcher(value).matches()) {
            errors.add(new FieldValidationError(field,
                    "서브도메인은 소문자·숫자·하이픈 3~40자여야 하며 하이픈으로 시작/끝날 수 없습니다."));
            return;
        }
        if (settingsService.stringList(SettingsService.RESERVED_SUBDOMAINS).contains(value)) {
            errors.add(new FieldValidationError(field,
                    "'" + value + "'은(는) 사용할 수 없는 예약 서브도메인입니다."));
        }
        if (settingsService.stringList(SettingsService.PROFANITY_SUBDOMAINS).stream()
                .anyMatch(value::contains)) {
            errors.add(new FieldValidationError(field, "사용할 수 없는 단어가 포함된 서브도메인입니다."));
        }
    }

    /** Validates a root domain is one of the allowed roots, under {@code field}. */
    public void validateRootDomain(String rootDomain, String field, List<FieldValidationError> errors) {
        if (rootDomain != null
                && !settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS).contains(rootDomain)) {
            errors.add(new FieldValidationError(field,
                    "'" + rootDomain + "'은(는) 허용된 루트 도메인이 아닙니다."));
        }
    }

    /** The default root domain (first allowed root) — used for AUTO subdomains. */
    public String defaultRootDomain() {
        List<String> roots = settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS);
        return roots.isEmpty() ? null : roots.getFirst();
    }

    /**
     * True when {@code fqdn} is (or is under) any allowed platform root — a custom
     * domain must not squat a platform zone (publish contract).
     */
    public boolean isUnderPlatformRoot(String fqdn) {
        String value = fqdn.toLowerCase(Locale.ROOT);
        return settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS).stream()
                .map(root -> root.toLowerCase(Locale.ROOT))
                .anyMatch(root -> value.equals(root) || value.endsWith("." + root));
    }
}
