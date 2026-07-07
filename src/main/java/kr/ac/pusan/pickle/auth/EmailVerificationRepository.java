package kr.ac.pusan.pickle.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findByTokenHashAndPurpose(String tokenHash, VerificationPurpose purpose);
}
