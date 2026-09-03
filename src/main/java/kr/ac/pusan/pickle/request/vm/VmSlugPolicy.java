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

    /** What a generated hostname falls back to when the seed yields nothing usable. */
    private static final String DEFAULT_SEED = "vm";

    private final SettingsService settingsService;

    /**
     * Turns a free-text name into the leading part of a generated hostname:
     * lowercase, alphanumerics and hyphens only, short enough to leave room for
     * the random suffix.
     *
     * <p>Most display names here are Korean and collapse to nothing, so the
     * caller passes a fallback that still identifies something — the owning
     * workspace — rather than letting every generated hostname read
     * {@code vm-????}. The workspace slug used to do this job and was always
     * ASCII; it no longer exists, so the fallback is built from the id.
     */
    public static String sanitizeSeed(String name, long workspaceId) {
        String seed = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (seed.length() > 20) {
            seed = seed.substring(0, 20).replaceAll("-+$", "");
        }
        if (seed.isBlank() || seed.length() < 2) {
            return fallbackSeed(workspaceId);
        }
        return seed;
    }

    /** The seed used when a display name yields none, or yields one the policy refuses. */
    public static String fallbackSeed(long workspaceId) {
        return DEFAULT_SEED + workspaceId;
    }

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
                    "소문자와 숫자, 하이픈만 쓸 수 있고 3~40자여야 합니다. 하이픈으로 시작하거나 끝낼 수 없습니다."));
            return;
        }
        if (settingsService.stringList(SettingsService.RESERVED_SUBDOMAINS).contains(value)) {
            errors.add(new FieldValidationError(field,
                    "'" + value + "'은(는) 예약된 이름이라 쓸 수 없습니다."));
        }
        if (settingsService.stringList(SettingsService.PROFANITY_SUBDOMAINS).stream()
                .anyMatch(value::contains)) {
            errors.add(new FieldValidationError(field, "쓸 수 없는 단어가 들어 있습니다."));
        }
    }
}
