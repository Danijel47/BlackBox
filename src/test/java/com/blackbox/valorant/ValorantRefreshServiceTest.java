package com.blackbox.valorant;

import com.blackbox.valorant.client.HenrikDevClient;
import com.blackbox.valorant.entity.ValorantProfileEntity;
import com.blackbox.valorant.properties.ValorantProperties;
import com.blackbox.valorant.repository.ValorantProfileRepository;
import com.blackbox.valorant.service.ValorantRefreshService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ValorantRefreshServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private final ValorantProfileRepository repository = mock(ValorantProfileRepository.class);
    private final HenrikDevClient client = mock(HenrikDevClient.class);

    @Test
    void refreshesOnlyOneDueProfileAndSavesTheResult() {
        var first = profile("Alex", "one");
        var second = profile("Sam", "two");
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(first, second));
        when(client.headshots("eu", "pc", "one")).thenReturn(new HenrikDevClient.Headshots(20, 250, 750, 0));

        service("key").refreshNextProfile();

        assertThat(first.savedHeadshots()).hasValue(new HenrikDevClient.Headshots(20, 250, 750, 0));
        assertThat(first.getHeadshotUpdatedAt()).isEqualTo(NOW);
        assertThat(second.getHeadshotAttemptedAt()).isNull();
        verify(repository).saveHeadshotResult(first.getId(), 20, 250, 750, 0, NOW);
    }

    @Test
    void aNewCollectorAfterRestartDoesNotRefetchAFreshDatabaseResult() {
        var profile = profile("Alex", "one");
        profile.recordHeadshots(new HenrikDevClient.Headshots(20, 250, 750, 0), NOW.minusSeconds(3599));
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(profile));

        service("key").refreshNextProfile();

        verifyNoInteractions(client);
    }

    @Test
    void refreshesAtTheOneHourBoundary() {
        var profile = profile("Alex", "one");
        profile.recordHeadshots(new HenrikDevClient.Headshots(20, 100, 900, 0), NOW.minusSeconds(3600));
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(profile));
        when(client.headshots("eu", "pc", "one")).thenReturn(new HenrikDevClient.Headshots(20, 250, 750, 0));

        service("key").refreshNextProfile();

        assertThat(profile.savedHeadshots().orElseThrow().percentage().orElseThrow()).isEqualByComparingTo("25.0");
        assertThat(profile.getHeadshotUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void failedRefreshPreservesTheLastGoodResultAndIsNotRetriedEveryMinute() {
        var profile = profile("Alex", "one");
        profile.recordHeadshots(new HenrikDevClient.Headshots(20, 100, 900, 0), NOW.minusSeconds(3600));
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(profile));
        when(client.headshots("eu", "pc", "one")).thenThrow(new IllegalStateException("private diagnostics"));

        service("key").refreshNextProfile();
        service("key").refreshNextProfile();

        assertThat(profile.savedHeadshots().orElseThrow().percentage().orElseThrow()).isEqualByComparingTo("10.0");
        assertThat(profile.getHeadshotUpdatedAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(profile.isHeadshotRefreshFailed()).isTrue();
        verify(client).headshots("eu", "pc", "one");
        assertThat(profile.getHeadshotRefreshWarning()).doesNotContain("private diagnostics");
        verify(repository).saveHeadshotFailure(profile.getId(), NOW, profile.getHeadshotRefreshWarning());
    }

    @Test
    void accessDeniedWarningIsPersistedAndClearedAfterARecovery() {
        var profile = profile("Alex", "one");
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(profile));
        String warning = "HenrikDev denied access (HTTP 403). Check your API key's permissions.";
        when(client.headshots("eu", "pc", "one")).thenThrow(new HenrikDevClient.UnavailableException(warning));

        service("key").refreshNextProfile();

        assertThat(profile.getHeadshotRefreshWarning()).isEqualTo(warning);
        verify(repository).saveHeadshotFailure(profile.getId(), NOW, warning);

        var later = new ValorantRefreshService(repository, client,
                new ValorantProperties("key", Duration.ofHours(1)), Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
        doReturn(new HenrikDevClient.Headshots(20, 250, 750, 0)).when(client).headshots("eu", "pc", "one");
        later.refreshNextProfile();

        assertThat(profile.getHeadshotRefreshWarning()).isNull();
        assertThat(profile.isHeadshotRefreshFailed()).isFalse();
        assertThat(profile.savedHeadshots()).hasValue(new HenrikDevClient.Headshots(20, 250, 750, 0));
    }

    @Test
    void rotatesToTheNextPlayerInsteadOfRefreshingTheSamePlayerAgain() {
        var first = profile("Alex", "one");
        first.recordHeadshots(new HenrikDevClient.Headshots(20, 250, 750, 0), NOW.minusSeconds(30));
        var second = profile("Sam", "two");
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(first, second));
        when(client.headshots("eu", "pc", "two")).thenReturn(new HenrikDevClient.Headshots(5, 10, 40, 0));

        service("key").refreshNextProfile();

        assertThat(second.savedHeadshots()).hasValue(new HenrikDevClient.Headshots(5, 10, 40, 0));
        assertThat(first.getHeadshotUpdatedAt()).isEqualTo(NOW.minusSeconds(30));
    }

    @Test
    void missingApiKeySkipsCollection() {
        service("").refreshNextProfile();

        verifyNoInteractions(repository, client);
    }

    private ValorantRefreshService service(String key) {
        return new ValorantRefreshService(repository, client, new ValorantProperties(key, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ValorantProfileEntity profile(String name, String id) {
        return new ValorantProfileEntity(name, id, name + "#EU1", "eu", "pc");
    }
}
