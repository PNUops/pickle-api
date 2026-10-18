package kr.ac.pusan.pickle.user;

import kr.ac.pusan.pickle.orgs.OrgScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Membership presence for account-role transitions; never an institution work authorization. */
@Service
public class UserOrgRoleService {
    private final UserOrgRoleRepository roles;
    public UserOrgRoleService(UserOrgRoleRepository roles) { this.roles = roles; }

    @Transactional(readOnly = true)
    public OrgScope scopeOf(Long userId) {
        return OrgScope.of(roles.findByUserIdOrderByOrgIdAsc(userId).stream().map(UserOrgRole::getOrgId).toList());
    }
}
