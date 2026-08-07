package kr.ac.pusan.pickle.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.consent.dto.ConsentInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Fail-closed guard for signup consent: the per-document requirement loop only
 * enforces the documents it finds, so a document that is not published is
 * silently not required. Signup must refuse until the full set is there rather
 * than record an account that consented to none of them, or to half. Pure unit
 * test — those states are awkward to reach against the seeded test DB.
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

        assertThatThrownBy(() -> service().recordSignupConsents(1L, List.of()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(userConsentRepository, never()).save(any());
    }

    @Test
    void signupFailsClosedWhenOnlyOneDocumentIsPublished() {
        TermsVersion published = mock(TermsVersion.class);
        when(termsVersionRepository.findFirstByDocTypeAndEffectiveAtLessThanEqualOrderByVersionDesc(
                eq(TermsDocType.TERMS_OF_SERVICE), any(Instant.class))).thenReturn(Optional.of(published));
        when(termsVersionRepository.findFirstByDocTypeAndEffectiveAtLessThanEqualOrderByVersionDesc(
                eq(TermsDocType.PRIVACY_POLICY), any(Instant.class))).thenReturn(Optional.empty());

        // The submitted consent covers every document the requirement loop can
        // see, so nothing below the guard would reject this signup.
        assertThatThrownBy(() -> service().recordSignupConsents(
                1L, List.of(new ConsentInput(TermsDocType.TERMS_OF_SERVICE, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(userConsentRepository, never()).save(any());
    }

    private TermsService service() {
        return new TermsService(termsVersionRepository, userConsentRepository);
    }
}
