package com.blackbox.wow.service;

import com.blackbox.wow.client.MPlusObservation;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.client.RaiderIoCollectionException;
import com.blackbox.wow.client.RaiderIoCollectionException.Category;
import com.blackbox.wow.properties.MPlusCollectionProperties;
import com.blackbox.wow.properties.MPlusDungeonProperties;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository;
import com.blackbox.wow.service.MPlusCollectionPersistenceService.CollectionStatus;
import com.blackbox.wow.service.MPlusCollectionPersistenceService.PendingRun;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MPlusDataCollectionService {

    private final RaiderIoClient raiderIoClient;
    private final TrackedPlayerService trackedPlayerService;
    private final MPlusCollectionPersistenceService persistence;
    private final MPlusCollectionProperties properties;
    private final MPlusDungeonProperties dungeonProperties;
    private final MPlusDungeonVaultRepository dungeonVaultRepository;
    private final Clock clock;
    private Instant staticDataRefreshAfter = Instant.EPOCH;

    public MPlusDataCollectionService(
            RaiderIoClient raiderIoClient,
            TrackedPlayerService trackedPlayerService,
            MPlusCollectionPersistenceService persistence,
            MPlusCollectionProperties properties,
            MPlusDungeonProperties dungeonProperties,
            MPlusDungeonVaultRepository dungeonVaultRepository,
            Clock clock
    ) {
        this.raiderIoClient = raiderIoClient;
        this.trackedPlayerService = trackedPlayerService;
        this.persistence = persistence;
        this.properties = properties;
        this.dungeonProperties = dungeonProperties;
        this.dungeonVaultRepository = dungeonVaultRepository;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${wow.mplus-collection.cron:0 */30 * * * *}",
            zone = "${wow.mplus-collection.zone:Europe/Zagreb}"
    )
    public void collectScheduledData() {
        if (!properties.enabled()) {
            return;
        }
        refreshStaticDataIfNeeded();
        for (TrackedPlayer player : trackedPlayerService.activePlayers()) {
            collectPlayer(player);
        }
    }

    private void refreshStaticDataIfNeeded() {
        Instant now = clock.instant();
        if (staticDataRefreshAfter.isAfter(now)) {
            return;
        }
        try {
            dungeonVaultRepository.saveStaticData(
                    raiderIoClient.getMPlusStaticData(dungeonProperties.expansionId()),
                    now
            );
            staticDataRefreshAfter = now.plus(Duration.ofHours(24));
        } catch (RaiderIoCollectionException e) {
            log.warn("Mythic+ static-data refresh failed ({})", e.category());
        } catch (RuntimeException e) {
            log.error("Unexpected Mythic+ static-data refresh failure ({})", e.getClass().getSimpleName());
        }
    }

    public String statusMessage() {
        List<TrackedPlayer> players = trackedPlayerService.activePlayers();
        Map<Long, CollectionStatus> statuses = persistence.statuses().stream()
                .collect(Collectors.toUnmodifiableMap(CollectionStatus::profileId, Function.identity()));
        StringBuilder message = new StringBuilder("Mythic+ data collection\n");
        if (!properties.enabled()) {
            message.append("Status: disabled\n");
        }
        if (players.isEmpty()) {
            return message.append("No active player profiles have a selected character.").toString();
        }
        Instant now = clock.instant();
        for (TrackedPlayer player : players) {
            appendPlayerStatus(message, player, statuses.get(player.profileId()), now);
        }
        return message.append("\nSchedule: every 30 minutes. Runs are observed from Raider.IO; ")
                .append("this is not a complete historical import.")
                .toString();
    }

    private void collectPlayer(TrackedPlayer player) {
        Instant attemptedAt = clock.instant();
        try {
            MPlusObservation observation = raiderIoClient.getMPlusObservation(
                    player.region(),
                    player.realm(),
                    player.name()
            );
            List<PendingRun> pendingRuns = persistence.saveObservation(player, observation, attemptedAt);
            loadPendingDetails(pendingRuns);
        } catch (RaiderIoCollectionException e) {
            recordFailureSafely(player, attemptedAt, e.category());
            log.warn("Mythic+ collection failed for profile {} ({})", player.profileName(), e.category());
        } catch (RuntimeException e) {
            recordFailureSafely(player, attemptedAt, Category.UNKNOWN);
            log.error("Unexpected Mythic+ collection failure for profile {} ({})",
                    player.profileName(), e.getClass().getSimpleName());
        }
    }

    private void recordFailureSafely(TrackedPlayer player, Instant attemptedAt, Category category) {
        try {
            persistence.recordFailure(player, attemptedAt, category);
        } catch (RuntimeException persistenceFailure) {
            log.error("Could not persist Mythic+ collection status for profile {} ({})",
                    player.profileName(), persistenceFailure.getClass().getSimpleName());
        }
    }

    private void loadPendingDetails(List<PendingRun> pendingRuns) {
        int requestCount = Math.min(pendingRuns.size(), properties.maxDetailRequestsPerProfile());
        for (int index = 0; index < requestCount; index++) {
            PendingRun pendingRun = pendingRuns.get(index);
            try {
                persistence.saveRunDetails(
                        pendingRun.storedRunId(),
                        raiderIoClient.getMPlusRunDetails(pendingRun.season(), pendingRun.raiderIoRunId())
                );
            } catch (RaiderIoCollectionException e) {
                handleRunDetailsFailure(pendingRun, e);
            } catch (RuntimeException e) {
                log.error("Could not persist Mythic+ details for stored run {} ({})",
                        pendingRun.storedRunId(), e.getClass().getSimpleName());
            }
        }
    }

    private void handleRunDetailsFailure(PendingRun pendingRun, RaiderIoCollectionException failure) {
        if (failure.category() == Category.NOT_FOUND || failure.category() == Category.INVALID_RESPONSE) {
            persistence.markRunDetailsUnavailable(pendingRun.storedRunId());
        }
        log.warn("Mythic+ run-details collection failed for stored run {} ({})",
                pendingRun.storedRunId(), failure.category());
    }

    private static void appendPlayerStatus(
            StringBuilder message,
            TrackedPlayer player,
            CollectionStatus status,
            Instant now
    ) {
        message.append("\n• ").append(player.profileName()).append(" — ");
        if (status == null) {
            message.append("waiting for first collection");
            return;
        }
        if (status.lastErrorCategory() != null) {
            message.append("failed: ").append(
                    status.lastErrorCategory().replace('_', ' ').toLowerCase(Locale.ROOT)
            );
            if (status.lastSuccessAt() != null) {
                message.append("; last success ").append(formatAge(status.lastSuccessAt(), now)).append(" ago");
            }
        } else {
            message.append("OK — ").append(formatAge(status.lastSuccessAt(), now)).append(" ago");
        }
        message.append(", ")
                .append(status.observedRunCount())
                .append(" visible runs (")
                .append(status.characterName())
                .append('-')
                .append(status.realm())
                .append(")");
    }

    private static String formatAge(Instant timestamp, Instant now) {
        if (timestamp == null) {
            return "never";
        }
        Duration age = Duration.between(timestamp, now);
        if (age.isNegative() || age.toMinutes() < 1) {
            return "less than a minute";
        }
        if (age.toHours() < 1) {
            return age.toMinutes() + "m";
        }
        if (age.toDays() < 1) {
            return age.toHours() + "h";
        }
        return age.toDays() + "d";
    }
}
