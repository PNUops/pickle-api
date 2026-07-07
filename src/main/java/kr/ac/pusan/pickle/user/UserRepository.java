package kr.ac.pusan.pickle.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Case-insensitive by virtue of the {@code citext} column. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
