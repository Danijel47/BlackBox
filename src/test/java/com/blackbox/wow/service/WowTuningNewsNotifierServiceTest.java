package com.blackbox.wow.service;

import com.blackbox.wow.client.WowheadNewsFeedClient;
import com.blackbox.wow.client.WowheadNewsFeedClient.NewsItem;
import com.blackbox.wow.entity.WowTuningNewsItemEntity;
import com.blackbox.wow.properties.WowTuningNewsProperties;
import com.blackbox.wow.repository.WowTuningNewsItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WowTuningNewsNotifierServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T22:00:00Z");

    @Mock
    private WowheadNewsFeedClient feedClient;

    @Mock
    private WowTuningNewsItemRepository repository;

    @Mock
    private BlackBoxBotNotifier notifier;

    @Test
    void acceptsSupportedLiveTuningTitles() {
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Season 2 Class Tuning Incoming", "Live"))).isTrue();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item(
                "Even More Class Tuning Added - Season 2 Class Tuning Incoming with Weekly Reset",
                "Live"
        ))).isTrue();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Upcoming PvP Tuning Changes", "Live"))).isTrue();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Tank, Healer and DPS Tuning", "Live"))).isTrue();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item(
                "Buffs to Algeth'ar Academy and Windrunner Spire for Mythic+ Week - Upcoming Dungeon Tuning Incoming",
                "Live"
        ))).isTrue();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item(
                "Al'gethar Academy Timer Increased Again - Upcoming Mythic+ Dungeon Tuning",
                "Live"
        ))).isTrue();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item(
                "Mythic+ Dungeon Tuning with Season 2 Launch",
                "Live"
        ))).isTrue();
    }

    @Test
    void acceptsLiveRaidAndTrinketTuning() {
        for (String title : List.of(
                "Raid Encounter Tuning",
                "Massive Nerfs to Coiled Altar and Ula'tek - Post Race to World First Raid Tuning",
                "Final Round of Trinket Tuning for Midnight Season 2 for Next Weekly Reset",
                "Trinkets Tuning"
        )) {
            assertThat(WowTuningNewsNotifierService.isTuningUpdate(item(title, "Live")))
                    .as(title).isTrue();
        }
    }

    @Test
    void acceptsLiveHotfixHeadlinesWithoutTuningSubjects() {
        for (String title : List.of(
                "Spark of Tides Fix - Patch 12.1 Hotfixes for September 15th",
                "Curse Surges Hotfixed and Now Spawn Every 30 Minutes",
                "Minimap Addon Tech Will Be Disabled in Hotfix",
                "Trinket Tuning Hotfixes"
        )) {
            assertThat(WowTuningNewsNotifierService.isTuningUpdate(item(title, "Live")))
                    .as(title).isTrue();
        }
    }

    @Test
    void rejectsPtrAndUnrelatedTuningPosts() {
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("PTR Class Tuning", "PTR"))).isFalse();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Mythic+ Dungeon Tuning", "Beta"))).isFalse();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Raid Tuning Hotfixes", "PTR"))).isFalse();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Trinket Tuning", "Beta"))).isFalse();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Patch Hotfixes", "Classic"))).isFalse();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Profession Tuning", "Live"))).isFalse();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Weekly Maintenance", "Live"))).isFalse();
    }

    @Test
    void sendsAndPersistsARecentUnseenUpdate() throws Exception {
        NewsItem update = item("Season 2 Class Tuning Incoming", "Live", NOW.minus(Duration.ofHours(2)));
        when(feedClient.fetch()).thenReturn(List.of(update));
        when(repository.count()).thenReturn(0L);
        when(repository.findById(update.guid())).thenReturn(Optional.empty());
        when(notifier.send(123L, expectedMessage(update))).thenReturn(true);

        service().checkForTuningUpdates();

        verify(notifier).send(123L, expectedMessage(update));
        verify(repository).save(any(WowTuningNewsItemEntity.class));
    }

    @Test
    void recordsOldFeedItemsWithoutSendingThemDuringInitialization() throws Exception {
        NewsItem oldUpdate = item("Old Class Tuning", "Live", NOW.minus(Duration.ofDays(7)));
        when(feedClient.fetch()).thenReturn(List.of(oldUpdate));
        when(repository.count()).thenReturn(0L);
        when(repository.findById(oldUpdate.guid())).thenReturn(Optional.empty());

        service().checkForTuningUpdates();

        verify(notifier, never()).send(any(Long.class), any(String.class));
        verify(repository).save(any(WowTuningNewsItemEntity.class));
    }

    @Test
    void retriesLaterWhenTelegramSendingFails() throws Exception {
        NewsItem update = item("Season 2 Class Tuning Incoming", "Live", NOW.minus(Duration.ofHours(2)));
        when(feedClient.fetch()).thenReturn(List.of(update));
        when(repository.count()).thenReturn(1L);
        when(repository.findById(update.guid())).thenReturn(Optional.empty());
        when(notifier.send(123L, expectedMessage(update))).thenReturn(false);

        service().checkForTuningUpdates();

        verify(repository, never()).save(any(WowTuningNewsItemEntity.class));
    }

    @Test
    void sendsAnArticleAgainWhenWowheadPublishesAChangedHeadline() throws Exception {
        String guid = "https://www.wowhead.com/news=382466";
        Instant originalPublication = NOW.minus(Duration.ofDays(3));
        NewsItem updatedArticle = new NewsItem(
                guid,
                "Even More Class Tuning Added - Season 2 Class Tuning Incoming with Weekly Reset",
                URI.create("https://www.wowhead.com/news=382466/season-2-class-tuning-incoming-with-weekly-reset-blood-dk-nerf"),
                "Live",
                NOW.minus(Duration.ofHours(2))
        );
        WowTuningNewsItemEntity storedArticle = new WowTuningNewsItemEntity(
                guid,
                "Season 2 Class Tuning Incoming with Weekly Reset",
                "https://www.wowhead.com/news=382466/season-2-class-tuning-incoming-with-weekly-reset-blood-dk-nerf",
                originalPublication,
                true,
                originalPublication
        );
        when(feedClient.fetch()).thenReturn(List.of(updatedArticle));
        when(repository.count()).thenReturn(1L);
        when(repository.findById(guid)).thenReturn(Optional.of(storedArticle));
        when(notifier.send(123L, expectedMessage(updatedArticle))).thenReturn(true);

        service().checkForTuningUpdates();

        verify(notifier).send(123L, expectedMessage(updatedArticle));
        verify(repository).save(storedArticle);
    }

    private WowTuningNewsNotifierService service() {
        return new WowTuningNewsNotifierService(
                feedClient,
                repository,
                notifier,
                new WowTuningNewsProperties(
                        true,
                        123L,
                        URI.create("https://www.wowhead.com/news/rss/all"),
                        Duration.ofHours(48),
                        3
                ),
                Clock.fixed(NOW, ZoneOffset.UTC),
                ZoneId.of("Europe/Zagreb")
        );
    }

    private static String expectedMessage(NewsItem item) {
        return "⚖️ WoW tuning update\n\n"
                + item.title() + "\n"
                + "Published: 15 Aug 2026, 22:00 CEST\n"
                + item.link() + "\n\n"
                + "Source: Wowhead";
    }

    private static NewsItem item(String title, String category) {
        return item(title, category, Instant.parse("2026-08-15T00:21:34Z"));
    }

    private static NewsItem item(String title, String category, Instant publishedAt) {
        return new NewsItem(
                "guid-" + title,
                title,
                URI.create("https://www.wowhead.com/news=1/example"),
                category,
                publishedAt
        );
    }
}
