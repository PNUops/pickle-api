package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.provisioning.DriftFindingKind;
import kr.ac.pusan.pickle.provisioning.DriftFindingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * One account is one reconciliation. Isolation between accounts is structural
 * now that the poll dispatcher hands each account its own job, so what is left
 * to prove here is that a single scope keeps its own namespace and does not
 * claim success it did not have.
 */
class OpenRouterReconcilerIsolationTest {

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void aFailedListingNeverMarksTheCredentialReconciled() {
        LlmApiKeyRepository keys = mock(LlmApiKeyRepository.class);
        OpenRouterClient client = mock(OpenRouterClient.class);
        DriftFindingRepository findings = mock(DriftFindingRepository.class);
        OpenRouterSpendRecorder spends = mock(OpenRouterSpendRecorder.class);
        OpenRouterCredentialResolver resolver = mock(OpenRouterCredentialResolver.class);
        UUID workspace = UUID.fromString("30000000-0000-4000-8000-000000000099");
        OpenRouterManagementAccess access = new OpenRouterManagementAccess(
                "failed-account", 9L, UUID.randomUUID(), workspace,
                null, "failed-management-key", 99L);
        when(client.listKeys("failed-management-key", workspace))
                .thenThrow(new OpenRouterException(401, "vendor body discarded"));

        // The worker above this call is what classifies and records the
        // failure; the reconciler's job is to not pretend it finished.
        assertThatThrownBy(() -> new OpenRouterReconciler(keys, client, findings, spends, resolver)
                .reconcileAccount(access, claim(9L), NOW, true, Clock.systemUTC()))
                .isInstanceOf(OpenRouterException.class);

        verify(resolver, never()).markReconciled(any(), any());
        verifyNoInteractions(findings, spends);
    }

    @Test
    void aScopeWithoutAnAccountIsRefusedRatherThanReconciledGlobally() {
        LlmApiKeyRepository keys = mock(LlmApiKeyRepository.class);
        OpenRouterClient client = mock(OpenRouterClient.class);
        DriftFindingRepository findings = mock(DriftFindingRepository.class);
        OpenRouterSpendRecorder spends = mock(OpenRouterSpendRecorder.class);
        OpenRouterCredentialResolver resolver = mock(OpenRouterCredentialResolver.class);
        OpenRouterManagementAccess accountless = new OpenRouterManagementAccess(
                "no-account", null, null, null, null, "secret", null);

        assertThatThrownBy(() -> new OpenRouterReconciler(keys, client, findings, spends, resolver)
                .reconcileAccount(accountless, claim(1L), NOW, true, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(client, findings, spends);
    }

    @Test
    void identicalRemoteHashesInTwoAccountsRemainSeparateLocalMatches() {
        LlmApiKeyRepository keys = mock(LlmApiKeyRepository.class);
        OpenRouterClient client = mock(OpenRouterClient.class);
        DriftFindingRepository findings = mock(DriftFindingRepository.class);
        OpenRouterSpendRecorder spends = mock(OpenRouterSpendRecorder.class);
        OpenRouterCredentialResolver resolver = mock(OpenRouterCredentialResolver.class);
        UUID firstWorkspace = UUID.fromString("30000000-0000-4000-8000-000000000011");
        UUID secondWorkspace = UUID.fromString("30000000-0000-4000-8000-000000000012");
        OpenRouterManagementAccess first = new OpenRouterManagementAccess(
                "account-a", 11L, UUID.randomUUID(), firstWorkspace, null, "secret-a", 101L);
        OpenRouterManagementAccess second = new OpenRouterManagementAccess(
                "account-b", 12L, UUID.randomUUID(), secondWorkspace, null, "secret-b", 102L);
        when(client.listKeys("secret-a", firstWorkspace)).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("same-hash", "a", false,
                        new BigDecimal("5"), null, true, BigDecimal.ONE, firstWorkspace)));
        when(client.listKeys("secret-b", secondWorkspace)).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("same-hash", "b", false,
                        new BigDecimal("5"), null, true, new BigDecimal("2"), secondWorkspace)));
        // Built before the stubbing below: `local` stubs a mock of its own,
        // and Mockito rejects nested stubbing inside a `when(...)` argument.
        LlmApiKey localA = local(201L);
        LlmApiKey localB = local(202L);
        when(keys.findByOpenrouterAccountId(11L)).thenReturn(List.of(localA));
        when(keys.findByOpenrouterAccountId(12L)).thenReturn(List.of(localB));
        persistedRecorder(spends);
        OpenRouterReconciler reconciler =
                new OpenRouterReconciler(keys, client, findings, spends, resolver);

        reconciler.reconcileAccount(first, claim(11L), NOW, true, Clock.systemUTC());
        reconciler.reconcileAccount(second, claim(12L), NOW, true, Clock.systemUTC());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenRouterSpendRecorder.Spend>> captured =
                ArgumentCaptor.forClass(List.class);
        verify(spends, org.mockito.Mockito.times(2))
                .recordAccount(captured.capture(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                        any(), any());
        assertThat(captured.getAllValues().stream()
                .flatMap(List::stream)
                .map(OpenRouterSpendRecorder.Spend::keyId).toList())
                .containsExactly(201L, 202L);
        // The dedup namespace carries the account, so one vendor hash seen in
        // two accounts is two findings rather than one overwriting the other.
        ArgumentCaptor<String> prefixes = ArgumentCaptor.forClass(String.class);
        verify(findings, org.mockito.Mockito.atLeastOnce()).autoResolveNotSeenInScope(
                any(DriftFindingKind.class), prefixes.capture(),
                org.mockito.ArgumentMatchers.anyCollection(), any());
        assertThat(prefixes.getAllValues()).contains(
                "account:account-a:key:", "account:account-b:key:");
    }

    /** Records everything and runs the caller's protected writes. */
    private static void persistedRecorder(OpenRouterSpendRecorder spends) {
        when(spends.recordAccount(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any())).thenAnswer(invocation -> {
                    invocation.getArgument(4, Runnable.class).run();
                    return new OpenRouterSpendRecorder.AccountRecordResult(true, false);
                });
    }

    private static OpenRouterPollRepository.Claim claim(long accountId) {
        return new OpenRouterPollRepository.Claim(accountId, UUID.randomUUID(), UUID.randomUUID(),
                accountId * 10, OpenRouterPollRepository.PollKind.PAIR, NOW, null, null);
    }

    private static LlmApiKey local(long id) {
        LlmApiKey key = mock(LlmApiKey.class);
        when(key.getId()).thenReturn(id);
        when(key.getOpenrouterKeyHash()).thenReturn("same-hash");
        when(key.getStatus()).thenReturn(LlmApiKeyStatus.ACTIVE);
        when(key.getCreditLimit()).thenReturn(new BigDecimal("5"));
        return key;
    }
}
