package kr.ac.pusan.pickle.oauth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OauthFlowRepository extends JpaRepository<OauthFlow, Long> {

    Optional<OauthFlow> findByStateHash(String stateHash);

    /**
     * Single-use consumption: the conditional UPDATE wins for exactly one
     * concurrent caller, mirroring the email-verification and refresh-token
     * rotation guards. Returns 0 when the state was already spent, and callers
     * must answer 410 to that rather than proceeding — a replayable state is a
     * replayable login.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OauthFlow f
               set f.consumedAt = :now
             where f.id = :id and f.consumedAt is null and f.expiresAt > :now
            """)
    int consume(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("delete from OauthFlow f where f.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
