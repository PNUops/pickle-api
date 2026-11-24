package kr.ac.pusan.pickle.config;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.Test;

class MfaPropertiesTest {

    @Test
    void eachSwitchCoversItsOwnTierAndNeverTheOrdinaryUser() {
        for (boolean sys : new boolean[] {false, true}) {
            for (boolean org : new boolean[] {false, true}) {
                MfaProperties properties = new MfaProperties(sys, org);
                for (UserRole role : UserRole.values()) {
                    boolean expected = role.isSysTier() ? sys : role.isOrgTier() && org;
                    assertThat(properties.requiresEnrollment(role))
                            .as("sys=%s org=%s role=%s", sys, org, role)
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void theOrgSwitchDoesNotReachTheSystemTier() {
        MfaProperties orgOnly = new MfaProperties(false, true);
        assertThat(orgOnly.requiresEnrollment(UserRole.SYS_ADMIN)).isFalse();
        assertThat(orgOnly.requiresEnrollment(UserRole.ORG_VIEWER)).isTrue();
        assertThat(orgOnly.requiresEnrollment(UserRole.USER)).isFalse();
    }
}
