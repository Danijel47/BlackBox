package com.blackbox.wow.client;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RaiderIoRaidRankingParserTest {

    @Test
    void parsesRaidEncounterNamesAndOrderFromStaticData() throws Exception {
        var response = JsonMapper.builder().build().readTree("""
                {
                  "raids": [{
                    "slug": "the-venomous-abyss",
                    "encounters": [
                      {"slug": "nekzali-the-soulcoiler", "name": "Nek'zali the Soulcoiler"},
                      {"slug": "entombed-sentinels", "name": "Entombed Sentinels"}
                    ]
                  }]
                }
                """);

        var encounters = RaiderIoClient.parseRaidEncounters(response, "the-venomous-abyss");

        assertThat(encounters)
                .extracting(RaiderIoClient.RaidEncounter::slug, RaiderIoClient.RaidEncounter::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("nekzali-the-soulcoiler", "Nek'zali the Soulcoiler"),
                        org.assertj.core.groups.Tuple.tuple("entombed-sentinels", "Entombed Sentinels")
                );
    }

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
                    }],
                    "encountersPulled": [{
                      "slug": "vordraka-the-deepdark",
                      "isDefeated": false,
                      "numPulls": 37,
                      "bestPercent": 18.42
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
            assertThat(ranking.bossProgress()).singleElement().satisfies(progress -> {
                assertThat(progress.slug()).isEqualTo("vordraka-the-deepdark");
                assertThat(progress.defeated()).isFalse();
                assertThat(progress.pullCount()).isEqualTo(37);
                assertThat(progress.bestPercent()).isEqualByComparingTo("18.42");
            });
        });
    }
}
