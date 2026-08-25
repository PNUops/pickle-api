package kr.ac.pusan.pickle.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<User> findByPublicId(UUID publicId);

    /** Case-insensitive by virtue of the {@code citext} column. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** All users holding a global role (SYS_ADMIN notification fan-out). */
    List<User> findByRole(UserRole role);
}
