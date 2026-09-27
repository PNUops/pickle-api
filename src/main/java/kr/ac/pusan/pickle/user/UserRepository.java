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

    /**
     * Whether an account other than {@code id} holds this 학번, ignoring case
     * as the V131 index does.
     */
    boolean existsByStudentNoIgnoreCaseAndIdNot(String studentNo, Long id);

    boolean existsByStudentNoIgnoreCase(String studentNo);

    /** The one account holding this 학번, ignoring case as the V131 index does. */
    Optional<User> findByStudentNoIgnoreCase(String studentNo);

    /** All users holding a global role (SYS_ADMIN notification fan-out). */
    List<User> findByRole(UserRole role);
}
