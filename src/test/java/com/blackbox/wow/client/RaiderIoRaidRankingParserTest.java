package com.blackbox.wow.client;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RaiderIoRaidRankingParserTest {

    @Test
    void parsesARealisticRaidRankingResponse() throws Exception {
        var response = JsonMapper.builder().build().readTree("""
                {
                  "raidRankings": [{
                    "rank": 1,
                    "guild": {
                      "name": "Liquid",
                      "displayName": "Liquid",
                      "realm": {"name": "Illidan"},
                      "region": {"short_name": "US"},
                      "path": "/guilds/us/illidan/Liquid"
                    },
                    "encountersDefeated": [{
                      "slug": "nekzali-the-soulcoiler",
                      "firstDefeated": "2026-08-19T15:00:00Z"
                    }]
                  }]
                }
                """);

        var rankings = RaiderIoClient.parseRaidRankings(response);

        assertThat(rankings).singleElement().satisfies(ranking -> {
            assertThat(ranking.rank()).isEqualTo(1);
            assertThat(ranking.guildName()).isEqualTo("Liquid");
            assertThat(ranking.realm()).isEqualTo("Illidan");
            assertThat(ranking.region()).isEqualTo("US");
            assertThat(ranking.defeatedBosses()).singleElement().satisfies(defeat -> {
                assertThat(defeat.slug()).isEqualTo("nekzali-the-soulcoiler");
                assertThat(defeat.firstDefeatedAt()).isEqualTo(Instant.parse("2026-08-19T15:00:00Z"));
            });
        });
    }
}
