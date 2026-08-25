package kr.ac.pusan.pickle.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserOrgRoleRepository
        extends JpaRepository<UserOrgRole, UserOrgRole.Key> {

    /** Everything one account administers. Empty for a non-admin. */
    List<UserOrgRole> findByUserId(Long userId);

    Optional<UserOrgRole> findByUserIdAndOrgId(Long userId, Long orgId);

    /** Who holds a role in an org, for notification fan-out. */
    List<UserOrgRole> findByOrgIdAndRole(Long orgId, UserRole role);

    void deleteByUserId(Long userId);
}
