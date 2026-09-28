package kr.ac.pusan.pickle.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserOrgRoleRepository
        extends JpaRepository<UserOrgRole, UserOrgRole.Key> {

    /** Every role one account holds, in a stable order. Empty for a plain user. */
    List<UserOrgRole> findByUserIdOrderByOrgIdAsc(Long userId);

    Optional<UserOrgRole> findByUserIdAndOrgId(Long userId, Long orgId);

    /** Who holds a role in an org, for notification fan-out. */
    List<UserOrgRole> findByOrgIdAndRole(Long orgId, UserRole role);

    /**
     * Sets the request-mail choice on one row, and only that column: the role
     * paths write the same row, and a whole-entity write-back from here could
     * resurrect a role a concurrent grant or revoke just changed. Turning it on
     * matches only a role that may approve. Returns the rows changed.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            update user_org_roles set request_mail = :enabled
             where user_id = :userId and org_id = :orgId
               and (not :enabled or role::text in ('ORG_ADMIN', 'ORG_MANAGER'))
            """)
    int updateRequestMail(@org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("orgId") Long orgId,
            @org.springframework.data.repository.query.Param("enabled") boolean enabled);

    /** Who the org has chosen to be mailed about new requests (V133). */
    List<UserOrgRole> findByOrgIdAndRequestMailTrue(Long orgId);

    void deleteByUserId(Long userId);
}
