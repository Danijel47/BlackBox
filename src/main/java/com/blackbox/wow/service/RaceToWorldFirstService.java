package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.client.RaiderIoClient.RaidBossDefeat;
import com.blackbox.wow.client.RaiderIoClient.RaidBossProgress;
import com.blackbox.wow.client.RaiderIoClient.RaidEncounter;
import com.blackbox.wow.client.RaiderIoClient.RaidRanking;
import com.blackbox.wow.entity.RaceToWorldFirstNotificationEntity;
import com.blackbox.wow.properties.RaceToWorldFirstProperties;
import com.blackbox.wow.repository.RaceToWorldFirstNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class RaceToWorldFirstService {

    private static final int LEADERBOARD_SIZE = 5;
    private static final int MONITOR_RANKING_LIMIT = 200;

    private final RaiderIoClient raiderIoClient;
    private final RaceToWorldFirstNotificationRepository notificationRepository;
    private final BlackBoxBotNotifier notifier;
    private final RaceToWorldFirstProperties properties;

    public RaceToWorldFirstService(
            RaiderIoClient raiderIoClient,
            RaceToWorldFirstNotificationRepository notificationRepository,
            BlackBoxBotNotifier notifier,
            RaceToWorldFirstProperties properties
    ) {
        this.raiderIoClient = raiderIoClient;
        this.notificationRepository = notificationRepository;
        this.notifier = notifier;
        this.properties = properties;
    }

    public String currentStandingsMessage() {
        validateConfiguration();
        List<RaidRanking> rankings = raiderIoClient.getMythicRaidRankings(
                properties.raidSlug(),
                LEADERBOARD_SIZE
        );
        return formatStandings(rankings);
    }

    @Scheduled(
            cron = "${wow.rwf.cron:0 */2 * * * *}",
            zone = "${wow.rwf.zone:Europe/Zagreb}"
    )
    public void checkForWorldFirstBossKills() {
        if (!properties.enabled() || properties.chatId() == 0) {
            return;
        }
        try {
            validateConfiguration();
            List<RaidRanking> rankings = raiderIoClient.getMythicRaidRankings(
                    properties.raidSlug(),
                    MONITOR_RANKING_LIMIT
            );
            notifyPendingWorldFirstKills(findWorldFirstBossKills(rankings));
        } catch (RuntimeException e) {
            log.warn("Could not check Race to World First boss kills ({})", e.getClass().getSimpleName());
        }
    }

    private String formatStandings(List<RaidRanking> rankings) {
        StringBuilder message = new StringBuilder("Race to World First — ")
                .append(properties.raidName())
                .append(" (Mythic)\n");
        if (rankings.isEmpty()) {
            message.append("No guild has a Mythic boss kill yet.\n");
        } else {
            for (int index = 0; index < rankings.size(); index++) {
                appendRanking(message, rankings.get(index), index + 1);
            }
        }
        return message.append("\nRaider.IO: ")
                .append(rankingUrl())
                .toString();
    }

    private void appendRanking(StringBuilder message, RaidRanking ranking, int fallbackRank) {
        int rank = ranking.rank() > 0 ? ranking.rank() : fallbackRank;
        int defeatedBosses = Math.min(ranking.defeatedBosses().size(), properties.bossCount());
        message.append(rank)
                .append(". ")
                .append(ranking.guildName())
                .append(" — ")
                .append(defeatedBosses)
                .append("/")
                .append(properties.bossCount())
                .append(" Mythic");
        appendBestPull(message, ranking.bossProgress());
        message.append(" (")
                .append(ranking.region())
                .append(")\n");
    }

    private static void appendBestPull(StringBuilder message, List<RaidBossProgress> bossProgress) {
        for (int index = bossProgress.size() - 1; index >= 0; index--) {
            RaidBossProgress progress = bossProgress.get(index);
            if (!progress.defeated()) {
                message.append(" — best pull: ")
                        .append(progress.bestPercent().stripTrailingZeros().toPlainString())
                        .append("% (")
                        .append(progress.pullCount())
                        .append(progress.pullCount() == 1 ? " pull)" : " pulls)");
                return;
            }
        }
    }

    private List<WorldFirstBossKill> findWorldFirstBossKills(List<RaidRanking> rankings) {
        Map<String, WorldFirstBossKill> earliestKills = new LinkedHashMap<>();
        for (RaidRanking ranking : rankings) {
            for (RaidBossDefeat defeat : ranking.defeatedBosses()) {
                if (!isSlug(defeat.slug())) {
                    continue;
                }
                WorldFirstBossKill candidate = new WorldFirstBossKill(ranking, defeat);
                earliestKills.merge(
                        defeat.slug(),
                        candidate,
                        (current, replacement) -> replacement.defeatedAt().isBefore(current.defeatedAt())
                                ? replacement
                                : current
                );
            }
        }
        return earliestKills.values().stream()
                .sorted(Comparator.comparing(WorldFirstBossKill::defeatedAt))
                .toList();
    }

    private void notifyPendingWorldFirstKills(List<WorldFirstBossKill> worldFirstKills) {
        List<PendingWorldFirstBossKill> pendingKills = new ArrayList<>();
        for (int index = 0; index < worldFirstKills.size(); index++) {
            WorldFirstBossKill worldFirstKill = worldFirstKills.get(index);
            if (!notificationRepository.existsById(eventKey(worldFirstKill.bossSlug()))) {
                pendingKills.add(new PendingWorldFirstBossKill(worldFirstKill, index + 1));
            }
        }
        if (pendingKills.isEmpty()) {
            return;
        }

        Map<String, RaidEncounter> encounters = loadEncounterMetadata();
        for (PendingWorldFirstBossKill pendingKill : pendingKills) {
            WorldFirstBossKill worldFirstKill = pendingKill.worldFirstKill();
            RaidEncounter encounter = encounters.get(worldFirstKill.bossSlug());
            String message = formatWorldFirstKillMessage(worldFirstKill, pendingKill.killNumber(), encounter);
            if (notifier.send(properties.chatId(), message)) {
                saveNotification(eventKey(worldFirstKill.bossSlug()), worldFirstKill);
            }
        }
    }

    private Map<String, RaidEncounter> loadEncounterMetadata() {
        Map<String, RaidEncounter> encounters = new LinkedHashMap<>();
        try {
            for (RaidEncounter encounter : raiderIoClient.getRaidEncounters(
                    properties.expansionId(),
                    properties.raidSlug()
            )) {
                encounters.put(encounter.slug(), encounter);
            }
        } catch (RuntimeException e) {
            log.warn("Could not load RWF encounter metadata ({})", e.getClass().getSimpleName());
        }
        return encounters;
    }

    private String formatWorldFirstKillMessage(
            WorldFirstBossKill worldFirstKill,
            int killNumber,
            RaidEncounter encounter
    ) {
        String bossName = encounter == null ? displayNameFromSlug(worldFirstKill.bossSlug()) : encounter.name();
        return "🏆 WORLD FIRST — MYTHIC BOSS " + killNumber + "\n"
                + worldFirstKill.ranking().guildName() + " (" + worldFirstKill.ranking().region()
                + ") defeated " + bossName + ".\n"
                + properties.raidName() + ": " + killNumber + "/" + properties.bossCount() + " Mythic\n\n"
                + "Raider.IO: " + rankingUrl();
    }

    private void saveNotification(String eventKey, WorldFirstBossKill worldFirstKill) {
        notificationRepository.saveAndFlush(new RaceToWorldFirstNotificationEntity(
                eventKey,
                properties.raidSlug(),
                worldFirstKill.bossSlug(),
                worldFirstKill.ranking().guildName(),
                worldFirstKill.defeatedAt(),
                Instant.now()
        ));
    }

    private String eventKey(String bossSlug) {
        return properties.raidSlug() + ":mythic:" + bossSlug;
    }

    private String rankingUrl() {
        return "https://raider.io/raid-rankings/" + properties.raidSlug() + "/world/mythic";
    }

    private void validateConfiguration() {
        if (!isSlug(properties.raidSlug())
                || properties.raidName() == null
                || properties.raidName().isBlank()
                || properties.bossCount() <= 0
                || properties.expansionId() <= 0) {
            throw new IllegalStateException("Race to World First configuration is invalid.");
        }
    }

    private static boolean isSlug(String value) {
        return value != null && value.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }

    private static String displayNameFromSlug(String slug) {
        StringBuilder name = new StringBuilder();
        for (String word : slug.split("-")) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(word.substring(0, 1).toUpperCase(Locale.ENGLISH))
                    .append(word.substring(1));
        }
        return name.toString();
    }

    private record WorldFirstBossKill(RaidRanking ranking, RaidBossDefeat defeat) {

        String bossSlug() {
            return defeat.slug();
        }

        Instant defeatedAt() {
            return defeat.firstDefeatedAt();
        }
    }

    private record PendingWorldFirstBossKill(WorldFirstBossKill worldFirstKill, int killNumber) {
    }
}
