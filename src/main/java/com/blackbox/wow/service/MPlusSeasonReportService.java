package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.helper.VaultSlotCalculator;
import com.blackbox.wow.helper.VaultSlotCalculator.VaultSlots;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class MPlusSeasonReportService {

    private static final String MIDNIGHT_SEASON_ONE = "season-mn-1";
    private static final Duration RECAP_CACHE_TTL = Duration.ofHours(24);
    private static final Duration FAILED_RECAP_CACHE_TTL = Duration.ofMinutes(10);

    private final RaiderIoClient raiderIoClient;
    private final RaiderIoAbandonedRunService abandonedRunService;
    private final TrackedPlayerService trackedPlayerService;
    private final Clock clock;
    private RunCountsCache runCountsCache;

    public MPlusSeasonReportService(
            RaiderIoClient raiderIoClient,
            RaiderIoAbandonedRunService abandonedRunService,
            TrackedPlayerService trackedPlayerService,
            Clock clock
    ) {
        this.raiderIoClient = raiderIoClient;
        this.abandonedRunService = abandonedRunService;
        this.trackedPlayerService = trackedPlayerService;
        this.clock = clock;
    }

    public synchronized String combinedRecap() {
        StringBuilder message = new StringBuilder("Midnight Season 1 M+ combined recap\n");
        for (PlayerRunCounts result : seasonRunCounts()) {
            appendCombinedRecap(message, result);
        }
        message.append("\nPercentages use timed + depleted + recorded abandoned as the total.\n")
                .append("Data: https://raider.io");
        return message.toString().trim();
    }

    public synchronized String depletedRecap() {
        StringBuilder message = new StringBuilder("Midnight Season 1 M+ timed/depleted recap\n");
        for (PlayerRunCounts result : seasonRunCounts()) {
            if (result.runCounts() == null) {
                message.append("\n• ").append(result.player().name()).append(": data unavailable\n");
            } else {
                appendDepletedRecap(message, result.runCounts());
            }
        }
        message.append("\nData: https://raider.io");
        return message.toString().trim();
    }

    public String abandonedRecap() {
        StringBuilder message = new StringBuilder("Midnight Season 1 M+ abandoned recap\n");
        for (TrackedPlayer player : trackedPlayerService.seasonRecapPlayers()) {
            appendAbandonedRecap(message, player);
        }
        return message.toString().trim();
    }

    public String weeklyVaultWatch() {
        List<TrackedPlayer> players = trackedPlayerService.vaultWatchPlayers();
        if (players.isEmpty()) {
            return "No Mythic+ vault watch players configured.";
        }

        StringBuilder message = new StringBuilder("Great Vault — Mythic+ only\n")
                .append("Delves and regular Mythic dungeons are not included.\n");
        for (TrackedPlayer player : players) {
            appendVaultWatchPlayer(message, player);
        }
        return message.toString().trim();
    }

    public String weeklyVault(String region, String realm, String name) {
        try {
            return formatWeeklyVault(raiderIoClient.getWeeklyVaultProgress(region, realm, name));
        } catch (RuntimeException _) {
            return """
                    Couldn’t fetch weekly Mythic+ vault data for %s on %s (%s).
                    Use: /vault <realm> <name>
                    Example: /vault stormscale bucothered
                    """.formatted(name, realm, region).strip();
        }
    }

    private void appendCombinedRecap(StringBuilder message, PlayerRunCounts result) {
        if (result.runCounts() == null) {
            message.append("\n• ").append(result.player().name()).append(": completed-run data unavailable\n");
            return;
        }

        RaiderIoClient.MPlusSeasonRunCounts recap = result.runCounts();
        int completed = recap.dungeons().stream().mapToInt(RaiderIoClient.DungeonRunCount::total).sum();
        int timed = recap.dungeons().stream().mapToInt(RaiderIoClient.DungeonRunCount::timed).sum();
        int depleted = completed - timed;
        var abandoned = abandonedRunService.findLatest(
                result.player().region(),
                result.player().realm(),
                result.player().name(),
                MIDNIGHT_SEASON_ONE
        );

        message.append("\n• ").append(recap.name())
                .append(": ").append(timed).append(" timed | ")
                .append(depleted).append(" depleted | ");
        if (abandoned.isEmpty()) {
            message.append("abandoned not recorded\n")
                    .append("  Combined percentages: unavailable\n");
        } else {
            int abandonedRuns = abandoned.get().abandonedRuns();
            int attempts = completed + abandonedRuns;
            message.append(abandonedRuns).append(" abandoned (recorded)\n")
                    .append("  Percentages: timed ").append(formatPercentage(timed, attempts))
                    .append(" | depleted ").append(formatPercentage(depleted, attempts))
                    .append(" | abandoned ").append(formatPercentage(abandonedRuns, attempts)).append('\n');
        }

        appendMostPlayedAndDepleted(message, recap.dungeons());
        abandoned.ifPresent(summary -> message.append("  Most abandoned: ")
                .append(summary.mostAbandonedDungeon())
                .append(" (").append(summary.mostAbandonedDungeonRuns()).append(")\n"));
    }

    private void appendAbandonedRecap(StringBuilder message, TrackedPlayer player) {
        var abandoned = abandonedRunService.findLatest(
                player.region(),
                player.realm(),
                player.name(),
                MIDNIGHT_SEASON_ONE
        );
        if (abandoned.isEmpty()) {
            message.append("\n• ").append(player.name()).append(": not recorded\n");
            return;
        }

        var summary = abandoned.get();
        message.append("\n• ").append(summary.characterName())
                .append(": ").append(summary.abandonedRuns()).append(" abandoned runs recorded")
                .append(" (of ").append(summary.liveTrackedRuns()).append(" live-tracked attempts, ")
                .append(formatPercentage(summary.abandonedRuns(), summary.liveTrackedRuns())).append(")\n")
                .append("  Most abandoned: ").append(summary.mostAbandonedDungeon())
                .append(" (").append(summary.mostAbandonedDungeonRuns()).append(")\n");
    }

    private List<PlayerRunCounts> seasonRunCounts() {
        Instant now = clock.instant();
        List<TrackedPlayer> players = trackedPlayerService.seasonRecapPlayers();
        if (isCurrent(runCountsCache, players, now)) {
            return runCountsCache.results();
        }

        boolean hadError = false;
        List<PlayerRunCounts> results = new ArrayList<>();
        for (TrackedPlayer player : players) {
            try {
                results.add(new PlayerRunCounts(player, raiderIoClient.getMPlusSeasonRunCounts(
                        player.region(), player.realm(), player.name(), MIDNIGHT_SEASON_ONE
                )));
            } catch (RuntimeException _) {
                hadError = true;
                results.add(new PlayerRunCounts(player, null));
            }
        }

        Duration ttl = hadError ? FAILED_RECAP_CACHE_TTL : RECAP_CACHE_TTL;
        List<PlayerRunCounts> cachedResults = List.copyOf(results);
        runCountsCache = new RunCountsCache(players, cachedResults, now.plus(ttl));
        return cachedResults;
    }

    private static boolean isCurrent(RunCountsCache cache, List<TrackedPlayer> players, Instant now) {
        return cache != null && cache.expiresAt().isAfter(now) && cache.players().equals(players);
    }

    private static void appendDepletedRecap(
            StringBuilder message,
            RaiderIoClient.MPlusSeasonRunCounts recap
    ) {
        List<RaiderIoClient.DungeonRunCount> dungeons = recap.dungeons() == null
                ? List.of()
                : recap.dungeons();
        int total = dungeons.stream().mapToInt(RaiderIoClient.DungeonRunCount::total).sum();
        int timed = dungeons.stream().mapToInt(RaiderIoClient.DungeonRunCount::timed).sum();

        message.append("\n• ").append(recap.name())
                .append(": ").append(total).append(" completed | ")
                .append(timed).append(" timed (").append(formatPercentage(timed, total)).append(") | ")
                .append(total - timed).append(" depleted\n");
        if (total == 0) {
            message.append("  Most played: none\n  Most depleted: none\n");
            return;
        }
        appendMostPlayedAndDepleted(message, dungeons);
    }

    private static void appendMostPlayedAndDepleted(
            StringBuilder message,
            List<RaiderIoClient.DungeonRunCount> dungeons
    ) {
        RaiderIoClient.DungeonRunCount mostPlayed = dungeons.stream()
                .max(Comparator.comparingInt(RaiderIoClient.DungeonRunCount::total))
                .orElse(null);
        RaiderIoClient.DungeonRunCount mostDepleted = dungeons.stream()
                .max(Comparator.comparingInt(RaiderIoClient.DungeonRunCount::depleted))
                .orElse(null);

        message.append("  Most played: ").append(formatDungeonCount(mostPlayed, false)).append('\n')
                .append("  Most depleted: ")
                .append(mostDepleted == null || mostDepleted.depleted() == 0
                        ? "none"
                        : formatDungeonCount(mostDepleted, true))
                .append('\n');
    }

    private void appendVaultWatchPlayer(StringBuilder message, TrackedPlayer player) {
        try {
            RaiderIoClient.WeeklyVaultProgress progress = raiderIoClient.getWeeklyVaultProgress(
                    player.region(), player.realm(), player.name()
            );
            message.append("• ").append(progress.name()).append(": ")
                    .append(formatVaultSlotSummary(progress.runs())).append('\n');
        } catch (RuntimeException _) {
            message.append("• ").append(player.name()).append(": error\n");
        }
    }

    private static String formatWeeklyVault(RaiderIoClient.WeeklyVaultProgress progress) {
        List<RaiderIoClient.MPlusRun> runs = progress.runs() == null ? List.of() : progress.runs();
        StringBuilder message = new StringBuilder("Great Vault — Mythic+ only\n");
        message.append("Delves and regular Mythic dungeons are not included.\n")
                .append(progress.name()).append(" - ").append(progress.realm())
                .append(" (").append(progress.region()).append(")\n")
                .append("Top weekly Mythic+ runs from Raider.IO: ").append(runs.size()).append('\n')
                .append("Slot 1 (1 run): ").append(formatVaultSlot(runs, 1)).append('\n')
                .append("Slot 2 (4 runs): ").append(formatVaultSlot(runs, 4)).append('\n')
                .append("Slot 3 (8 runs): ").append(formatVaultSlot(runs, 8)).append('\n');
        appendTopRuns(message, runs);
        if (progress.profileUrl() != null && !progress.profileUrl().isBlank()) {
            message.append("\nProfile: ").append(progress.profileUrl());
        }
        return message.toString().trim();
    }

    private static void appendTopRuns(StringBuilder message, List<RaiderIoClient.MPlusRun> runs) {
        if (runs.isEmpty()) {
            message.append("Top runs: none found for the current reset");
            return;
        }
        message.append("Top runs:\n");
        for (int index = 0; index < Math.min(runs.size(), 8); index++) {
            RaiderIoClient.MPlusRun run = runs.get(index);
            message.append(index + 1).append(". +").append(run.level())
                    .append(' ').append(run.dungeon()).append('\n');
        }
    }

    private static String formatVaultSlotSummary(List<RaiderIoClient.MPlusRun> runs) {
        List<RaiderIoClient.MPlusRun> safeRuns = runs == null ? List.of() : runs;
        if (safeRuns.isEmpty()) {
            return "no current-reset Mythic+ runs found";
        }
        return "top " + safeRuns.size()
               + " | 1: " + formatVaultSlot(safeRuns, 1)
               + " | 4: " + formatVaultSlot(safeRuns, 4)
               + " | 8: " + formatVaultSlot(safeRuns, 8);
    }

    private static String formatVaultSlot(List<RaiderIoClient.MPlusRun> runs, int requiredRuns) {
        VaultSlots slots = VaultSlotCalculator.calculate(runs.stream().map(RaiderIoClient.MPlusRun::level).toList());
        Integer level = switch (requiredRuns) {
            case 1 -> slots.slotOne();
            case 4 -> slots.slotFour();
            case 8 -> slots.slotEight();
            default -> throw new IllegalArgumentException("Unsupported vault slot: " + requiredRuns);
        };
        return level == null ? "locked (" + (requiredRuns - slots.runCount()) + " more)" : "+" + level;
    }

    private static String formatPercentage(int part, int total) {
        if (total <= 0) {
            return "0%";
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "%";
    }

    private static String formatDungeonCount(RaiderIoClient.DungeonRunCount dungeon, boolean depleted) {
        if (dungeon == null) {
            return "none";
        }
        int count = depleted ? dungeon.depleted() : dungeon.total();
        return dungeon.shortName() + " (" + count + ")";
    }

    private record PlayerRunCounts(TrackedPlayer player, RaiderIoClient.MPlusSeasonRunCounts runCounts) {
    }

    private record RunCountsCache(
            List<TrackedPlayer> players,
            List<PlayerRunCounts> results,
            Instant expiresAt
    ) {
    }
}
