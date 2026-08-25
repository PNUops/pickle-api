package kr.ac.pusan.pickle.identity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);

    Optional<UserIdentity> findByProviderAndUserId(IdentityProvider provider, Long userId);

    List<UserIdentity> findByUserIdOrderByLinkedAtAsc(Long userId);

    /**
     * Withdrawal path. The row's {@code on delete cascade} never fires there —
     * 탈퇴 sets {@code status = WITHDRAWN} and deletes no user row — so this has
     * to be called explicitly. Leaving the row behind would keep the withdrawn
     * address's {@code sub} live, and the next Google login would match it and
     * walk back into the withdrawn account.
     */
    int deleteByUserId(Long userId);
}
