package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.config.OpenRouterDirectPollingProperties;
import org.jobrunr.server.BackgroundJobServer;
import org.junit.jupiter.api.Test;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;

class OpenRouterDirectPollingTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final UUID ACCOUNT =
            UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ACCOUNT =
            UUID.fromString("30000000-0000-4000-8000-000000000002");

    @Test
    void defaultsOffAndRequiresAnExplicitAccountAllowlistWhenEnabled() {
        OpenRouterDirectPollingProperties defaults =
                new OpenRouterDirectPollingProperties(false, Set.of(), null);

        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.accountPublicIds()).isEmpty();
        assertThat(defaults.fixedDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(new OpenRouterDirectPollingProperties(false, Set.of(),
                Duration.ofMinutes(1)).fixedDelay()).isEqualTo(Duration.ofMinutes(1));
        for (Duration tooShort : List.of(Duration.ofSeconds(30), Duration.ofMillis(1))) {
            assertThatThrownBy(() -> new OpenRouterDirectPollingProperties(
                    false, Set.of(), tooShort))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least PT1M");
        }
        assertThatThrownBy(() -> new OpenRouterDirectPollingProperties(
                true, Set.of(), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account-public-id allowlist");
    }

    @Test
    void directModeRejectsTheGenericJobRunrWorker() {
        assertThatThrownBy(() -> new OpenRouterDirectPollingConfiguration(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generic JobRunr worker to be disabled and absent");
        assertThatThrownBy(() -> OpenRouterDirectPollingWorkerGuard
                .requireWorkerDisabled(false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worker to be disabled and absent");
    }

    @Test
    void schedulerRejectsAnActualBackgroundJobServerBeanEvenWhenPropertySaysOff() {
        OpenRouterPollRepository polls = mock(OpenRouterPollRepository.class);
        OpenRouterDirectAccountPoller poller = mock(OpenRouterDirectAccountPoller.class);
        OpenRouterCredentialResolver credentials = mock(OpenRouterCredentialResolver.class);
        OpenRouterDirectPollingProperties properties = new OpenRouterDirectPollingProperties(
                true, Set.of(ACCOUNT), Duration.ofMinutes(1));
        Environment environment = mock(Environment.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BackgroundJobServer> backgroundServer = mock(ObjectProvider.class);
        when(environment.getProperty("jobrunr.background-job-server.enabled",
                Boolean.class, true)).thenReturn(false);
        when(backgroundServer.getIfAvailable()).thenReturn(mock(BackgroundJobServer.class));

        OpenRouterDirectPollScheduler scheduler = new OpenRouterDirectPollScheduler(
                polls, poller, credentials, properties, Clock.fixed(NOW, ZoneOffset.UTC),
                environment, backgroundServer);

        assertThatThrownBy(() -> scheduler.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worker to be disabled and absent");
        verifyNoInteractions(polls, poller);
    }

    @Test
    void schedulerFailsClosedWhenAnAllowlistedAccountIsNotReady() {
        OpenRouterPollRepository polls = mock(OpenRouterPollRepository.class);
        OpenRouterDirectAccountPoller poller = mock(OpenRouterDirectAccountPoller.class);
        OpenRouterCredentialResolver credentials = mock(OpenRouterCredentialResolver.class);
        OpenRouterDirectPollingProperties properties = new OpenRouterDirectPollingProperties(
                true, Set.of(ACCOUNT), Duration.ofMinutes(1));
        Environment environment = mock(Environment.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BackgroundJobServer> backgroundServer = mock(ObjectProvider.class);
        when(environment.getProperty("jobrunr.background-job-server.enabled",
                Boolean.class, true)).thenReturn(false);
        when(polls.directPollAccountReady(ACCOUNT)).thenReturn(false);

        OpenRouterDirectPollScheduler scheduler = new OpenRouterDirectPollScheduler(
                polls, poller, credentials, properties, Clock.fixed(NOW, ZoneOffset.UTC),
                environment, backgroundServer);

        assertThatThrownBy(() -> scheduler.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verified ACTIVE credential");
        verifyNoInteractions(poller);
    }

    @Test
    void schedulerRefusesAnAllowlistedCredentialThatCannotBeDecrypted() {
        OpenRouterPollRepository polls = mock(OpenRouterPollRepository.class);
        OpenRouterDirectAccountPoller poller = mock(OpenRouterDirectAccountPoller.class);
        OpenRouterCredentialResolver credentials = mock(OpenRouterCredentialResolver.class);
        OpenRouterDirectPollingProperties properties = new OpenRouterDirectPollingProperties(
                true, Set.of(ACCOUNT), Duration.ofMinutes(1));
        Environment environment = mock(Environment.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BackgroundJobServer> backgroundServer = mock(ObjectProvider.class);
        when(environment.getProperty("jobrunr.background-job-server.enabled",
                Boolean.class, true)).thenReturn(false);
        when(polls.directPollAccountReady(ACCOUNT)).thenReturn(true);
        when(credentials.forAccount(ACCOUNT)).thenThrow(
                new OpenRouterException(0, "credential decrypt failed"));
        OpenRouterDirectPollScheduler scheduler = new OpenRouterDirectPollScheduler(
                polls, poller, credentials, properties, Clock.fixed(NOW, ZoneOffset.UTC),
                environment, backgroundServer);

        assertThatThrownBy(() -> scheduler.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryptable verified ACTIVE credentials")
                .hasNoCause();
        verifyNoInteractions(poller);
    }

    @Test
    void directModePersistsRefreshTriggersWithoutEnqueuingTheGenericDispatcher() {
        OpenRouterPollRepository polls = mock(OpenRouterPollRepository.class);
        OpenRouterPollDispatcher dispatcher = mock(OpenRouterPollDispatcher.class);
        JobScheduler jobs = mock(JobScheduler.class);
        OpenRouterDirectPollingProperties properties = new OpenRouterDirectPollingProperties(
                true, Set.of(ACCOUNT), Duration.ofMinutes(1));
        when(polls.requestRefresh(ACCOUNT, false, NOW)).thenReturn(true);

        new OpenRouterCreditRefreshScheduler(polls, dispatcher, jobs,
                Clock.fixed(NOW, ZoneOffset.UTC), properties).requestCredits(ACCOUNT);

        verify(polls).requestRefresh(ACCOUNT, false, NOW);
        verifyNoInteractions(dispatcher, jobs);
    }

    @Test
    void schedulerQueriesAndRunsOnlyAllowlistedAccountsInline() {
        OpenRouterPollRepository polls = mock(OpenRouterPollRepository.class);
        OpenRouterDirectAccountPoller poller = mock(OpenRouterDirectAccountPoller.class);
        OpenRouterCredentialResolver credentials = mock(OpenRouterCredentialResolver.class);
        OpenRouterDirectPollingProperties properties = new OpenRouterDirectPollingProperties(
                true, Set.of(ACCOUNT, OTHER_ACCOUNT), Duration.ofMinutes(1));
        Environment environment = mock(Environment.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BackgroundJobServer> backgroundServer = mock(ObjectProvider.class);
        OpenRouterPollRepository.Claim claim = claim(41L, ACCOUNT,
                OpenRouterPollRepository.PollKind.CREDITS);
        when(polls.dueAccountIds(NOW, ACCOUNT)).thenReturn(List.of(41L));
        when(polls.claim(41L, NOW)).thenReturn(claim);

        new OpenRouterDirectPollScheduler(polls, poller, credentials, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), environment, backgroundServer).pollDueAccounts();

        verify(polls).dueAccountIds(NOW, ACCOUNT);
        verify(polls).dueAccountIds(NOW, OTHER_ACCOUNT);
        verify(poller).poll(ACCOUNT, claim.token());
        verify(poller, never()).poll(OTHER_ACCOUNT, claim.token());
    }

    @Test
    void accountFailureDoesNotSkipOtherAllowlistedAccounts() {
        OpenRouterPollRepository polls = mock(OpenRouterPollRepository.class);
        OpenRouterDirectAccountPoller poller = mock(OpenRouterDirectAccountPoller.class);
        OpenRouterCredentialResolver credentials = mock(OpenRouterCredentialResolver.class);
        OpenRouterDirectPollingProperties properties = new OpenRouterDirectPollingProperties(
                true, Set.of(ACCOUNT, OTHER_ACCOUNT), Duration.ofMinutes(1));
        Environment environment = mock(Environment.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BackgroundJobServer> backgroundServer = mock(ObjectProvider.class);
        OpenRouterPollRepository.Claim failed = claim(41L, ACCOUNT,
                OpenRouterPollRepository.PollKind.CREDITS);
        OpenRouterPollRepository.Claim succeeding = claim(42L, OTHER_ACCOUNT,
                OpenRouterPollRepository.PollKind.PAIR);
        OpenRouterPollRepository.DirectPollHealth healthy =
                new OpenRouterPollRepository.DirectPollHealth(NOW, NOW, null, null);
        when(polls.dueAccountIds(NOW, ACCOUNT)).thenReturn(List.of(41L));
        when(polls.dueAccountIds(NOW, OTHER_ACCOUNT)).thenReturn(List.of(42L));
        when(polls.claim(41L, NOW)).thenReturn(failed);
        when(polls.claim(42L, NOW)).thenReturn(succeeding);
        when(polls.directPollHealth(ACCOUNT)).thenReturn(healthy);
        when(polls.directPollHealth(OTHER_ACCOUNT)).thenReturn(healthy);
        doThrow(new IllegalStateException("sensitive detail"))
                .when(poller).poll(ACCOUNT, failed.token());

        new OpenRouterDirectPollScheduler(polls, poller, credentials,
                new OpenRouterDirectPollingProperties(true,
                        Set.of(ACCOUNT, OTHER_ACCOUNT), Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC), environment, backgroundServer)
                .pollDueAccounts();

        verify(polls, never()).abandon(failed);
        verify(poller).poll(OTHER_ACCOUNT, succeeding.token());
    }

    @Test
    void directHealthUsesTheSameThirtyMinuteBoundaryAsTheCachedReadModel() {
        assertThat(OpenRouterDirectPollScheduler.isStale(null, NOW)).isTrue();
        assertThat(OpenRouterDirectPollScheduler.isStale(NOW,
                NOW.plus(Duration.ofMinutes(30)).minusMillis(1))).isFalse();
        assertThat(OpenRouterDirectPollScheduler.isStale(NOW,
                NOW.plus(Duration.ofMinutes(30)))).isTrue();
    }

    @Test
    void creditClaimUsesOnlyTheReadClientAndPersistsThroughTheExistingClaim() {
        OpenRouterPollRepository polls = mock(OpenRouterPollRepository.class);
        OpenRouterCredentialResolver credentials = mock(OpenRouterCredentialResolver.class);
        OpenRouterReconciler reconciler = mock(OpenRouterReconciler.class);
        OpenRouterReadClient reader = mock(OpenRouterReadClient.class);
        OpenRouterManagementAccess access = new OpenRouterManagementAccess(
                "account-scope", 41L, ACCOUNT, null, null, "secret", 410L);
        OpenRouterPollRepository.Claim claim = claim(41L, ACCOUNT,
                OpenRouterPollRepository.PollKind.CREDITS);
        OpenRouterClient.Credits credits = new OpenRouterClient.Credits(
                new java.math.BigDecimal("20"), new java.math.BigDecimal("3"));
        when(polls.activeClaim(ACCOUNT, claim.token(), NOW)).thenReturn(claim);
        when(credentials.forAccount(ACCOUNT)).thenReturn(Optional.of(access));
        when(reader.credits("secret")).thenReturn(credits);
        when(polls.recordCreditsSuccess(claim, credits, NOW, NOW)).thenReturn(true);

        new OpenRouterDirectAccountPoller(polls, credentials, reconciler, reader,
                Clock.fixed(NOW, ZoneOffset.UTC)).poll(ACCOUNT, claim.token());

        verify(reader).credits("secret");
        verify(reader, never()).listKeys("secret", null);
        verify(polls).recordCreditsSuccess(claim, credits, NOW, NOW);
        verify(credentials).markUsed(access, NOW);
        verifyNoInteractions(reconciler);
        assertThat(OpenRouterReadClient.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("credits", "listKeys");
    }

    @Test
    void pairClaimUsesReadOnlyReconciliationThenRecordsTheExistingPairState() {
        OpenRouterPollRepository polls = mock(OpenRouterPollRepository.class);
        OpenRouterCredentialResolver credentials = mock(OpenRouterCredentialResolver.class);
        OpenRouterReconciler reconciler = mock(OpenRouterReconciler.class);
        OpenRouterReadClient reader = mock(OpenRouterReadClient.class);
        OpenRouterManagementAccess access = new OpenRouterManagementAccess(
                "account-scope", 41L, ACCOUNT, null, null, "secret", 410L);
        OpenRouterPollRepository.Claim claim = claim(41L, ACCOUNT,
                OpenRouterPollRepository.PollKind.PAIR);
        OpenRouterReconciler.ScopeObservation keys =
                new OpenRouterReconciler.ScopeObservation(true, true, false, NOW);
        OpenRouterClient.Credits credits = new OpenRouterClient.Credits(
                new BigDecimal("20"), new BigDecimal("3"));
        when(polls.activeClaim(ACCOUNT, claim.token(), NOW)).thenReturn(claim);
        when(credentials.forAccount(ACCOUNT)).thenReturn(Optional.of(access));
        when(polls.baselineExists(claim)).thenReturn(true);
        when(reconciler.reconcileAccountReadOnly(access, claim, NOW, true,
                Clock.fixed(NOW, ZoneOffset.UTC), reader)).thenReturn(keys);
        when(polls.managedUsageSinceBaseline(claim)).thenReturn(BigDecimal.ONE);
        when(reader.credits("secret")).thenReturn(credits);
        when(polls.recordPairSuccess(claim, credits, BigDecimal.ONE,
                false, NOW, NOW, NOW)).thenReturn(true);

        new OpenRouterDirectAccountPoller(polls, credentials, reconciler, reader,
                Clock.fixed(NOW, ZoneOffset.UTC)).poll(ACCOUNT, claim.token());

        verify(reconciler).reconcileAccountReadOnly(access, claim, NOW, true,
                Clock.fixed(NOW, ZoneOffset.UTC), reader);
        verify(reader).credits("secret");
        verify(polls).recordPairSuccess(claim, credits, BigDecimal.ONE,
                false, NOW, NOW, NOW);
    }

    @Test
    void throttledDirectPollRecordsBackoffAndReleasesItsClaim() {
        OpenRouterPollRepository polls = mock(OpenRouterPollRepository.class);
        OpenRouterCredentialResolver credentials = mock(OpenRouterCredentialResolver.class);
        OpenRouterReconciler reconciler = mock(OpenRouterReconciler.class);
        OpenRouterReadClient reader = mock(OpenRouterReadClient.class);
        OpenRouterManagementAccess access = new OpenRouterManagementAccess(
                "account-scope", 41L, ACCOUNT, null, null, "secret", 410L);
        OpenRouterPollRepository.Claim claim = claim(41L, ACCOUNT,
                OpenRouterPollRepository.PollKind.CREDITS);
        OpenRouterException throttled = new OpenRouterException(429, "rate limited");
        when(polls.activeClaim(ACCOUNT, claim.token(), NOW)).thenReturn(claim);
        when(credentials.forAccount(ACCOUNT)).thenReturn(Optional.of(access));
        when(reader.credits("secret")).thenThrow(throttled);

        new OpenRouterDirectAccountPoller(polls, credentials, reconciler, reader,
                Clock.fixed(NOW, ZoneOffset.UTC)).poll(ACCOUNT, claim.token());

        verify(credentials).markVerificationFailure(access,
                OpenRouterCredentialError.THROTTLED, NOW);
        verify(polls).recordFailure(claim, OpenRouterPollRepository.FailureAxis.CREDITS,
                OpenRouterCredentialError.THROTTLED, NOW);
        verify(polls).abandon(claim);
        verifyNoInteractions(reconciler);
    }

    private static OpenRouterPollRepository.Claim claim(long internalId, UUID publicId,
            OpenRouterPollRepository.PollKind kind) {
        return new OpenRouterPollRepository.Claim(internalId, publicId, UUID.randomUUID(),
                internalId * 10, kind, NOW, null, null);
    }
}
