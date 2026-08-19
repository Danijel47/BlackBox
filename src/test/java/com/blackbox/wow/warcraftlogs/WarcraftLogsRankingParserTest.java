package com.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

class WarcraftLogsRankingParserTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void selectsTheKeystoneBracketPercentInsteadOfTheGlobalRankPercent() throws Exception {
        JsonNode rankings = jsonMapper.readTree("""
                {
                  "roles": {
                    "dps": {
                      "characters": [
                        {
                          "id": 42,
                          "name": "Bucothered",
                          "rankPercent": 97.31,
                          "bracketPercent": 63.27
                        }
                      ]
                    }
                  }
                }
                """);

        BigDecimal result = WarcraftLogsStatisticsService.findKeyParsePercentage(
                rankings, 42, "Bucothered"
        );

        assertThat(result).isEqualByComparingTo("63.27");
    }

    @Test
    void doesNotFallBackToGlobalParseWhenKeyParseIsMissing() throws Exception {
        JsonNode rankings = jsonMapper.readTree("""
                {
                  "characters": [
                    {"id": 42, "name": "Bucothered", "rankPercent": 97.31}
                  ]
                }
                """);

        BigDecimal result = WarcraftLogsStatisticsService.findKeyParsePercentage(
                rankings, 42, "Bucothered"
        );

        assertThat(result).isNull();
    }

    @Test
    void ignoresAnotherPlayersKeyParse() throws Exception {
        JsonNode rankings = jsonMapper.readTree("""
                {
                  "characters": [
                    {"id": 99, "name": "Other", "bracketPercent": 88.0}
                  ]
                }
                """);

        assertThat(WarcraftLogsStatisticsService.findKeyParsePercentage(
                rankings, 42, "Bucothered"
        )).isNull();
    }

    @Test
    void requestsDamageRankingsExplicitlyInsteadOfTheMythicPlusDefaultMetric() {
        String query = WarcraftLogsStatisticsService.rankingsQuery(
                new LinkedHashSet<>(java.util.List.of(7, 9))
        );

        assertThat(query)
                .contains("p7: rankings(compare: Parses, playerMetric: dps, fightIDs: [7])")
                .contains("p9: rankings(compare: Parses, playerMetric: dps, fightIDs: [9])");
    }
}
