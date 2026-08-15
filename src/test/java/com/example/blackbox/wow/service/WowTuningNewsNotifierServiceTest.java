package com.example.blackbox.wow.service;

import com.example.blackbox.wow.client.WowheadNewsFeedClient;
import com.example.blackbox.wow.client.WowheadNewsFeedClient.NewsItem;
import com.example.blackbox.wow.entity.WowTuningNewsItemEntity;
import com.example.blackbox.wow.properties.WowTuningNewsProperties;
import com.example.blackbox.wow.repository.WowTuningNewsItemRepository;
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
    private RioBotNotifier notifier;

    @Test
    void acceptsLiveClassAndPvpTuningTitles() {
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Season 2 Class Tuning Incoming", "Live"))).isTrue();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Upcoming PvP Tuning Changes", "Live"))).isTrue();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Tank, Healer and DPS Tuning", "Live"))).isTrue();
    }

    @Test
    void rejectsPtrAndUnrelatedTuningPosts() {
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("PTR Class Tuning", "PTR"))).isFalse();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Raid Encounter Tuning", "Live"))).isFalse();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Trinket Tuning Hotfixes", "Live"))).isFalse();
        assertThat(WowTuningNewsNotifierService.isTuningUpdate(item("Weekly Maintenance", "Live"))).isFalse();
    }

    @Test
    void sendsAndPersistsARecentUnseenUpdate() throws Exception {
        NewsItem update = item("Season 2 Class Tuning Incoming", "Live", NOW.minus(Duration.ofHours(2)));
        when(feedClient.fetch()).thenReturn(List.of(update));
        when(repository.count()).thenReturn(0L);
        when(repository.existsById(update.guid())).thenReturn(false);
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
        when(repository.existsById(oldUpdate.guid())).thenReturn(false);

        service().checkForTuningUpdates();

        verify(notifier, never()).send(any(Long.class), any(String.class));
        verify(repository).save(any(WowTuningNewsItemEntity.class));
    }

    @Test
    void retriesLaterWhenTelegramSendingFails() throws Exception {
        NewsItem update = item("Season 2 Class Tuning Incoming", "Live", NOW.minus(Duration.ofHours(2)));
        when(feedClient.fetch()).thenReturn(List.of(update));
        when(repository.count()).thenReturn(1L);
        when(repository.existsById(update.guid())).thenReturn(false);
        when(notifier.send(123L, expectedMessage(update))).thenReturn(false);

        service().checkForTuningUpdates();

        verify(repository, never()).save(any(WowTuningNewsItemEntity.class));
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
