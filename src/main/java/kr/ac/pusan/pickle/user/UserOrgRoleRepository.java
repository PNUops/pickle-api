package kr.ac.pusan.pickle.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface UserOrgRoleRepository
        extends Repository<UserOrgRole, UserOrgRole.Key> {

    /** Every role one account holds, in a stable order. Empty for a plain user. */
    List<UserOrgRole> findByUserIdOrderByOrgIdAsc(Long userId);

    Optional<UserOrgRole> findByUserIdAndOrgId(Long userId, Long orgId);

}
