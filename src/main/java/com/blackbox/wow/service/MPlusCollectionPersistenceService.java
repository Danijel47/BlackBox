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
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class MPlusCollectionPersistenceService {

    private static final String DETAILS_PENDING = "PENDING";

    private final MPlusCollectionRepository collectionRepository;
    private final MPlusProgressRepository progressRepository;
    private final MPlusDungeonVaultRepository dungeonVaultRepository;

    public MPlusCollectionPersistenceService(
            MPlusCollectionRepository collectionRepository,
            MPlusProgressRepository progressRepository,
            MPlusDungeonVaultRepository dungeonVaultRepository
    ) {
        this.collectionRepository = collectionRepository;
        this.progressRepository = progressRepository;
        this.dungeonVaultRepository = dungeonVaultRepository;
    }

    @Transactional
    public List<PendingRun> saveObservation(
            TrackedPlayer player,
            MPlusObservation observation,
            Instant observedAt
    ) {
        collectionRepository.saveScoreSnapshot(player, observation, observedAt);
        progressRepository.recordMilestones(player, observation, observedAt);
        List<PendingRun> pendingRuns = saveRuns(player, observation, observedAt);
        dungeonVaultRepository.saveCurrentVaultSnapshot(player, observation, observedAt);
        collectionRepository.recordSuccess(player, observation, observedAt);
        return List.copyOf(pendingRuns);
    }

    @Transactional
    public void saveRunDetails(long storedRunId, RunDetails details) {
        collectionRepository.replaceRunDetails(storedRunId, details);
    }

    @Transactional
    public void markRunDetailsUnavailable(long storedRunId) {
        collectionRepository.markRunDetailsUnavailable(storedRunId);
    }

    @Transactional
    public void recordFailure(TrackedPlayer player, Instant attemptedAt, Category category) {
        collectionRepository.recordFailure(player, attemptedAt, category);
    }

    @Transactional(readOnly = true)
    public List<CollectionStatus> statuses() {
        return collectionRepository.statuses().stream()
                .map(MPlusCollectionPersistenceService::toCollectionStatus)
                .toList();
    }

    private List<PendingRun> saveRuns(
            TrackedPlayer player,
            MPlusObservation observation,
            Instant observedAt
    ) {
        List<PendingRun> pendingRuns = new ArrayList<>();
        for (RunSummary run : observation.runs()) {
            StoredRun storedRun = collectionRepository.saveRunSummary(observation.season(), run, observedAt);
            collectionRepository.linkRunToProfile(storedRun.id(), player, observedAt);
            if (DETAILS_PENDING.equals(storedRun.detailsStatus())) {
                pendingRuns.add(new PendingRun(storedRun.id(), observation.season(), run.raiderIoRunId()));
            }
        }
        return pendingRuns;
    }

    private static CollectionStatus toCollectionStatus(CollectionStatusRow row) {
        return new CollectionStatus(
                row.profileId(),
                row.season(),
                row.region(),
                row.realm(),
                row.characterName(),
                row.lastAttemptAt(),
                row.lastSuccessAt(),
                row.raiderIoCrawledAt(),
                row.observedRunCount(),
                row.lastErrorCategory()
        );
    }

    public record PendingRun(long storedRunId, String season, long raiderIoRunId) {
    }

    public record CollectionStatus(
            long profileId,
            String season,
            String region,
            String realm,
            String characterName,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            Instant raiderIoCrawledAt,
            int observedRunCount,
            String lastErrorCategory
    ) {
    }
}
