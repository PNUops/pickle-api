package kr.ac.pusan.pickle.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Fail-closed guard for signup consent (sec m6): with no configured terms/privacy
 * document the per-document requirement loop finds nothing to enforce, so signup
 * must refuse rather than record a consent-free account. Pure unit test — the
 * empty-document state is awkward to reach against the seeded test DB.
 */
@ExtendWith(MockitoExtension.class)
class TermsServiceSignupGuardTest {

    @Mock
    private TermsVersionRepository termsVersionRepository;
    @Mock
    private UserConsentRepository userConsentRepository;

    @Test
    void signupFailsClosedWhenNoCurrentDocuments() {
        when(termsVersionRepository
                .findFirstByDocTypeAndEffectiveAtLessThanEqualOrderByVersionDesc(any(), any(Instant.class)))
                .thenReturn(Optional.empty());
        TermsService service = new TermsService(termsVersionRepository, userConsentRepository);

        assertThatThrownBy(() -> service.recordSignupConsents(1L, List.of()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
        verify(userConsentRepository, never()).save(any());
    }
}
