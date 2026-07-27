package kr.ac.pusan.pickle.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthReverificationRepository extends JpaRepository<AuthReverification, Long> {

    Optional<AuthReverification> findByTokenHash(String tokenHash);
}
