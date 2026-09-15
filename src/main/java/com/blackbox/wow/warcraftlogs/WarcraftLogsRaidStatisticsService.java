package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class WarcraftLogsRaidStatisticsService {
    private static final String QUERY = """
            query CharacterRaidRankings($name: String!, $server: String!, $region: String!) {
              characterData {
                character(name: $name, serverSlug: $server, serverRegion: $region) {
                  name
                  normal: zoneRankings(difficulty: 3)
                  heroic: zoneRankings(difficulty: 4)
                  mythic: zoneRankings(difficulty: 5)
                }
              }
            }
            """;
    private final WarcraftLogsClient client;

    public WarcraftLogsRaidStatisticsService(WarcraftLogsClient client) { this.client = client; }

    public String raidCombatMessage(List<TrackedPlayer> players) {
        if (players.isEmpty()) return "No active profiles are available for raid combat parses.";
        List<Row> rows = players.stream().map(this::load).sorted(Comparator
                .comparingInt(Row::difficultyPriority)
                .thenComparing(Row::bestParse, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> row.player.profileName(), String.CASE_INSENSITIVE_ORDER)).toList();
        StringBuilder message = new StringBuilder("Raid Combat — best performance average\n\n");
        rows.forEach(row -> append(message, row));
        return message.append("Public Warcraft Logs rankings; unavailable difficulties are shown as —.").toString();
    }

    private Row load(TrackedPlayer player) {
        try {
            JsonNode character = client.query(QUERY, Map.of(
                    "name", player.name(), "server", player.realm().toLowerCase(Locale.ROOT),
                    "region", player.region().toUpperCase(Locale.ROOT)))
                    .path("characterData").path("character");
            if (character.isMissingNode() || character.isNull())
                throw new IllegalStateException("character rankings unavailable");
            List<String> difficulties = List.of("mythic", "heroic", "normal");
            for (int priority = 0; priority < difficulties.size(); priority++) {
                BigDecimal parse = parse(character.path(difficulties.get(priority)));
                if (parse != null) return new Row(player, character, priority, parse);
            }
            return new Row(player, character, Integer.MAX_VALUE, null);
        } catch (RuntimeException _) {
            return new Row(player, null, Integer.MAX_VALUE, null);
        }
    }

    private static void append(StringBuilder out, Row row) {
        if (row.character == null) {
            out.append("• ").append(row.player.profileName()).append(" (").append(row.player.name())
                    .append("): unavailable\n\n");
            return;
        }
        out.append("• ").append(row.player.profileName()).append(" (")
                .append(row.character.path("name").asText(row.player.name())).append(")\n")
                .append("  Mythic: ").append(format(row.character.path("mythic"))).append('\n')
                .append("  Heroic: ").append(format(row.character.path("heroic"))).append('\n')
                .append("  Normal: ").append(format(row.character.path("normal"))).append("\n\n");
    }

    private static BigDecimal parse(JsonNode rankings) {
        JsonNode average = rankings.path("bestPerformanceAverage");
        return average.isNumber() ? average.decimalValue() : null;
    }

    private static String format(JsonNode rankings) {
        BigDecimal value = parse(rankings);
        return value == null ? "—" : CombatStatisticsFormatter.percent(value.setScale(2, RoundingMode.HALF_UP));
    }

    private record Row(TrackedPlayer player, JsonNode character, int difficultyPriority, BigDecimal bestParse) {}
}
