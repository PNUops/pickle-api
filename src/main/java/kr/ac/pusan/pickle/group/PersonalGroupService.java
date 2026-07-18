package kr.ac.pusan.pickle.group;

import java.util.Locale;
import kr.ac.pusan.pickle.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the implicit PERSONAL group (one OWNER row) when an account becomes
 * ACTIVE (docs/plan/02). Idempotent per user.
 */
@Service
public class PersonalGroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;

    public PersonalGroupService(GroupRepository groupRepository, GroupMemberRepository groupMemberRepository) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
    }

    @Transactional
    public void ensurePersonalGroup(User user) {
        if (groupMemberRepository.existsByUserIdAndGroupKind(user.getId(), GroupKind.PERSONAL)) {
            return;
        }
        String slug = uniqueSlug(slugify(user.getEmail().split("@", 2)[0], user.getId()));
        Group group = groupRepository.save(new Group(GroupKind.PERSONAL, user.getName(), slug, null));
        groupMemberRepository.save(new GroupMember(group, user.getId(), GroupMemberRole.OWNER));
    }

    private String uniqueSlug(String base) {
        String candidate = base;
        int suffix = 2;
        while (groupRepository.existsBySlugAndDeletedAtIsNull(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    /** Lowercase/digit/hyphen slug (default-subdomain constraint, docs/plan/02). */
    private static String slugify(String source, Long fallbackId) {
        String slug = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isBlank() ? "user-" + fallbackId : slug;
    }
}
