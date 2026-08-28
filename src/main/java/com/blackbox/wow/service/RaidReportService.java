package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardApiClient;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.helper.MPlusResetCalendar;
import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.properties.RaceToWorldFirstProperties;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RaidReportService {

    private static final int RAID_SLOT_ONE_BOSSES = 2;
    private static final int RAID_SLOT_TWO_BOSSES = 4;
    private static final int RAID_SLOT_THREE_BOSSES = 6;
    private static final String PROFILE_RAIDS_PATH =
            "/profile/wow/character/{realmSlug}/{characterName}/encounters/raids";

    private final RaiderIoClient raiderIoClient;
    private final BlizzardApiClient blizzardApiClient;
    private final RaceToWorldFirstProperties raidProperties;
    private final MPlusResetCalendar resetCalendar;
    private final Clock clock;

    public RaidReportService(
            RaiderIoClient raiderIoClient,
            BlizzardApiClient blizzardApiClient,
            RaceToWorldFirstProperties raidProperties,
            MPlusProgressProperties progressProperties,
            Clock clock
    ) {
        this.raiderIoClient = raiderIoClient;
        this.blizzardApiClient = blizzardApiClient;
        this.raidProperties = raidProperties;
        this.resetCalendar = new MPlusResetCalendar(progressProperties);
        this.clock = clock;
    }

    public String progress(List<TrackedPlayer> players) {
        if (players.isEmpty()) {
            return "No active profiles are available for raid progress.";
        }
        List<RaidProgressRow> rows = players.stream()
                .map(this::loadRaidProgress)
                .sorted(Comparator
                        .comparing(RaidProgressRow::ranking, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(row -> row.player().profileName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        StringBuilder message = new StringBuilder("Raid Progress — ")
                .append(raidProperties.raidName()).append("\n\n");
        for (RaidProgressRow row : rows) {
            appendProgress(message, row);
        }
        return message.append("\nData: https://raider.io").toString();
    }

    public String weeklyVault(List<TrackedPlayer> players) {
        if (players.isEmpty()) {
            return "No active profiles are available for raid vault progress.";
        }
        Instant resetStartedAt = resetCalendar.periodStart(clock.instant());
        List<RaidVaultRow> rows = players.stream()
                .map(player -> loadRaidVault(player, resetStartedAt))
                .sorted(Comparator
                        .comparing(RaidVaultRow::ranking, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(row -> row.player().profileName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        StringBuilder message = new StringBuilder("Great Vault — Raid only — current week\n")
                .append(raidProperties.raidName()).append("\n\n");
        for (RaidVaultRow row : rows) {
            appendRaidVault(message, row);
        }
        return message.append("Raid slots require 2, 4, and 6 unique bosses this reset.").toString();
    }

    private RaidProgressRow loadRaidProgress(TrackedPlayer player) {
        try {
            RaiderIoClient.CharacterRaidProgress progress = raiderIoClient.getCharacterRaidProgress(
                    player.region(), player.realm(), player.name(), raidProperties.raidSlug()
            );
            return new RaidProgressRow(player, progress);
        } catch (RuntimeException _) {
            return new RaidProgressRow(player, null);
        }
    }

    private static void appendProgress(StringBuilder message, RaidProgressRow row) {
        TrackedPlayer player = row.player();
        RaiderIoClient.CharacterRaidProgress progress = row.progress();
        if (progress == null) {
            appendUnavailableProfile(message, player);
            return;
        }
        int total = progress.totalBosses();
        message.append("• ").append(player.profileName()).append(" (")
                .append(progress.name()).append(")\n  ")
                .append(progress.normalBossesKilled()).append('/').append(total).append(" NM | ")
                .append(progress.heroicBossesKilled()).append('/').append(total).append(" HC | ")
                .append(progress.mythicBossesKilled()).append('/').append(total).append(" M\n");
    }

    private RaidVaultRow loadRaidVault(TrackedPlayer player, Instant resetStartedAt) {
        try {
            JsonNode raids = blizzardApiClient.get(
                    PROFILE_RAIDS_PATH,
                    Map.of("realmSlug", slug(player.realm()), "characterName", slug(player.name())),
                    blizzardApiClient.profileQuery()
            );
            List<RaidBossKill> kills = weeklyBossKills(raids, raidProperties.raidName(), resetStartedAt);
            return new RaidVaultRow(player, kills);
        } catch (RuntimeException _) {
            return new RaidVaultRow(player, null);
        }
    }

    private static void appendRaidVault(StringBuilder message, RaidVaultRow row) {
        TrackedPlayer player = row.player();
        List<RaidBossKill> kills = row.kills();
        if (kills == null) {
            appendUnavailableProfile(message, player);
            message.append('\n');
            return;
        }
        message.append("• ").append(player.profileName()).append(" (").append(player.name()).append(")\n")
                .append("  Bosses this reset: ").append(kills.size()).append('/').append(RAID_SLOT_THREE_BOSSES)
                .append('\n')
                .append("  Slot 1 (2 bosses): ").append(formatSlot(kills, RAID_SLOT_ONE_BOSSES)).append('\n')
                .append("  Slot 2 (4 bosses): ").append(formatSlot(kills, RAID_SLOT_TWO_BOSSES)).append('\n')
                .append("  Slot 3 (6 bosses): ").append(formatSlot(kills, RAID_SLOT_THREE_BOSSES)).append("\n\n");
    }

    static List<RaidBossKill> weeklyBossKills(JsonNode raids, String raidName, Instant resetStartedAt) {
        Map<String, RaidBossKill> highestKillByBoss = new LinkedHashMap<>();
        for (JsonNode expansion : raids.path("expansions")) {
            for (JsonNode instance : expansion.path("instances")) {
                if (!sameSlug(instance.path("instance").path("name").asText(""), raidName)) {
                    continue;
                }
                collectInstanceKills(instance, resetStartedAt, highestKillByBoss);
            }
        }
        return highestKillByBoss.values().stream()
                .sorted(Comparator.comparingInt(RaidBossKill::difficultyRank).reversed())
                .toList();
    }

    private static void collectInstanceKills(
            JsonNode instance,
            Instant resetStartedAt,
            Map<String, RaidBossKill> highestKillByBoss
    ) {
        for (JsonNode mode : instance.path("modes")) {
            RaidDifficulty difficulty = RaidDifficulty.from(
                    mode.path("difficulty").path("type").asText("")
            );
            if (difficulty == null) {
                continue;
            }
            for (JsonNode encounter : mode.path("progress").path("encounters")) {
                long lastKillTimestamp = encounter.path("last_kill_timestamp").asLong(0);
                if (encounter.path("completed_count").asInt(0) <= 0
                        || lastKillTimestamp < resetStartedAt.toEpochMilli()) {
                    continue;
                }
                String bossKey = bossKey(encounter);
                RaidBossKill kill = new RaidBossKill(bossKey, difficulty.label, difficulty.rank);
                highestKillByBoss.merge(
                        bossKey,
                        kill,
                        (existing, replacement) -> existing.difficultyRank() >= replacement.difficultyRank()
                                ? existing
                                : replacement
                );
            }
        }
    }

    private static String bossKey(JsonNode encounter) {
        long id = encounter.path("encounter").path("id").asLong(0);
        return id > 0
                ? Long.toString(id)
                : slug(encounter.path("encounter").path("name").asText("unknown"));
    }

    private static String formatSlot(List<RaidBossKill> kills, int requiredBosses) {
        if (kills.size() < requiredBosses) {
            return "locked (" + (requiredBosses - kills.size()) + " more)";
        }
        return kills.get(requiredBosses - 1).difficulty();
    }

    private static void appendUnavailableProfile(StringBuilder message, TrackedPlayer player) {
        message.append("• ").append(player.profileName()).append(" (").append(player.name())
                .append("): unavailable\n");
    }

    private static boolean sameSlug(String left, String right) {
        return slug(left).equals(slug(right));
    }

    private static String slug(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    record RaidBossKill(String boss, String difficulty, int difficultyRank) {
    }

    private record RaidProgressRow(
            TrackedPlayer player,
            RaiderIoClient.CharacterRaidProgress progress
    ) {
        private Integer ranking() {
            if (progress == null) {
                return null;
            }
            return progress.mythicBossesKilled() * 10_000
                    + progress.heroicBossesKilled() * 100
                    + progress.normalBossesKilled();
        }
    }

    private record RaidVaultRow(TrackedPlayer player, List<RaidBossKill> kills) {
        private Integer ranking() {
            if (kills == null) {
                return null;
            }
            int difficultyTotal = kills.stream().mapToInt(RaidBossKill::difficultyRank).sum();
            return kills.size() * 100 + difficultyTotal;
        }
    }

    private enum RaidDifficulty {
        NORMAL("Normal", 1),
        HEROIC("Heroic", 2),
        MYTHIC("Mythic", 3);

        private final String label;
        private final int rank;

        RaidDifficulty(String label, int rank) {
            this.label = label;
            this.rank = rank;
        }

        private static RaidDifficulty from(String type) {
            try {
                return valueOf(type.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException _) {
                return null;
            }
        }
    }
}
