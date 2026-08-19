package com.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

class WarcraftLogsRankingParserTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void selectsParseKeyAndDpsFromTheSamePlayerRanking() throws Exception {
        JsonNode rankings = jsonMapper.readTree("""
                {
                  "roles": {
                    "dps": {
                      "characters": [
                        {
                          "id": 42,
                          "name": "Bucothered",
                          "rankPercent": 77,
                          "bracketPercent": 49,
                          "amount": 124013.3
                        }
                      ]
                    }
                  }
                }
                """);

        WarcraftLogsStatisticsService.RankingMetrics result = WarcraftLogsStatisticsService.findRankingMetrics(
                rankings, 42, "Bucothered"
        );

        assertThat(result.parsePercentage()).isEqualByComparingTo("77");
        assertThat(result.keyParsePercentage()).isEqualByComparingTo("49");
        assertThat(result.damagePerSecond()).isEqualByComparingTo("124013.3");
    }

    @Test
    void rejectsAnIncompleteRankingInsteadOfMixingMetrics() throws Exception {
        JsonNode rankings = jsonMapper.readTree("""
                {
                  "characters": [
                    {"id": 42, "name": "Bucothered", "rankPercent": 97.31}
                  ]
                }
                """);

        WarcraftLogsStatisticsService.RankingMetrics result = WarcraftLogsStatisticsService.findRankingMetrics(
                rankings, 42, "Bucothered"
        );

        assertThat(result.parsePercentage()).isNull();
        assertThat(result.keyParsePercentage()).isNull();
        assertThat(result.damagePerSecond()).isNull();
    }

    @Test
    void ignoresAnotherPlayersKeyParse() throws Exception {
        JsonNode rankings = jsonMapper.readTree("""
                {
                  "characters": [
                    {
                      "id": 99,
                      "name": "Other",
                      "rankPercent": 90,
                      "bracketPercent": 88,
                      "amount": 150000
                    }
                  ]
                }
                """);

        assertThat(WarcraftLogsStatisticsService.findRankingMetrics(
                rankings, 42, "Bucothered"
        ).parsePercentage()).isNull();
    }

    @Test
    void requestsDamageRankingsExplicitlyInsteadOfTheMythicPlusDefaultMetric() {
        String query = WarcraftLogsStatisticsService.rankingsQuery(
                new LinkedHashSet<>(java.util.List.of(7, 9))
        );

        assertThat(query)
                .contains("p7: rankings(compare: Rankings, playerMetric: dps, fightIDs: [7])")
                .contains("p9: rankings(compare: Rankings, playerMetric: dps, fightIDs: [9])");
    }
}
