package kr.ac.pusan.pickle.request.vm;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.stereotype.Component;

/**
 * Server-side validation of a user-chosen VM slug (hostname / SSH access
 * name, contract v0.12.0). Mirrors {@link kr.ac.pusan.pickle.publishing.SubdomainPolicy}
 * — pattern, reserved list and profanity denylist live in one place, shared by
 * request submission ({@code desiredSlug}) and admin approval
 * ({@code grantedSlug}). The reserved/profanity lists are intentionally the
 * SAME settings as the subdomain ones ({@code reserved_subdomains} /
 * {@code profanity_subdomains}). Uniqueness against {@code vms.hostname}
 * (including soft-deleted VMs — slugs are never recycled) is checked by the
 * services; the final gate is the DB unique constraint.
 */
@Component
public class VmSlugPolicy {

    /** RFC 1123-style label, lowercase alnum + hyphen, 3–40 chars, no leading/trailing hyphen. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$");

    private final SettingsService settingsService;

    public VmSlugPolicy(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** Validates a slug, appending field errors under {@code field}. Null is a no-op. */
    public void validateSlug(String slug, String field, List<FieldValidationError> errors) {
        if (slug == null) {
            return;
        }
        String value = slug.toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(value).matches()) {
            errors.add(new FieldValidationError(field,
                    "호스트명(슬러그)은 3~40자의 소문자·숫자·하이픈이어야 합니다 (하이픈으로 시작/끝 불가)."));
            return;
        }
        if (settingsService.stringList(SettingsService.RESERVED_SUBDOMAINS).contains(value)) {
            errors.add(new FieldValidationError(field,
                    "'" + value + "'은(는) 사용할 수 없는 예약어입니다."));
        }
        if (settingsService.stringList(SettingsService.PROFANITY_SUBDOMAINS).stream()
                .anyMatch(value::contains)) {
            errors.add(new FieldValidationError(field, "사용할 수 없는 단어가 포함된 호스트명입니다."));
        }
    }
}
