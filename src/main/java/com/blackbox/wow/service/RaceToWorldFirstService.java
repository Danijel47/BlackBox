package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.client.RaiderIoClient.RaidBossDefeat;
import com.blackbox.wow.client.RaiderIoClient.RaidBossProgress;
import com.blackbox.wow.client.RaiderIoClient.RaidRanking;
import com.blackbox.wow.entity.RaceToWorldFirstNotificationEntity;
import com.blackbox.wow.properties.RaceToWorldFirstProperties;
import com.blackbox.wow.repository.RaceToWorldFirstNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

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
    public void checkForFirstBossKill() {
        if (!properties.enabled() || properties.chatId() == 0) {
            return;
        }
        try {
            validateConfiguration();
            String eventKey = eventKey();
            if (notificationRepository.existsById(eventKey)) {
                return;
            }
            List<RaidRanking> rankings = raiderIoClient.getMythicRaidRankings(
                    properties.raidSlug(),
                    MONITOR_RANKING_LIMIT
            );
            FirstBossKill firstKill = findFirstBossKill(rankings);
            if (firstKill != null && notifier.send(properties.chatId(), formatFirstKillMessage(firstKill))) {
                saveNotification(eventKey, firstKill);
            }
        } catch (RuntimeException e) {
            log.warn("Could not check the Race to World First first-boss kill: {}", e.getMessage());
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

    private FirstBossKill findFirstBossKill(List<RaidRanking> rankings) {
        FirstBossKill earliestKill = null;
        for (RaidRanking ranking : rankings) {
            for (RaidBossDefeat defeat : ranking.defeatedBosses()) {
                if (defeat.slug().equals(properties.firstBossSlug())) {
                    FirstBossKill candidate = new FirstBossKill(ranking, defeat.firstDefeatedAt());
                    if (earliestKill == null || candidate.defeatedAt().isBefore(earliestKill.defeatedAt())) {
                        earliestKill = candidate;
                    }
                }
            }
        }
        return earliestKill;
    }

    private String formatFirstKillMessage(FirstBossKill firstKill) {
        return "🏆 WORLD FIRST — MYTHIC BOSS ONE\n"
                + firstKill.ranking().guildName() + " (" + firstKill.ranking().region() + ") defeated "
                + properties.firstBossName() + ".\n"
                + properties.raidName() + ": 1/" + properties.bossCount() + " Mythic\n\n"
                + "Raider.IO: " + rankingUrl();
    }

    private void saveNotification(String eventKey, FirstBossKill firstKill) {
        notificationRepository.saveAndFlush(new RaceToWorldFirstNotificationEntity(
                eventKey,
                properties.raidSlug(),
                properties.firstBossSlug(),
                firstKill.ranking().guildName(),
                firstKill.defeatedAt(),
                Instant.now()
        ));
    }

    private String eventKey() {
        return properties.raidSlug() + ":mythic:" + properties.firstBossSlug();
    }

    private String rankingUrl() {
        return "https://raider.io/raid-rankings/" + properties.raidSlug() + "/world/mythic";
    }

    private void validateConfiguration() {
        if (!isSlug(properties.raidSlug())
                || !isSlug(properties.firstBossSlug())
                || properties.raidName() == null
                || properties.raidName().isBlank()
                || properties.firstBossName() == null
                || properties.firstBossName().isBlank()
                || properties.bossCount() <= 0) {
            throw new IllegalStateException("Race to World First configuration is invalid.");
        }
    }

    private static boolean isSlug(String value) {
        return value != null && value.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }

    private record FirstBossKill(RaidRanking ranking, Instant defeatedAt) {
    }
}
