package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.provisioning.DriftFindingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpenRouterReconcilerIsolationTest {

    @Test
    void failedActiveScopeRecordsCredentialErrorInsteadOfMarkingReconciled() {
        LlmApiKeyRepository keys = mock(LlmApiKeyRepository.class);
        OpenRouterClient client = mock(OpenRouterClient.class);
        DriftFindingRepository findings = mock(DriftFindingRepository.class);
        OpenRouterSpendRecorder spends = mock(OpenRouterSpendRecorder.class);
        OpenRouterCredentialResolver resolver = mock(OpenRouterCredentialResolver.class);
        UUID workspace = UUID.fromString("30000000-0000-4000-8000-000000000099");
        OpenRouterManagementAccess access = new OpenRouterManagementAccess(
                "failed-account", 9L, UUID.randomUUID(), workspace,
                null, "failed-management-key", 99L);
        when(resolver.reconciliationScopes()).thenReturn(
                new OpenRouterCredentialResolver.ReconciliationAccesses(
                        List.of(access), true));
        when(client.listKeys("failed-management-key", workspace))
                .thenThrow(new OpenRouterException(401, "vendor body discarded"));

        new OpenRouterReconciler(keys, client, findings, spends, resolver).reconcileAllScopes();

        verify(resolver).markVerificationFailure(
                eq(access), eq(OpenRouterCredentialError.CREDENTIAL_ERROR), any());
        verify(resolver, never()).markReconciled(any(), any());
    }

    @Test
    void oneCredentialResolutionFailureDoesNotBlockHealthyScopeOrAutoResolve() {
        LlmApiKeyRepository keys = mock(LlmApiKeyRepository.class);
        OpenRouterClient client = mock(OpenRouterClient.class);
        DriftFindingRepository findings = mock(DriftFindingRepository.class);
        OpenRouterSpendRecorder spends = mock(OpenRouterSpendRecorder.class);
        OpenRouterCredentialResolver resolver = mock(OpenRouterCredentialResolver.class);
        UUID workspace = UUID.fromString("30000000-0000-4000-8000-000000000001");
        OpenRouterManagementAccess healthy = new OpenRouterManagementAccess(
                "healthy-account", 2L, UUID.randomUUID(), workspace,
                null, "healthy-management-key", 22L);
        when(resolver.reconciliationScopes()).thenReturn(
                new OpenRouterCredentialResolver.ReconciliationAccesses(
                        List.of(healthy), false));
        when(client.listKeys("healthy-management-key", workspace)).thenReturn(List.of());
        when(keys.findByOpenrouterAccountId(2L)).thenReturn(List.of());

        new OpenRouterReconciler(keys, client, findings, spends, resolver).reconcileAllScopes();

        verify(client).listKeys("healthy-management-key", workspace);
        verify(resolver).markReconciled(any(), any());
        verify(spends).record(any(), any());
        verifyNoInteractions(findings);
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
        when(resolver.reconciliationScopes()).thenReturn(
                new OpenRouterCredentialResolver.ReconciliationAccesses(
                        List.of(first, second), true));
        when(client.listKeys("secret-a", firstWorkspace)).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("same-hash", "a", false,
                        new BigDecimal("5"), null, true, BigDecimal.ONE, firstWorkspace)));
        when(client.listKeys("secret-b", secondWorkspace)).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("same-hash", "b", false,
                        new BigDecimal("5"), null, true, new BigDecimal("2"), secondWorkspace)));
        LlmApiKey localA = local(201L);
        LlmApiKey localB = local(202L);
        when(keys.findByOpenrouterAccountId(11L)).thenReturn(List.of(localA));
        when(keys.findByOpenrouterAccountId(12L)).thenReturn(List.of(localB));

        new OpenRouterReconciler(keys, client, findings, spends, resolver).reconcileAllScopes();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenRouterSpendRecorder.Spend>> captured =
                ArgumentCaptor.forClass(List.class);
        verify(spends).record(captured.capture(), any());
        assertThat(captured.getValue().stream().map(OpenRouterSpendRecorder.Spend::keyId).toList())
                .containsExactly(201L, 202L);
        verify(findings, times(2)).autoResolveNotSeen(
                org.mockito.ArgumentMatchers.any(kr.ac.pusan.pickle.provisioning.DriftFindingKind.class),
                org.mockito.ArgumentMatchers.anyCollection(), any());
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
