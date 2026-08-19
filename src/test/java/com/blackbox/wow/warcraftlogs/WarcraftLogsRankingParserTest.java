package com.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

class WarcraftLogsRankingParserTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void selectsParseAndKeyFromTheSamePlayerRanking() throws Exception {
        JsonNode rankings = jsonMapper.readTree("""
                {
                  "roles": {
                    "dps": {
                      "characters": [
                        {
                          "id": 42,
                          "name": "Bucothered",
                          "rankPercent": 77,
                          "bracketPercent": 49
                        }
                      ]
                    }
                  }
                }
                """);

        WarcraftLogsStatisticsService.RankingPercentiles result =
                WarcraftLogsStatisticsService.findRankingPercentiles(
                rankings, 42, "Bucothered"
        );

        assertThat(result.parsePercentage()).isEqualByComparingTo("77");
        assertThat(result.keyParsePercentage()).isEqualByComparingTo("49");
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

        WarcraftLogsStatisticsService.RankingPercentiles result =
                WarcraftLogsStatisticsService.findRankingPercentiles(
                rankings, 42, "Bucothered"
        );

        assertThat(result.parsePercentage()).isNull();
        assertThat(result.keyParsePercentage()).isNull();
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
                      "bracketPercent": 88
                    }
                  ]
                }
                """);

        assertThat(WarcraftLogsStatisticsService.findRankingPercentiles(
                rankings, 42, "Bucothered"
        ).parsePercentage()).isNull();
    }

    @Test
    void calculatesDpsFromTheDamageDoneTable() throws Exception {
        JsonNode table = jsonMapper.readTree("""
                {
                  "data": {
                    "entries": [
                      {"id": 42, "name": "Linqq", "total": 193709836}
                    ],
                    "totalTime": 1562008.558759,
                    "logVersion": 17,
                    "gameVersion": 1
                  }
                }
                """);

        assertThat(WarcraftLogsStatisticsService.findDamagePerSecond(table, 42, "Linqq"))
                .isEqualByComparingTo("124013.3");
    }

    @Test
    void retainsCompatibilityWithTheUnwrappedDamageTable() throws Exception {
        JsonNode table = jsonMapper.readTree("""
                {
                  "entries": [{"id": 42, "name": "Linqq", "total": 193709836}],
                  "totalTime": 1562008.558759
                }
                """);

        assertThat(WarcraftLogsStatisticsService.findDamagePerSecond(table, 42, "Linqq"))
                .isEqualByComparingTo("124013.3");
    }

    @Test
    void requestsDamageRankingsExplicitlyInsteadOfTheMythicPlusDefaultMetric() {
        String query = WarcraftLogsStatisticsService.rankingsQuery(
                new LinkedHashSet<>(java.util.List.of(7, 9))
        );

        assertThat(query)
                .contains("p7: rankings(compare: Rankings, playerMetric: dps, fightIDs: [7])")
                .contains("d7: table(dataType: DamageDone, viewBy: Source, fightIDs: [7])")
                .contains("p9: rankings(compare: Rankings, playerMetric: dps, fightIDs: [9])")
                .contains("d9: table(dataType: DamageDone, viewBy: Source, fightIDs: [9])");
    }
}
