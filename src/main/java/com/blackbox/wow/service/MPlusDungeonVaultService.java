package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusDungeonProperties;
import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository.DungeonCoverage;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository.VaultHistory;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class MPlusDungeonVaultService {

    private static final DateTimeFormatter WEEK_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MPlusPlayerResolver playerResolver;
    private final MPlusProgressRepository progressRepository;
    private final MPlusDungeonVaultRepository dungeonVaultRepository;
    private final MPlusDungeonProperties dungeonProperties;
    private final MPlusProgressProperties progressProperties;

    public MPlusDungeonVaultService(
            MPlusPlayerResolver playerResolver,
            MPlusProgressRepository progressRepository,
            MPlusDungeonVaultRepository dungeonVaultRepository,
            MPlusDungeonProperties dungeonProperties,
            MPlusProgressProperties progressProperties
    ) {
        this.playerResolver = playerResolver;
        this.progressRepository = progressRepository;
        this.dungeonVaultRepository = dungeonVaultRepository;
        this.dungeonProperties = dungeonProperties;
        this.progressProperties = progressProperties;
    }

    public String dungeonCoverageMessage(String argument, Long telegramUserId) {
        Resolution resolution = playerResolver.resolveSelfOrNamed(argument, telegramUserId);
        if (resolution.error() != null) {
            return resolution.error();
        }
        TrackedPlayer player = resolution.player();
        Optional<ScorePoint> latestScore = progressRepository.latestScore(player.profileId());
        if (latestScore.isEmpty()) {
            return "Dungeon coverage for " + player.profileName()
                    + " is unavailable until its first collection completes.";
        }
        List<DungeonCoverage> dungeons = dungeonVaultRepository.dungeonCoverage(
                player.profileId(), latestScore.get().season()
        );
        if (dungeons.isEmpty()) {
            return "The dungeon pool for " + latestScore.get().season()
                    + " is unavailable until Raider.IO static data is collected.";
        }
        return formatDungeonCoverage(player, latestScore.get().season(), dungeons);
    }

    public String vaultHistoryMessage(String argument, Long telegramUserId) {
        Resolution resolution = playerResolver.resolveSelfOrNamed(argument, telegramUserId);
        if (resolution.error() != null) {
            return resolution.error();
        }
        TrackedPlayer player = resolution.player();
        List<VaultHistory> history = dungeonVaultRepository.vaultHistory(
                player.profileId(), dungeonProperties.historyWeeks()
        );
        if (history.isEmpty()) {
            return "No finalized Mythic+ vault history is available for " + player.profileName() + " yet.";
        }
        return formatVaultHistory(player, history);
    }

    private String formatDungeonCoverage(
            TrackedPlayer player,
            String season,
            List<DungeonCoverage> dungeons
    ) {
        int target = dungeonProperties.targetLevel();
        long covered = dungeons.stream()
                .filter(dungeon -> dungeon.highestTimedLevel() != null && dungeon.highestTimedLevel() >= target)
                .count();
        StringBuilder message = new StringBuilder("Mythic+ dungeons — ")
                .append(player.profileName())
                .append(" — ")
                .append(season)
                .append('\n')
                .append("Timed +")
                .append(target)
                .append(" coverage: ")
                .append(covered)
                .append('/')
                .append(dungeons.size())
                .append('\n');
        for (DungeonCoverage dungeon : dungeons) {
            message.append("• ").append(dungeon.shortName()).append(" — ")
                    .append(formatHighestTimed(dungeon.highestTimedLevel()))
                    .append(" | score ")
                    .append(formatOptionalScore(dungeon.bestObservedScore()))
                    .append('\n');
        }
        appendRecommendation(message, dungeons, target);
        return message.append("\nBased on timed runs observed by BlackBox; depleted runs do not satisfy coverage.")
                .toString();
    }

    private static void appendRecommendation(
            StringBuilder message,
            List<DungeonCoverage> dungeons,
            int target
    ) {
        List<String> missing = dungeons.stream()
                .filter(dungeon -> dungeon.highestTimedLevel() == null)
                .map(DungeonCoverage::shortName)
                .toList();
        if (!missing.isEmpty()) {
            message.append("Missing timed dungeons: ").append(String.join(", ", missing));
            return;
        }
        Optional<DungeonCoverage> weakest = dungeons.stream()
                .filter(dungeon -> dungeon.highestTimedLevel() != null
                        && dungeon.highestTimedLevel() < target)
                .min(Comparator.comparingInt(DungeonCoverage::highestTimedLevel)
                        .thenComparing(DungeonCoverage::shortName));
        if (weakest.isPresent()) {
            message.append("Weakest coverage: ")
                    .append(weakest.get().shortName())
                    .append(" (+")
                    .append(weakest.get().highestTimedLevel())
                    .append(")");
        } else {
            message.append("All current-season dungeons meet the +").append(target).append(" target.");
        }
    }

    private String formatVaultHistory(TrackedPlayer player, List<VaultHistory> history) {
        StringBuilder message = new StringBuilder("Great Vault history — Mythic+ only — ")
                .append(player.profileName())
                .append('\n');
        for (VaultHistory week : history) {
            message.append("• Week of ")
                    .append(WEEK_DATE.format(toResetDate(week)))
                    .append(" — ")
                    .append(week.season())
                    .append(" — ")
                    .append(week.runCount())
                    .append(" runs | 1: ")
                    .append(formatSlot(week.slotOne()))
                    .append(" | 4: ")
                    .append(formatSlot(week.slotFour()))
                    .append(" | 8: ")
                    .append(formatSlot(week.slotEight()))
                    .append('\n');
        }
        return message.append("Max-vault streak: ")
                .append(longestMaxVaultStreak(history))
                .append(" week(s)\n")
                .append("Delves and regular Mythic dungeons are not included.")
                .toString();
    }

    private int longestMaxVaultStreak(List<VaultHistory> history) {
        List<VaultHistory> chronological = new ArrayList<>(history);
        chronological.sort(Comparator.comparing(VaultHistory::resetPeriodStart));
        int longest = 0;
        int current = 0;
        LocalDate previousDate = null;
        for (VaultHistory week : chronological) {
            LocalDate date = toResetDate(week);
            if (week.slotEight() != null) {
                current = previousDate != null && previousDate.plusWeeks(1).equals(date) ? current + 1 : 1;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
            previousDate = date;
        }
        return longest;
    }

    private LocalDate toResetDate(VaultHistory week) {
        return week.resetPeriodStart().atZone(progressProperties.resetZone()).toLocalDate();
    }

    private static String formatHighestTimed(Integer level) {
        return level == null ? "no timed run" : "+" + level + " timed";
    }

    private static String formatOptionalScore(BigDecimal score) {
        return score == null ? "unavailable" : score.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatSlot(Integer level) {
        return level == null ? "locked" : "+" + level;
    }

}
