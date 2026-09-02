package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import org.junit.jupiter.api.Test;

class OpenRouterAccountSelectionServiceTest {

    private final OpenRouterAccountRepository accounts = mock(OpenRouterAccountRepository.class);
    private final OpenRouterAccountCredentialRepository credentials =
            mock(OpenRouterAccountCredentialRepository.class);
    private final OpenRouterManagementCredentialCipher cipher =
            mock(OpenRouterManagementCredentialCipher.class);
    private final OpenRouterAccountSelectionService service =
            new OpenRouterAccountSelectionService(accounts, credentials, cipher);

    /**
     * A grant of nothing names no account, and asking for one anyway is the
     * caller's mistake rather than a silent no-op.
     */
    @Test
    void aZeroGrantTakesNoAccountAndRejectsARequestedOne() {
        assertThat(service.select(1L, BigDecimal.ZERO, null)).isNull();
        assertThat(service.select(1L, null, null)).isNull();
        assertThatThrownBy(() -> service.select(1L, BigDecimal.ZERO, UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void zeroOneAndManyEligibleAccountsHaveDistinctOutcomes() {
        when(accounts.findByOrgIdAndStatusOrderByNameAsc(1L, OpenRouterAccountStatus.ACTIVE))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.select(1L, BigDecimal.ONE, null))
                .isInstanceOf(ApiException.class);

        OpenRouterAccount first = eligible(11L);
        when(accounts.findByOrgIdAndStatusOrderByNameAsc(1L, OpenRouterAccountStatus.ACTIVE))
                .thenReturn(List.of(first));
        assertThat(service.select(1L, BigDecimal.ONE, null)).isSameAs(first);

        OpenRouterAccount second = eligible(12L);
        when(accounts.findByOrgIdAndStatusOrderByNameAsc(1L, OpenRouterAccountStatus.ACTIVE))
                .thenReturn(List.of(first, second));
        assertThatThrownBy(() -> service.select(1L, BigDecimal.ONE, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void corruptCiphertextAndMetadataOnlyAccountAreNotEligible() {
        OpenRouterAccount corrupt = eligible(21L);
        when(corrupt.getOrgId()).thenReturn(1L);
        when(cipher.decrypt(corrupt.getPublicId(), "cipher-21"))
                .thenThrow(new IllegalStateException("missing read key"));
        when(accounts.findWithLockByPublicId(corrupt.getPublicId()))
                .thenReturn(Optional.of(corrupt));
        assertThat(service.eligible(corrupt)).isFalse();
        assertThatThrownBy(() -> service.select(
                1L, BigDecimal.ONE, corrupt.getPublicId())).isInstanceOf(ApiException.class);

        OpenRouterAccount metadataOnly = mock(OpenRouterAccount.class);
        when(metadataOnly.getId()).thenReturn(22L);
        when(metadataOnly.getStatus()).thenReturn(OpenRouterAccountStatus.ACTIVE);
        assertThat(service.eligible(metadataOnly)).isFalse();
    }

    @Test
    void invalidProofBlocksBindingWhileTransientHealthErrorsRemainEligible() {
        assertErrorAvailability(23L, OpenRouterCredentialError.CREDENTIAL_ERROR, false);
        assertErrorAvailability(24L, OpenRouterCredentialError.VENDOR_REJECTED, false);
        assertErrorAvailability(25L, OpenRouterCredentialError.THROTTLED, true);
        assertErrorAvailability(26L, OpenRouterCredentialError.VENDOR_UNAVAILABLE, true);
    }

    private void assertErrorAvailability(long id, OpenRouterCredentialError error,
            boolean expected) {
        OpenRouterAccount account = mock(OpenRouterAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getOrgId()).thenReturn(1L);
        when(account.getStatus()).thenReturn(OpenRouterAccountStatus.ACTIVE);
        when(account.getPublicId()).thenReturn(UUID.randomUUID());
        OpenRouterAccountCredential credential = mock(OpenRouterAccountCredential.class);
        when(credential.getVerifiedAt()).thenReturn(Instant.now());
        when(credential.getVerificationError()).thenReturn(error);
        when(credential.getCredentialEnc()).thenReturn("cipher-" + id);
        when(credentials.findByAccountIdAndStatus(id, OpenRouterCredentialStatus.ACTIVE))
                .thenReturn(Optional.of(credential));
        when(cipher.decrypt(account.getPublicId(), "cipher-" + id)).thenReturn("secret");
        when(accounts.findWithLockByPublicId(account.getPublicId()))
                .thenReturn(Optional.of(account));

        assertThat(service.databaseCredentialAvailable(account)).isEqualTo(expected);
        assertThat(service.eligible(account)).isEqualTo(expected);
        if (expected) {
            assertThat(service.select(1L, BigDecimal.ONE, account.getPublicId()))
                    .isSameAs(account);
        } else {
            assertThatThrownBy(() -> service.select(1L, BigDecimal.ONE, account.getPublicId()))
                    .isInstanceOf(ApiException.class);
        }
    }

    private OpenRouterAccount eligible(long id) {
        OpenRouterAccount account = mock(OpenRouterAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getStatus()).thenReturn(OpenRouterAccountStatus.ACTIVE);
        OpenRouterAccountCredential credential = mock(OpenRouterAccountCredential.class);
        when(credential.getVerifiedAt()).thenReturn(Instant.now());
        when(credentials.findByAccountIdAndStatus(id, OpenRouterCredentialStatus.ACTIVE))
                .thenReturn(Optional.of(credential));
        when(credential.getCredentialEnc()).thenReturn("cipher-" + id);
        when(account.getPublicId()).thenReturn(UUID.randomUUID());
        when(cipher.decrypt(account.getPublicId(), "cipher-" + id)).thenReturn("secret");
        return account;
    }
}
