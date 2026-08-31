package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.config.OpenRouterProperties;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import org.junit.jupiter.api.Test;

class OpenRouterCredentialResolverTest {

    @Test
    void accountBoundMissingOrBadCredentialNeverFallsBackToEnv() {
        OpenRouterAccountRepository accounts = mock(OpenRouterAccountRepository.class);
        OpenRouterAccountCredentialRepository credentials =
                mock(OpenRouterAccountCredentialRepository.class);
        OpenRouterManagementCredentialCipher cipher = mock(OpenRouterManagementCredentialCipher.class);
        OpenRouterProperties legacy = new OpenRouterProperties(
                null, "legacy-env-secret", null, null, false);
        OpenRouterCredentialResolver resolver =
                new OpenRouterCredentialResolver(accounts, credentials, cipher, legacy);
        LlmApiKey key = mock(LlmApiKey.class);
        when(key.getOpenrouterAccountId()).thenReturn(7L);
        OpenRouterAccount account = mock(OpenRouterAccount.class);
        when(account.getId()).thenReturn(7L);
        when(account.getPublicId()).thenReturn(UUID.randomUUID());
        when(accounts.findById(7L)).thenReturn(Optional.of(account));

        assertThat(resolver.forKey(key)).isEmpty();

        OpenRouterAccountCredential active = mock(OpenRouterAccountCredential.class);
        when(active.getVerifiedAt()).thenReturn(Instant.now());
        when(active.getCredentialEnc()).thenReturn("corrupt");
        when(credentials.findByAccountIdAndStatus(7L, OpenRouterCredentialStatus.ACTIVE))
                .thenReturn(Optional.of(active));
        when(cipher.decrypt(account.getPublicId(), "corrupt"))
                .thenThrow(new IllegalStateException("bad ciphertext"));
        assertThatThrownBy(() -> resolver.forKey(key)).isInstanceOf(OpenRouterException.class);

        when(active.getVerificationError()).thenReturn(OpenRouterCredentialError.CREDENTIAL_ERROR);
        when(active.getCredentialEnc()).thenReturn("valid-db-ciphertext");
        when(cipher.decrypt(account.getPublicId(), "valid-db-ciphertext"))
                .thenReturn("db-management-secret");
        OpenRouterManagementAccess access = resolver.forKey(key).orElseThrow();
        assertThat(access.accountId()).isEqualTo(7L);
        assertThat(access.secret()).isEqualTo("db-management-secret");
        assertThat(access.includesLegacyKeys()).isFalse();
    }

    @Test
    void explicitLegacyKeyUsesTheSoleLegacyEnvSource() {
        OpenRouterAccountRepository accounts = mock(OpenRouterAccountRepository.class);
        OpenRouterAccountCredentialRepository credentials =
                mock(OpenRouterAccountCredentialRepository.class);
        OpenRouterManagementCredentialCipher cipher = mock(OpenRouterManagementCredentialCipher.class);
        OpenRouterProperties legacy = new OpenRouterProperties(
                null, "legacy-env-secret", null, null, false);
        OpenRouterCredentialResolver resolver =
                new OpenRouterCredentialResolver(accounts, credentials, cipher, legacy);
        LlmApiKey key = mock(LlmApiKey.class);
        when(key.getOpenrouterAccountId()).thenReturn(null);
        when(key.isOpenrouterLegacy()).thenReturn(true);
        OpenRouterManagementAccess access = resolver.forKey(key).orElseThrow();
        assertThat(access.scopeKey()).isEqualTo("legacy-env");
        assertThat(access.secret()).isEqualTo("legacy-env-secret");
        assertThat(access.includesLegacyKeys()).isTrue();
    }
}
