package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.client.RaiderIoClient.RaidBossDefeat;
import com.blackbox.wow.client.RaiderIoClient.RaidRanking;
import com.blackbox.wow.entity.RaceToWorldFirstNotificationEntity;
import com.blackbox.wow.properties.RaceToWorldFirstProperties;
import com.blackbox.wow.repository.RaceToWorldFirstNotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RaceToWorldFirstServiceTest {

    private static final String EVENT_KEY = "the-venomous-abyss:mythic:nekzali-the-soulcoiler";

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
    void sendsAndPersistsOnlyTheEarliestFirstBossKill() {
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
        when(notificationRepository.existsById(EVENT_KEY)).thenReturn(false);
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 200))
                .thenReturn(List.of(laterKill, earliestKill));
        when(notifier.send(123L, "🏆 WORLD FIRST — MYTHIC BOSS ONE\n"
                + "Liquid (US) defeated Nek'zali the Soulcoiler.\n"
                + "The Venomous Abyss: 1/8 Mythic\n\n"
                + "Raider.IO: https://raider.io/raid-rankings/the-venomous-abyss/world/mythic"))
                .thenReturn(true);

        service().checkForFirstBossKill();

        verify(notificationRepository).saveAndFlush(any(RaceToWorldFirstNotificationEntity.class));
    }

    @Test
    void stopsPollingAfterTheNotificationWasPersisted() {
        when(notificationRepository.existsById(EVENT_KEY)).thenReturn(true);

        service().checkForFirstBossKill();

        verify(raiderIoClient, never()).getMythicRaidRankings(any(), any(Integer.class));
        verify(notifier, never()).send(any(), contains("WORLD FIRST"));
    }

    @Test
    void doesNotNotifyBeforeTheFirstBossIsKilled() {
        when(notificationRepository.existsById(EVENT_KEY)).thenReturn(false);
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 200)).thenReturn(List.of());

        service().checkForFirstBossKill();

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
        when(notificationRepository.existsById(EVENT_KEY)).thenReturn(false);
        when(raiderIoClient.getMythicRaidRankings("the-venomous-abyss", 200))
                .thenReturn(List.of(firstKill));
        when(notifier.send(any(), contains("WORLD FIRST"))).thenReturn(false);

        service().checkForFirstBossKill();

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
                        "nekzali-the-soulcoiler",
                        "Nek'zali the Soulcoiler"
                )
        );
    }

    private static RaidRanking ranking(int rank, String guild, String region, int defeatedBosses) {
        List<RaidBossDefeat> defeats = java.util.stream.IntStream.range(0, defeatedBosses)
                .mapToObj(index -> new RaidBossDefeat("boss-" + index, Instant.EPOCH.plusSeconds(index)))
                .toList();
        return new RaidRanking(rank, guild, "Realm", region, defeats, "/guilds/path");
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
                "/guilds/path"
        );
    }
}
