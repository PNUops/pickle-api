package kr.ac.pusan.pickle.user;

import java.util.List;
import kr.ac.pusan.pickle.orgs.OrgScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single writer of {@code user_org_roles} and of the effective role on
 * {@code users} that has to agree with it.
 *
 * <p>V90 moved the org-tier invariant out of a CHECK constraint, because it now
 * spans two tables. This class is what keeps it: every path that changes what an
 * account administers goes through here, and each one ends by recomputing
 * {@code users.role} from the rows that remain.
 */
@Service
public class UserOrgRoleService {

    private final UserOrgRoleRepository userOrgRoleRepository;

    public UserOrgRoleService(UserOrgRoleRepository userOrgRoleRepository) {
        this.userOrgRoleRepository = userOrgRoleRepository;
    }

    /**
     * Wholesale replacement: the account administers exactly this one org, in
     * this role. The sys tier's own user-edit screen works this way.
     */
    @Transactional
    public void replaceWithSingle(User user, Long orgId, UserRole role) {
        userOrgRoleRepository.deleteByUserId(user.getId());
        userOrgRoleRepository.flush();
        userOrgRoleRepository.save(new UserOrgRole(user.getId(), orgId, role));
        applyEffectiveRole(user, List.of(role));
    }

    /** The account administers nothing: it is not an org-tier account. */
    @Transactional
    public void clear(User user) {
        userOrgRoleRepository.deleteByUserId(user.getId());
        userOrgRoleRepository.flush();
    }

    /** What the account administers, for a scope decision made outside a request. */
    @Transactional(readOnly = true)
    public OrgScope scopeOf(Long userId) {
        return OrgScope.of(userOrgRoleRepository.findByUserId(userId).stream()
                .map(UserOrgRole::getOrgId)
                .toList());
    }

    /**
     * {@code users.role} is the highest role held across the org rows. With no
     * rows left the account is no longer org-tier and falls to {@code USER};
     * the caller decides whether that is a role change worth a token bump.
     */
    void applyEffectiveRole(User user, List<UserRole> heldRoles) {
        UserRole effective = heldRoles.stream()
                .max(java.util.Comparator.naturalOrder())
                .orElse(UserRole.USER);
        user.setRole(effective);
    }
}
