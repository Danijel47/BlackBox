package com.blackbox.wow.service;

import com.blackbox.wow.client.MPlusObservation;
import com.blackbox.wow.client.MPlusObservation.RunDetails;
import com.blackbox.wow.client.MPlusObservation.RunSummary;
import com.blackbox.wow.client.RaiderIoCollectionException.Category;
import com.blackbox.wow.repository.MPlusCollectionRepository;
import com.blackbox.wow.repository.MPlusCollectionRepository.CollectionStatusRow;
import com.blackbox.wow.repository.MPlusCollectionRepository.StoredRun;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.service.MPlusCollectionPersistenceService.CollectionStatus;
import com.blackbox.wow.service.MPlusCollectionPersistenceService.PendingRun;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusCollectionPersistenceServiceTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-20T10:00:00Z");
    private static final String SEASON = "season-mn-2";

    @Mock private MPlusCollectionRepository collectionRepository;
    @Mock private MPlusProgressRepository progressRepository;
    @Mock private MPlusDungeonVaultRepository dungeonVaultRepository;

    @Test
    void savesObservationInTransactionOrderAndReturnsOnlyPendingRuns() {
        TrackedPlayer player = player();
        RunSummary pendingRun = run(101L, 10);
        RunSummary loadedRun = run(102L, 11);
        MPlusObservation observation = observation(List.of(pendingRun, loadedRun));
        when(collectionRepository.saveRunSummary(SEASON, pendingRun, OBSERVED_AT))
                .thenReturn(new StoredRun(1L, "PENDING"));
        when(collectionRepository.saveRunSummary(SEASON, loadedRun, OBSERVED_AT))
                .thenReturn(new StoredRun(2L, "LOADED"));

        List<PendingRun> result = service().saveObservation(player, observation, OBSERVED_AT);

        assertThat(result).containsExactly(new PendingRun(1L, SEASON, 101L));
        InOrder order = inOrder(collectionRepository, progressRepository, dungeonVaultRepository);
        order.verify(collectionRepository).saveScoreSnapshot(player, observation, OBSERVED_AT);
        order.verify(progressRepository).recordMilestones(player, observation, OBSERVED_AT);
        order.verify(collectionRepository).saveRunSummary(SEASON, pendingRun, OBSERVED_AT);
        order.verify(collectionRepository).linkRunToProfile(1L, player, OBSERVED_AT);
        order.verify(collectionRepository).saveRunSummary(SEASON, loadedRun, OBSERVED_AT);
        order.verify(collectionRepository).linkRunToProfile(2L, player, OBSERVED_AT);
        order.verify(dungeonVaultRepository).saveCurrentVaultSnapshot(player, observation, OBSERVED_AT);
        order.verify(collectionRepository).recordSuccess(player, observation, OBSERVED_AT);
    }

    @Test
    void delegatesRunDetailsAndFailurePersistence() {
        RunDetails details = new RunDetails(List.of(), List.of());
        TrackedPlayer player = player();

        service().saveRunDetails(5L, details);
        service().markRunDetailsUnavailable(6L);
        service().recordFailure(player, OBSERVED_AT, Category.TIMEOUT);

        verify(collectionRepository).replaceRunDetails(5L, details);
        verify(collectionRepository).markRunDetailsUnavailable(6L);
        verify(collectionRepository).recordFailure(player, OBSERVED_AT, Category.TIMEOUT);
    }

    @Test
    void mapsRepositoryRowsToStableServiceStatusType() {
        Instant lastSuccess = OBSERVED_AT.minusSeconds(60);
        when(collectionRepository.statuses()).thenReturn(List.of(new CollectionStatusRow(
                7L,
                SEASON,
                "eu",
                "Stormscale",
                "Thelinq",
                OBSERVED_AT,
                lastSuccess,
                lastSuccess,
                4,
                null
        )));

        List<CollectionStatus> result = service().statuses();

        assertThat(result).containsExactly(new CollectionStatus(
                7L,
                SEASON,
                "eu",
                "Stormscale",
                "Thelinq",
                OBSERVED_AT,
                lastSuccess,
                lastSuccess,
                4,
                null
        ));
    }

    private MPlusCollectionPersistenceService service() {
        return new MPlusCollectionPersistenceService(
                collectionRepository,
                progressRepository,
                dungeonVaultRepository
        );
    }

    private static TrackedPlayer player() {
        return new TrackedPlayer(7L, "Linq", "eu", "Stormscale", "Thelinq");
    }

    private static MPlusObservation observation(List<RunSummary> runs) {
        return new MPlusObservation(
                "Thelinq",
                "Stormscale",
                "eu",
                SEASON,
                BigDecimal.valueOf(2_000),
                BigDecimal.valueOf(2_000),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                OBSERVED_AT,
                runs,
                runs,
                List.of(),
                true
        );
    }

    private static RunSummary run(long id, int level) {
        return new RunSummary(
                id,
                "Kings' Rest",
                "KR",
                244,
                level,
                OBSERVED_AT.minusSeconds(3_600),
                1_800_000,
                2_000_000,
                1,
                BigDecimal.valueOf(200),
                true,
                true,
                true
        );
    }
}
