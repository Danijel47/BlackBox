package com.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WarcraftLogsEventParserTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyCompletedEligibleFightsContainingTheActor() throws Exception {
        JsonNode fight = mapper.readTree("""
                {"keystoneTime":1800000,"friendlyPlayers":[42]}
                """);
        assertThat(WarcraftLogsEventParser.isEligibleFight(fight, 3, 13, 42, 13)).isTrue();
        assertThat(WarcraftLogsEventParser.isEligibleFight(fight, 3, 12, 42, 13)).isFalse();
        assertThat(WarcraftLogsEventParser.isEligibleFight(fight, 3, 13, 7, 13)).isFalse();
    }

    @Test
    void calculatesAbsoluteFightWindowFromReportOffsets() throws Exception {
        JsonNode fight = mapper.readTree("""
                {"startTime":300000,"endTime":2100000}
                """);
        WarcraftLogsEventParser.FightWindow window = WarcraftLogsEventParser.fightWindow(
                fight, Instant.parse("2026-08-19T10:00:00Z"));
        assertThat(window.startedAt()).isEqualTo("2026-08-19T10:05:00Z");
        assertThat(window.endedAt()).isEqualTo("2026-08-19T10:35:00Z");
        assertThat(window.durationMs()).isEqualTo(1_800_000);
    }

    @Test
    void matchesFriendlyItemLevelAndCountsActorEvents() throws Exception {
        JsonNode fight = mapper.readTree("""
                {"friendlyPlayers":[7,2,9],"friendlyItemLevels":[305,301,298]}
                """);
        List<JsonNode> events = List.of(
                mapper.readTree("{" + "\"sourceID\":2}"),
                mapper.readTree("{" + "\"sourceID\":7}"),
                mapper.readTree("{" + "\"sourceID\":2}")
        );
        assertThat(WarcraftLogsEventParser.findFriendlyItemLevel(fight, 2)).isEqualByComparingTo("301");
        assertThat(WarcraftLogsEventParser.countEventsForActor(events, "sourceID", 2)).isEqualTo(2);
    }

    @Test
    void appliesExplicitAndCatalogueAvoidableDamageClassification() throws Exception {
        JsonNode events = mapper.readTree("""
                [
                  {"targetID":42,"abilityGameID":373614,"amount":120000},
                  {"targetID":42,"abilityGameID":372735,"amount":900000},
                  {"targetID":42,"isAvoidable":true,"amount":30000},
                  {"targetID":7,"isAvoidable":true,"amount":800000}
                ]
                """);
        assertThat(WarcraftLogsEventParser.sumAvoidableDamageForActor(
                events, 42, "Ruby Life Pools")).isEqualByComparingTo("150000");
    }
}
