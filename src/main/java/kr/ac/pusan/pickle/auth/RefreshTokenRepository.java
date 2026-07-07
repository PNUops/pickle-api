package kr.ac.pusan.pickle.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Returns 0 when the token was already revoked (concurrent rotation ⇒ reuse). */
    @Modifying
    @Query(value = """
            update refresh_tokens
               set revoked_at = now(), updated_at = now()
             where id = :id and revoked_at is null
            """, nativeQuery = true)
    int revokeIfActive(@Param("id") Long id);

    /**
     * Revokes the given token and every descendant in its rotation chain
     * (theft signal on reuse, docs/plan/07).
     */
    @Modifying
    @Query(value = """
            with recursive chain as (
                select id from refresh_tokens where id = :id
                union all
                select rt.id from refresh_tokens rt join chain c on rt.rotated_from = c.id
            )
            update refresh_tokens
               set revoked_at = now(), updated_at = now()
             where id in (select id from chain) and revoked_at is null
            """, nativeQuery = true)
    int revokeChainFrom(@Param("id") Long id);
}
