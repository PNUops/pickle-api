package kr.ac.pusan.pickle.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Case-insensitive by virtue of the {@code citext} column. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** The admins of an org (deletion notifications, docs/plan/03). */
    List<User> findByRoleAndOrgId(UserRole role, Long orgId);

    /** All users holding a global role (SYS_ADMIN notification fan-out, M5). */
    List<User> findByRole(UserRole role);
}
