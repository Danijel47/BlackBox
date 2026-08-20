package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.client.RaiderIoClient.MPlusRun;
import com.blackbox.wow.client.RaiderIoClient.WeeklyVaultProgress;
import com.blackbox.wow.helper.VaultSlotCalculator;
import com.blackbox.wow.helper.VaultSlotCalculator.VaultSlots;
import com.blackbox.wow.properties.MPlusDungeonProperties;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository.DungeonCoverage;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class MPlusDungeonVaultService {

    private final MPlusPlayerResolver playerResolver;
    private final RaiderIoClient raiderIoClient;
    private final MPlusProgressRepository progressRepository;
    private final MPlusDungeonVaultRepository dungeonVaultRepository;
    private final MPlusDungeonProperties dungeonProperties;

    public MPlusDungeonVaultService(
            MPlusPlayerResolver playerResolver,
            RaiderIoClient raiderIoClient,
            MPlusProgressRepository progressRepository,
            MPlusDungeonVaultRepository dungeonVaultRepository,
            MPlusDungeonProperties dungeonProperties
    ) {
        this.playerResolver = playerResolver;
        this.raiderIoClient = raiderIoClient;
        this.progressRepository = progressRepository;
        this.dungeonVaultRepository = dungeonVaultRepository;
        this.dungeonProperties = dungeonProperties;
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

    public String currentVaultMessage(String argument, Long telegramUserId) {
        Resolution resolution = playerResolver.resolveSelfOrNamed(argument, telegramUserId);
        if (resolution.error() != null) {
            return resolution.error();
        }
        TrackedPlayer player = resolution.player();
        try {
            WeeklyVaultProgress progress = raiderIoClient.getWeeklyVaultProgress(
                    player.region(), player.realm(), player.name()
            );
            return formatCurrentVault(player, progress);
        } catch (RuntimeException exception) {
            return "Current Mythic+ vault progress is unavailable for " + player.profileName()
                    + " (" + player.name() + "-" + player.realm() + ").";
        }
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
        return message.append("\nBased on observed timed runs; depleted runs do not satisfy coverage.")
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

    private static String formatCurrentVault(TrackedPlayer player, WeeklyVaultProgress progress) {
        List<MPlusRun> runs = progress.runs() == null ? List.of() : progress.runs();
        VaultSlots slots = VaultSlotCalculator.calculate(runs.stream().map(MPlusRun::level).toList());
        StringBuilder message = new StringBuilder("Great Vault — current week — Mythic+ only — ")
                .append(player.profileName())
                .append('\n')
                .append("• ")
                .append(progress.name())
                .append('-')
                .append(progress.realm())
                .append(" — ")
                .append(slots.runCount())
                .append(" runs | 1: ")
                .append(formatSlot(slots.slotOne()))
                .append(" | 4: ")
                .append(formatSlot(slots.slotFour()))
                .append(" | 8: ")
                .append(formatSlot(slots.slotEight()));
        appendTopRuns(message, runs);
        return message.append("\nDelves and regular Mythic dungeons are not included.").toString();
    }

    private static void appendTopRuns(StringBuilder message, List<MPlusRun> runs) {
        if (runs.isEmpty()) {
            return;
        }
        message.append("\nTop runs:");
        for (MPlusRun run : runs.stream()
                .sorted(Comparator.comparingInt(MPlusRun::level).reversed())
                .limit(8)
                .toList()) {
            message.append("\n• +").append(run.level()).append(' ').append(run.dungeon());
        }
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
