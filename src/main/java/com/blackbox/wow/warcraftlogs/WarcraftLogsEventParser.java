package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.helper.MPlusRunMatcher.PlayerIdentity;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

final class WarcraftLogsEventParser {
    private WarcraftLogsEventParser() {}

    static boolean isEligibleFight(JsonNode fight, int fightId, int keyLevel,
                                   int actorId, int minimumKeystoneLevel) {
        return fightId > 0 && keyLevel >= minimumKeystoneLevel
                && fight.path("keystoneTime").asLong(0) > 0
                && containsInt(fight.path("friendlyPlayers"), actorId);
    }

    static FightWindow fightWindow(JsonNode fight, Instant reportStartedAt) {
        long startOffset = fight.path("startTime").asLong(-1);
        long endOffset = fight.path("endTime").asLong(-1);
        if (reportStartedAt == null || startOffset < 0 || endOffset <= startOffset) return null;
        return new FightWindow(reportStartedAt.plusMillis(startOffset),
                reportStartedAt.plusMillis(endOffset), endOffset - startOffset);
    }

    static Integer findActorId(Map<Integer, ReportActor> actors, TrackedPlayer player) {
        String expectedServer = normalize(player.realm());
        for (ReportActor actor : actors.values()) {
            if (!actor.name().equalsIgnoreCase(player.name())) continue;
            String actorServer = normalize(actor.server());
            if (actorServer.isBlank() || actorServer.equals(expectedServer)) return actor.id();
        }
        return null;
    }

    static Map<Integer, ReportActor> reportActors(JsonNode actors) {
        Map<Integer, ReportActor> result = new LinkedHashMap<>();
        for (JsonNode actor : actors) {
            int id = actor.path("id").asInt(0);
            String name = actor.path("name").asText("");
            if (id > 0 && !name.isBlank())
                result.put(id, new ReportActor(id, name, actor.path("server").asText("")));
        }
        return Map.copyOf(result);
    }

    static Set<PlayerIdentity> friendlyRoster(JsonNode friendlyPlayers,
                                               Map<Integer, ReportActor> actors, String region) {
        Set<PlayerIdentity> roster = new LinkedHashSet<>();
        for (JsonNode playerId : friendlyPlayers) {
            ReportActor actor = actors.get(playerId.asInt(0));
            if (actor != null && !actor.server().isBlank())
                roster.add(new PlayerIdentity(region, actor.server(), actor.name()));
        }
        return Set.copyOf(roster);
    }

    static BigDecimal findFriendlyItemLevel(JsonNode fight, int actorId) {
        JsonNode players = fight.path("friendlyPlayers");
        JsonNode levels = fight.path("friendlyItemLevels");
        if (!players.isArray() || !levels.isArray() || players.size() != levels.size()) return null;
        for (int index = 0; index < players.size(); index++) {
            JsonNode level = levels.path(index);
            if (players.path(index).asInt(-1) == actorId && level.isNumber()
                    && level.decimalValue().signum() > 0) return level.decimalValue();
        }
        return null;
    }

    static int countEventsForActor(Iterable<JsonNode> events, String actorField, int actorId) {
        int count = 0;
        for (JsonNode event : events) if (event.path(actorField).asInt(-1) == actorId) count++;
        return count;
    }

    static int countDeathEventsForActor(Iterable<JsonNode> events, int actorId) {
        int count = 0;
        for (JsonNode event : events) {
            if (event.path("targetID").asInt(-1) == actorId
                    || (!event.has("targetID") && event.path("sourceID").asInt(-1) == actorId)) count++;
        }
        return count;
    }

    static BigDecimal sumAvoidableDamageForActor(Iterable<JsonNode> events,
                                                  int actorId, String dungeonName) {
        BigDecimal total = BigDecimal.ZERO;
        Optional<Set<Long>> catalogue = MidnightSeason2AvoidableAbilities.abilitiesFor(dungeonName);
        Set<Long> abilities = catalogue.orElseGet(Set::of);
        boolean classificationAvailable = catalogue.isPresent();
        for (JsonNode event : events) {
            if (event.path("targetID").asInt(-1) != actorId) continue;
            JsonNode explicit = event.path("isAvoidable");
            classificationAvailable |= explicit.isBoolean();
            JsonNode amount = event.path("amount");
            if (isAvoidable(event, abilities, explicit) && amount.isNumber()
                    && amount.decimalValue().signum() >= 0) total = total.add(amount.decimalValue());
        }
        return classificationAvailable ? total : null;
    }

    private static boolean isAvoidable(JsonNode event, Set<Long> abilities, JsonNode explicit) {
        if (explicit.isBoolean()) return explicit.asBoolean();
        JsonNode abilityId = event.path("abilityGameID");
        return abilityId.canConvertToLong() && abilities.contains(abilityId.asLong());
    }

    private static boolean containsInt(JsonNode array, int expected) {
        for (JsonNode value : array) if (value.asInt() == expected) return true;
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    record ReportActor(int id, String name, String server) {}
    record FightWindow(Instant startedAt, Instant endedAt, long durationMs) {}
}
