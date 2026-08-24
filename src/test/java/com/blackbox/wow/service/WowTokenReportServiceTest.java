package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenHourAverage;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenPricePoint;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenTradingHours;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WowTokenReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");

    @Mock private BlizzardAuctionService auctionService;
    @Mock private WowTokenPriceHistoryService historyService;

    private WowTokenReportService service;

    @BeforeEach
    void setUp() {
        service = new WowTokenReportService(
                auctionService,
                historyService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void formatsTheCurrentTokenPrice() {
        when(auctionService.getWowTokenPrice()).thenReturn(
                new BlizzardAuctionService.PriceResult(true, 3_456_789_000L, 0, 0, 0)
        );

        assertThat(service.currentPrice()).isEqualTo("WoW Token (EU) avg for WoW Token: 345678g 90s");
    }

    @Test
    void formatsTheLowestHistoricalPriceInZagrebTime() {
        when(historyService.lowestPriceSince(NOW.minus(Duration.ofDays(7)))).thenReturn(Optional.of(
                new TokenPricePoint(3_456_789_000L, Instant.parse("2026-08-21T08:00:00Z"))
        ));

        String report = service.lowestPrice(Duration.ofDays(7), "last week");

        assertThat(report).isEqualTo("""
                Lowest WoW Token price (EU) in the last week: 345678g 90s
                Date: 21 Aug 2026, 10:00 CEST
                """.strip());
    }

    @Test
    void formatsTheBestRecurringTradingHours() {
        when(historyService.bestTradingHoursSince(any(Instant.class), any(ZoneId.class))).thenReturn(Optional.of(
                new TokenTradingHours(
                        new TokenHourAverage(4, 3_200_000_000L, 28),
                        new TokenHourAverage(20, 3_600_000_000L, 29)
                )
        ));

        assertThat(service.bestTradingHours()).isEqualTo("""
                Best recurring WoW Token times (EU, last 30 days; Europe/Zagreb):
                Buy with gold: 04:00–04:59 — avg 320000g 0s (28 daily samples)
                Sell for gold: 20:00–20:59 — avg 360000g 0s (29 daily samples)
                Based on hourly averages; historical patterns do not guarantee future prices.
                """.strip());
    }

    @Test
    void doesNotExposeCurrentPriceFailureDetails() {
        when(auctionService.getWowTokenPrice()).thenThrow(new IllegalStateException("sensitive endpoint"));

        assertThat(service.currentPrice())
                .isEqualTo("Blizzard token lookup failed.")
                .doesNotContain("sensitive endpoint");
    }
}
