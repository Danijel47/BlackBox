package com.blackbox.wow.service;

import com.blackbox.wow.client.MPlusObservation;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.client.RaiderIoCollectionException;
import com.blackbox.wow.client.RaiderIoCollectionException.Category;
import com.blackbox.wow.properties.MPlusCollectionProperties;
import com.blackbox.wow.properties.MPlusDungeonProperties;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository;
import com.blackbox.wow.service.MPlusCollectionPersistenceService.CollectionStatus;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusDataCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Mock private RaiderIoClient client;
    @Mock private TrackedPlayerService trackedPlayerService;
    @Mock private MPlusCollectionPersistenceService persistence;
    @Mock private MPlusDungeonVaultRepository dungeonVaultRepository;

    @Test
    void continuesWithOtherProfilesAfterOneCollectionFails() {
        TrackedPlayer failed = new TrackedPlayer(1, "Buco", "eu", "Stormscale", "Bucothered");
        TrackedPlayer successful = new TrackedPlayer(2, "Linq", "eu", "Stormscale", "Thelinq");
        MPlusObservation observation = observation("Thelinq");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(failed, successful));
        when(client.getMPlusObservation("eu", "Stormscale", "Bucothered"))
                .thenThrow(new RaiderIoCollectionException(Category.TIMEOUT, "timeout"));
        when(client.getMPlusObservation("eu", "Stormscale", "Thelinq")).thenReturn(observation);
        when(persistence.saveObservation(successful, observation, NOW)).thenReturn(List.of());

        service().collectScheduledData();

        verify(persistence).recordFailure(failed, NOW, Category.TIMEOUT);
        verify(persistence).saveObservation(successful, observation, NOW);
    }

    @Test
    void formatsCurrentAndFailedProfileStatuses() {
        TrackedPlayer buco = new TrackedPlayer(1, "Buco", "eu", "Stormscale", "Bucothered");
        TrackedPlayer yoda = new TrackedPlayer(2, "Yoda", "eu", "Darksorrow", "Felmm");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, yoda));
        when(persistence.statuses()).thenReturn(List.of(
                new CollectionStatus(1, "season-mn-2", "eu", "Stormscale", "Bucothered",
                        NOW.minusSeconds(600), NOW.minusSeconds(600), NOW.minusSeconds(900), 8, null),
                new CollectionStatus(2, "season-mn-2", "eu", "Darksorrow", "Felmm",
                        NOW.minusSeconds(120), NOW.minusSeconds(3600), NOW.minusSeconds(3900), 4, "TIMEOUT")
        ));

        String message = service().statusMessage();

        assertThat(message)
                .contains("Buco — OK — 10m ago, 8 visible runs (Bucothered-Stormscale)")
                .contains("Yoda — failed: timeout; last success 1h ago, 4 visible runs (Felmm-Darksorrow)")
                .contains("not a complete historical import");
    }

    private MPlusDataCollectionService service() {
        return new MPlusDataCollectionService(
                client,
                trackedPlayerService,
                persistence,
                new MPlusCollectionProperties(true, 10, 0),
                new MPlusDungeonProperties(11, 10, 8),
                dungeonVaultRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static MPlusObservation observation(String name) {
        return new MPlusObservation(
                name,
                "Stormscale",
                "eu",
                "season-mn-2",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                NOW,
                List.of(),
                List.of(),
                List.of(),
                true
        );
    }
}
