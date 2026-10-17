package com.blackbox.valorant;

import com.blackbox.valorant.client.HenrikDevClient;
import com.blackbox.valorant.properties.ValorantProperties;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HenrikDevClientTest {
    private MockRestServiceServer server;
    private HenrikDevClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.henrikdev.xyz");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HenrikDevClient(builder.build(), JsonMapper.builder().build(),
                new ValorantProperties("test-key", java.time.Duration.ofHours(1)));
    }

    @Test
    void requestsCompetitivePagesAndWeightsHitsAcrossTwentyMatches() {
        var first = new ArrayList<String>();
        var second = new ArrayList<String>();
        for (int index = 0; index < 10; index++) {
            first.add(match("first-" + index, index, "competitive", "player", 50, 50, 0));
            second.add(match("second-" + index, index + 10, "competitive", "player", 0, 800, 100));
        }
        expectPage(0, first);
        expectPage(10, second);

        var result = client.headshots("eu", "pc", "player");

        assertThat(result.matches()).isEqualTo(20);
        assertThat(result.headHits()).isEqualTo(500);
        assertThat(result.totalHits()).isEqualTo(10000);
        assertThat(result.percentage()).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("5.0"));
        // Repeated reports must not consume another provider request during the cache window.
        assertThat(client.headshots("eu", "pc", "player")).isEqualTo(result);
        server.verify();
    }

    @Test
    void excludesOtherQueuesDeduplicatesMatchesAndUsesOnlyTheRequestedPlayer() {
        expectPage(0, java.util.List.of(
                match("one", 1, "competitive", "player", 10, 20, 10),
                match("one", 1, "competitive", "player", 10, 20, 10),
                match("casual", 2, "unrated", "player", 900, 0, 0)));

        var result = client.headshots("eu", "pc", "player");

        assertThat(result.matches()).isEqualTo(1);
        assertThat(result.percentage()).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("25.0"));
        server.verify();
    }

    @Test
    void zeroHitsAreUnavailableRatherThanZeroPercent() {
        expectPage(0, java.util.List.of(match("one", 0, "competitive", "player", 0, 0, 0)));

        var result = client.headshots("eu", "pc", "player");

        assertThat(result.matches()).isEqualTo(1);
        assertThat(result.percentage()).isEmpty();
    }

    @Test
    void aggregatesCombatStatsAndWeightsDamageAndScoreByRounds() {
        String win = match("win", 0, "competitive", "player", 10, 30, 0)
                .replace("\"score\":4000", "\"score\":2000")
                .replace("\"dealt\":3000", "\"dealt\":1000");
        String loss = match("loss", 1, "competitive", "player", 40, 60, 0)
                .replace("\"deaths\":15", "\"deaths\":5")
                .replace("\"score\":4000", "\"score\":8000")
                .replace("\"dealt\":3000", "\"dealt\":6000");
        expectPage(0, java.util.List.of(withTeams(win, 13, 7, true, false),
                withTeams(loss, 11, 13, false, true)));

        var result = client.headshots("eu", "pc", "player");
        var stats = result.combatStats();

        assertThat(result.matches()).isEqualTo(2);
        assertThat(stats.kills()).isEqualTo(40);
        assertThat(stats.deaths()).isEqualTo(20);
        assertThat(stats.assists()).isEqualTo(10);
        assertThat(stats.damageDealt()).isEqualTo(7000);
        assertThat(stats.damageReceived()).isEqualTo(5000);
        assertThat(stats.rounds()).isEqualTo(44);
        assertThat(stats.wins()).isEqualTo(1);
        assertThat(stats.losses()).isEqualTo(1);
        assertThat(stats.draws()).isZero();
        assertThat(stats.killDeathRatio()).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("2.00"));
        assertThat(stats.averageDamage()).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("159.1"));
        assertThat(stats.averageCombatScore()).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("227.3"));
        assertThat(stats.damageDelta()).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("45.5"));
        server.verify();
    }

    @Test
    void countsDrawsSeparatelyAndKeepsUnknownCombatStatsUnavailable() {
        expectPage(0, java.util.List.of(withTeams(match("draw", 0, "competitive", "player", 1, 3, 0),
                12, 12, false, false)));

        var stats = client.headshots("eu", "pc", "player").combatStats();

        assertThat(stats.draws()).isEqualTo(1);
        assertThat(stats.losses()).isZero();
        assertThat(stats.wins()).isZero();
    }

    @Test
    void missingCombatFieldsNeverBecomeZeroAndDoNotLoseValidHeadshotData() {
        expectPage(0, java.util.List.of(withTeams(match("win", 0, "competitive", "player", 10, 30, 0),
                13, 7, true, false).replace("\"kills\":20,", "")));

        var result = client.headshots("eu", "pc", "player");

        assertThat(result.combatStats()).isNull();
        assertThat(result.percentage()).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("25.0"));
    }

    @Test
    void zeroDeathsOrRoundsHaveNoRatioInsteadOfInventingZero() {
        var stats = new HenrikDevClient.CombatStats(10, 0, 3, 1000, 500, 2000, 0, 1, 0, 0);

        assertThat(stats.killDeathRatio()).isEmpty();
        assertThat(stats.averageDamage()).isEmpty();
        assertThat(stats.averageCombatScore()).isEmpty();
    }

    @Test
    void selectsTheNewestTwentyByTimestampAndExcludesIncompleteMatches() {
        var matches = new ArrayList<String>();
        for (int index = 24; index >= 0; index--) {
            matches.add(match("match-" + index, index, "competitive", "player", index < 20 ? 0 : 100, 100, 0));
        }
        matches.add(match("unfinished", 0, "competitive", "player", 900, 0, 0)
                .replace("\"is_completed\":true", "\"is_completed\":false"));
        expectPage(0, matches);

        var result = client.headshots("eu", "pc", "player");

        assertThat(result.matches()).isEqualTo(20);
        assertThat(result.headHits()).isZero();
        assertThat(result.percentage()).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("0.0"));
    }

    @Test
    void rejectsNegativeHitCounts() {
        expectPage(0, java.util.List.of(match("one", 0, "competitive", "player", -1, 2, 3)));

        assertThatThrownBy(() -> client.headshots("eu", "pc", "player"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hit counts");
    }

    @Test
    void missingShotCountsDoNotSilentlyBecomeZero() {
        expectPage(0, java.util.List.of(match("one", 0, "competitive", "player", 1, 2, 3)
                .replace("\"headshots\":1,", "")));

        assertThatThrownBy(() -> client.headshots("eu", "pc", "player"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hit counts");
    }

    @Test
    void doesNotSubstituteAnotherPlayersStatsWhenTheTrackedPlayerIsMissing() {
        expectPage(0, java.util.List.of(match("one", 0, "competitive", "someone-else", 1, 2, 3)));

        assertThatThrownBy(() -> client.headshots("eu", "pc", "player"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("player");
    }

    @Test
    void resolvesRiotIdsWithSpacesAndPersistsStableIdentityInformation() {
        server.expect(requestTo("https://api.henrikdev.xyz/valorant/v2/account/Alex%20Player/EU1"))
                .andExpect(header("Authorization", "test-key"))
                .andRespond(withSuccess("""
                        {"status":200,"data":{"puuid":"stable-id","name":"Alex Player","tag":"EU1",
                        "region":"eu","platforms":["pc"],"account_level":42,"card":"card-id",
                        "title":"title-id","updated_at":"2026-10-13T10:00:00Z"}}
                        """, MediaType.APPLICATION_JSON));

        var account = client.account("Alex Player", "EU1");

        assertThat(account.puuid()).isEqualTo("stable-id");
        assertThat(account.region()).isEqualTo("eu");
        assertThat(account.riotId()).isEqualTo("Alex Player#EU1");
        server.verify();
    }

    @Test
    void rateLimitErrorsAreSanitizedAndDoNotGetCachedAsResults() {
        server.expect(requestTo(pageUrl(0))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("secret provider diagnostic test-key"));

        assertThatThrownBy(() -> client.headshots("eu", "pc", "player"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate limit").hasMessageNotContaining("test-key");
        assertThatThrownBy(() -> client.headshots("eu", "pc", "another-player"))
                .hasMessageContaining("rate limit");
        server.verify();
    }

    @Test
    void forbiddenAccessExplainsPermissionsWithoutShowingProviderDiagnostics() {
        server.expect(requestTo(pageUrl(0))).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("private provider diagnostic test-key"));

        assertThatThrownBy(() -> client.headshots("eu", "pc", "player"))
                .isInstanceOf(HenrikDevClient.UnavailableException.class)
                .hasMessageContaining("403", "permissions")
                .hasMessageNotContaining("test-key").hasMessageNotContaining("private provider");
    }

    @Test
    void unauthorizedAccessExplainsThatTheKeyWasRejected() {
        server.expect(requestTo(pageUrl(0))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.headshots("eu", "pc", "player"))
                .isInstanceOf(HenrikDevClient.UnavailableException.class)
                .hasMessageContaining("401", "rejected", "HENRIKDEV_API_KEY");
    }

    @Test
    void missingApiKeyDoesNotMakeAnUnauthenticatedRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.henrikdev.xyz");
        var unauthenticatedServer = MockRestServiceServer.bindTo(builder).build();
        var unconfigured = new HenrikDevClient(builder.build(), JsonMapper.builder().build(),
                new ValorantProperties("", java.time.Duration.ofHours(1)));

        assertThatThrownBy(() -> unconfigured.headshots("eu", "pc", "player"))
                .hasMessageContaining("HENRIKDEV_API_KEY");
        unauthenticatedServer.verify();
    }

    @Test
    void successfulResponseHeadersPauseRequestsWhenTheRemainingBudgetIsLow() {
        String body = "{\"status\":200,\"data\":[" + match("one", 0, "competitive", "player", 1, 3, 0) + "]}";
        server.expect(requestTo(pageUrl(0))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON)
                .header("X-RateLimit-Remaining", "1").header("X-RateLimit-Reset", "120"));

        assertThat(client.headshots("eu", "pc", "player").matches()).isEqualTo(1);
        assertThatThrownBy(() -> client.headshots("eu", "pc", "another-player"))
                .hasMessageContaining("rate limit");
        server.verify();
    }

    private void expectPage(int start, java.util.List<String> matches) {
        server.expect(requestTo(pageUrl(start))).andExpect(header("Authorization", "test-key"))
                .andRespond(withSuccess("{\"status\":200,\"data\":[" + String.join(",", matches) + "]}",
                        MediaType.APPLICATION_JSON));
    }

    private static String pageUrl(int start) {
        return "https://api.henrikdev.xyz/valorant/v4/by-puuid/matches/eu/pc/player"
                + "?mode=competitive&size=10&start=" + start;
    }

    private static String withTeams(String match, int won, int lost, boolean winner, boolean opponentWinner) {
        return match.strip().replaceFirst("\\}$", "") + """
                ,"teams":[{"team_id":"Blue","rounds":{"won":%d,"lost":%d},"won":%s},
                {"team_id":"Red","rounds":{"won":%d,"lost":%d},"won":%s}]}
                """.formatted(won, lost, winner, lost, won, opponentWinner);
    }

    private static String match(String id, int daysAgo, String queue, String puuid, int head, int body, int leg) {
        return """
                {"metadata":{"match_id":"%s","started_at":"%s","is_completed":true,
                  "queue":{"id":"%s","name":"%s"},"map":{"id":"map","name":"Ascent"},
                  "game_version":"version","game_length_in_ms":2000000,"season":{"id":"act","short":"act"},
                  "platform":"pc","region":"eu","party_rr_penaltys":[]},
                 "players":[{"puuid":"%s","name":"Player","tag":"EU1","team_id":"Blue",
                  "stats":{"headshots":%d,"bodyshots":%d,"legshots":%d,"kills":20,"deaths":15,
                  "assists":5,"score":4000,"damage":{"dealt":3000,"received":2500}}},
                  {"puuid":"opponent","stats":{"headshots":999,"bodyshots":0,"legshots":0}}]}
                """.formatted(id, Instant.parse("2026-10-13T12:00:00Z").minusSeconds(daysAgo * 86400L),
                queue, queue, puuid, head, body, leg);
    }
}
