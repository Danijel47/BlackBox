package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardAuctionService.PriceResult;
import com.blackbox.wow.blizzard.BlizzardAuctionService.WowTokenPriceSnapshot;
import com.blackbox.wow.entity.WowTokenPriceSnapshotEntity;
import com.blackbox.wow.properties.WowTokenHistoryProperties;
import com.blackbox.wow.repository.WowTokenPriceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WowTokenPriceHistoryServiceTest {

    private static final Instant SOURCE_UPDATED_AT = Instant.parse("2026-08-21T08:45:00Z");

    @Mock private BlizzardAuctionService auctionService;
    @Mock private WowTokenPriceSnapshotRepository repository;

    @Test
    void savesOnePriceObservationForTheUtcHour() {
        when(auctionService.getWowTokenPriceSnapshot()).thenReturn(new WowTokenPriceSnapshot(
                new PriceResult(true, 3_456_789_000L, 345_678, 90, 0),
                SOURCE_UPDATED_AT
        ));

        service(true).capturePriceAt(Instant.parse("2026-08-21T09:37:12Z"));

        ArgumentCaptor<WowTokenPriceSnapshotEntity> entityCaptor =
                ArgumentCaptor.forClass(WowTokenPriceSnapshotEntity.class);
        verify(repository).saveAndFlush(entityCaptor.capture());
        WowTokenPriceSnapshotEntity entity = entityCaptor.getValue();
        assertThat(entity.getRegion()).isEqualTo("EU");
        assertThat(entity.getPriceCopper()).isEqualTo(3_456_789_000L);
        assertThat(entity.getSourceUpdatedAt()).isEqualTo(SOURCE_UPDATED_AT);
        assertThat(entity.getCapturedAt()).isEqualTo(Instant.parse("2026-08-21T09:00:00Z"));
    }

    @Test
    void skipsAnHourThatWasAlreadyCaptured() {
        Instant observedAt = Instant.parse("2026-08-21T09:37:12Z");
        when(repository.existsByRegionAndCapturedAt("EU", Instant.parse("2026-08-21T09:00:00Z")))
                .thenReturn(true);

        service(true).capturePriceAt(observedAt);

        verify(auctionService, never()).getWowTokenPriceSnapshot();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void doesNotSaveAnUnavailablePrice() {
        when(auctionService.getWowTokenPriceSnapshot()).thenReturn(new WowTokenPriceSnapshot(
                new PriceResult(false, 0, 0, 0, 0),
                null
        ));

        service(true).capturePriceAt(Instant.parse("2026-08-21T09:37:12Z"));

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void doesNothingWhenHistoryCollectionIsDisabled() {
        service(false).capturePriceAt(Instant.parse("2026-08-21T09:37:12Z"));

        verify(repository, never()).existsByRegionAndCapturedAt(any(), any());
        verify(auctionService, never()).getWowTokenPriceSnapshot();
    }

    @Test
    void returnsTheLowestPriceAndPrefersTheBlizzardTimestamp() {
        Instant capturedAt = Instant.parse("2026-08-21T09:00:00Z");
        WowTokenPriceSnapshotEntity snapshot = new WowTokenPriceSnapshotEntity(
                "EU",
                3_456_789_000L,
                SOURCE_UPDATED_AT,
                capturedAt
        );
        when(repository.findFirstByRegionAndCapturedAtGreaterThanEqualOrderByPriceCopperAscCapturedAtAsc(
                "EU",
                capturedAt
        )).thenReturn(Optional.of(snapshot));

        var lowestPrice = service(true).lowestPriceSince(capturedAt);

        assertThat(lowestPrice).hasValueSatisfying(price -> {
            assertThat(price.priceCopper()).isEqualTo(3_456_789_000L);
            assertThat(price.priceAt()).isEqualTo(SOURCE_UPDATED_AT);
        });
    }

    @Test
    void returnsTheHighestPrice() {
        Instant capturedAt = Instant.parse("2026-08-21T09:00:00Z");
        WowTokenPriceSnapshotEntity snapshot = new WowTokenPriceSnapshotEntity(
                "EU",
                4_000_000_000L,
                null,
                capturedAt
        );
        when(repository.findFirstByRegionAndCapturedAtGreaterThanEqualOrderByPriceCopperDescCapturedAtAsc(
                "EU",
                capturedAt
        )).thenReturn(Optional.of(snapshot));

        var highestPrice = service(true).highestPriceSince(capturedAt);

        assertThat(highestPrice).hasValueSatisfying(price -> {
            assertThat(price.priceCopper()).isEqualTo(4_000_000_000L);
            assertThat(price.priceAt()).isEqualTo(capturedAt);
        });
    }

    @Test
    void determinesBuyAndSellHoursFromThirtyDayHourlyAverages() {
        Instant cutoff = Instant.parse("2026-08-01T00:00:00Z");
        List<WowTokenPriceSnapshotEntity> snapshots = List.of(
                snapshot(1_000_000L, "2026-08-01T02:00:00Z"),
                snapshot(1_100_000L, "2026-08-02T02:00:00Z"),
                snapshot(1_200_000L, "2026-08-03T02:00:00Z"),
                snapshot(2_000_000L, "2026-08-01T18:00:00Z"),
                snapshot(2_100_000L, "2026-08-02T18:00:00Z"),
                snapshot(2_200_000L, "2026-08-03T18:00:00Z")
        );
        when(repository.findByRegionAndCapturedAtGreaterThanEqualOrderByCapturedAtAsc("EU", cutoff))
                .thenReturn(snapshots);

        var tradingHours = service(true).bestTradingHoursSince(cutoff, ZoneId.of("Europe/Zagreb"));

        assertThat(tradingHours).hasValueSatisfying(hours -> {
            assertThat(hours.buy().hour()).isEqualTo(4);
            assertThat(hours.buy().averageCopper()).isEqualTo(1_100_000L);
            assertThat(hours.buy().sampleCount()).isEqualTo(3);
            assertThat(hours.sell().hour()).isEqualTo(20);
            assertThat(hours.sell().averageCopper()).isEqualTo(2_100_000L);
            assertThat(hours.sell().sampleCount()).isEqualTo(3);
        });
    }

    private static WowTokenPriceSnapshotEntity snapshot(long priceCopper, String capturedAt) {
        return new WowTokenPriceSnapshotEntity(
                "EU",
                priceCopper,
                null,
                Instant.parse(capturedAt)
        );
    }

    private WowTokenPriceHistoryService service(boolean enabled) {
        return new WowTokenPriceHistoryService(
                auctionService,
                repository,
                new WowTokenHistoryProperties(enabled)
        );
    }
}
