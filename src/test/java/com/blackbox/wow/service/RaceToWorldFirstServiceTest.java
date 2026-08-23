package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.client.RaiderIoClient.RaidBossDefeat;
import com.blackbox.wow.client.RaiderIoClient.RaidBossProgress;
import com.blackbox.wow.client.RaiderIoClient.RaidEncounter;
import com.blackbox.wow.client.RaiderIoClient.RaidRanking;
import com.blackbox.wow.entity.RaceToWorldFirstNotificationEntity;
import com.blackbox.wow.properties.RaceToWorldFirstProperties;
import com.blackbox.wow.repository.RaceToWorldFirstNotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RaceToWorldFirstServiceTest {

    private static final String FIRST_BOSS_EVENT_KEY =
            "the-venomous-abyss:mythic:nekzali-the-soulcoiler";
    private static final String SECOND_BOSS_EVENT_KEY =
            "the-venomous-abyss:mythic:the-lost-explorers";

    @Mock private RaiderIoClient raiderIoClient;
    @Mock private RaceToWorldFirstNotificationRepository notificationRepository;
    @Mock private BlackBoxBotNotifier notifier;

    @Test
    void formatsTheTopFiveMythicGuilds() {
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 5)).thenReturn(List.of(
                ranking(1, "Liquid", "US", 2),
                ranking(2, "Echo", "EU", 1)
        ));

        String message = service().currentStandingsMessage();

        assertThat(message)
                .contains("Race to World First — The Venomous Abyss (Mythic)")
                .contains("1. Liquid — 2/8 Mythic (US)")
                .contains("2. Echo — 1/8 Mythic (EU)")
                .contains("https://raider.io/raid-rankings/the-venomous-abyss/world/mythic");
    }

    @Test
    void includesBestPullPercentageAndPullCountForTheActiveBoss() {
        RaidRanking ranking = new RaidRanking(
                1,
                "Liquid",
                "Illidan",
                "US",
                List.of(new RaidBossDefeat("boss-one", Instant.EPOCH)),
                List.of(
                        new RaidBossProgress("boss-one", true, 5, BigDecimal.ZERO),
                        new RaidBossProgress("boss-two", false, 37, new BigDecimal("18.42"))
                ),
                "/guilds/path"
        );
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 5)).thenReturn(List.of(ranking));

        String message = service().currentStandingsMessage();

        assertThat(message).contains("1. Liquid — 1/8 Mythic — best pull: 18.42% (37 pulls) (US)");
    }

    @Test
    void sendsAndPersistsTheEarliestWorldFirstForEveryDefeatedBoss() {
        RaidRanking laterKill = rankingWithFirstBossKill(
                1,
                "Echo",
                "EU",
                Instant.parse("2026-08-19T15:02:00Z")
        );
        RaidRanking earliestKill = rankingWithFirstBossKill(
                2,
                "Liquid",
                "US",
                Instant.parse("2026-08-19T15:00:00Z")
        );
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 200))
                .thenReturn(List.of(laterKill, earliestKill));
        when(raiderIoClient.getRaidEncounters(11, "the-venomous-abyss")).thenReturn(List.of(
                new RaidEncounter("nekzali-the-soulcoiler", "Nek'zali the Soulcoiler")
        ));
        when(notifier.send(123L, "🏆 WORLD FIRST — MYTHIC BOSS 1\n"
                + "Liquid (US) defeated Nek'zali the Soulcoiler.\n"
                + "The Venomous Abyss: 1/8 Mythic\n\n"
                + "Raider.IO: https://raider.io/raid-rankings/the-venomous-abyss/world/mythic"))
                .thenReturn(true);

        service().checkForWorldFirstBossKills();

        verify(notificationRepository).saveAndFlush(any(RaceToWorldFirstNotificationEntity.class));
    }

    @Test
    void catchesUpEveryBossThatHasNotAlreadyBeenNotified() {
        RaidRanking ranking = rankingWithBossKills(
                1,
                "xD",
                "EU",
                new RaidBossDefeat("nekzali-the-soulcoiler", Instant.parse("2026-08-22T00:26:55Z")),
                new RaidBossDefeat("the-lost-explorers", Instant.parse("2026-08-22T03:24:12Z"))
        );
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 200)).thenReturn(List.of(ranking));
        when(raiderIoClient.getRaidEncounters(11, "the-venomous-abyss")).thenReturn(List.of(
                new RaidEncounter("nekzali-the-soulcoiler", "Nek'zali the Soulcoiler"),
                new RaidEncounter("the-lost-explorers", "The Lost Explorers")
        ));
        when(notifier.send(eq(123L), anyString())).thenReturn(true);

        service().checkForWorldFirstBossKills();

        verify(notifier).send(eq(123L), contains("Nek'zali the Soulcoiler"));
        verify(notifier).send(eq(123L), contains("The Lost Explorers"));
        verify(notificationRepository, times(2)).saveAndFlush(any(RaceToWorldFirstNotificationEntity.class));
    }

    @Test
    void skipsThePersistedFirstBossAndNotifiesForTheNextBoss() {
        RaidRanking ranking = rankingWithBossKills(
                1,
                "xD",
                "EU",
                new RaidBossDefeat("nekzali-the-soulcoiler", Instant.parse("2026-08-22T00:26:55Z")),
                new RaidBossDefeat("the-lost-explorers", Instant.parse("2026-08-22T03:24:12Z"))
        );
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 200)).thenReturn(List.of(ranking));
        when(notificationRepository.existsById(FIRST_BOSS_EVENT_KEY)).thenReturn(true);
        when(notificationRepository.existsById(SECOND_BOSS_EVENT_KEY)).thenReturn(false);
        when(raiderIoClient.getRaidEncounters(11, "the-venomous-abyss")).thenReturn(List.of(
                new RaidEncounter("nekzali-the-soulcoiler", "Nek'zali the Soulcoiler"),
                new RaidEncounter("the-lost-explorers", "The Lost Explorers")
        ));
        when(notifier.send(123L, "🏆 WORLD FIRST — MYTHIC BOSS 2\n"
                + "xD (EU) defeated The Lost Explorers.\n"
                + "The Venomous Abyss: 2/8 Mythic\n\n"
                + "Raider.IO: https://raider.io/raid-rankings/the-venomous-abyss/world/mythic"))
                .thenReturn(true);

        service().checkForWorldFirstBossKills();

        verify(notifier, never()).send(any(), contains("Nek'zali"));
        verify(notifier).send(any(), contains("The Lost Explorers"));
        verify(notificationRepository).saveAndFlush(any(RaceToWorldFirstNotificationEntity.class));
    }

    @Test
    void doesNotNotifyBeforeTheFirstBossIsKilled() {
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 200)).thenReturn(List.of());

        service().checkForWorldFirstBossKills();

        verify(notifier, never()).send(any(), any());
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void retriesLaterWhenTelegramDeliveryFails() {
        RaidRanking firstKill = rankingWithFirstBossKill(
                1,
                "Liquid",
                "US",
                Instant.parse("2026-08-19T15:00:00Z")
        );
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 200))
                .thenReturn(List.of(firstKill));
        when(raiderIoClient.getRaidEncounters(11, "the-venomous-abyss")).thenReturn(List.of(
                new RaidEncounter("nekzali-the-soulcoiler", "Nek'zali the Soulcoiler")
        ));
        when(notifier.send(any(), contains("WORLD FIRST"))).thenReturn(false);

        service().checkForWorldFirstBossKills();

        verify(notificationRepository, never()).saveAndFlush(any());
    }

    private RaceToWorldFirstService service() {
        return new RaceToWorldFirstService(
                raiderIoClient,
                notificationRepository,
                notifier,
                new RaceToWorldFirstProperties(
                        true,
                        123L,
                        "the-venomous-abyss",
                        "The Venomous Abyss",
                        8,
                        11
                )
        );
    }

    private static RaidRanking ranking(int rank, String guild, String region, int defeatedBosses) {
        List<RaidBossDefeat> defeats = java.util.stream.IntStream.range(0, defeatedBosses)
                .mapToObj(index -> new RaidBossDefeat("boss-" + index, Instant.EPOCH.plusSeconds(index)))
                .toList();
        return new RaidRanking(rank, guild, "Realm", region, defeats, List.of(), "/guilds/path");
    }

    private static RaidRanking rankingWithFirstBossKill(
            int rank,
            String guild,
            String region,
            Instant defeatedAt
    ) {
        return new RaidRanking(
                rank,
                guild,
                "Realm",
                region,
                List.of(new RaidBossDefeat("nekzali-the-soulcoiler", defeatedAt)),
                List.of(),
                "/guilds/path"
        );
    }

    private static RaidRanking rankingWithBossKills(
            int rank,
            String guild,
            String region,
            RaidBossDefeat... defeats
    ) {
        return new RaidRanking(
                rank,
                guild,
                "Realm",
                region,
                List.of(defeats),
                List.of(),
                "/guilds/path"
        );
    }
}
