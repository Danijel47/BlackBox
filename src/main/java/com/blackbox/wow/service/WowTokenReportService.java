package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenHourAverage;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenPricePoint;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenTradingHours;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

@Service
public class WowTokenReportService {

    private static final Duration MONTH_LOOKBACK = Duration.ofDays(30);
    private static final String MONTH_LABEL = "last 30 days";
    private static final String TOKEN_SCOPE = "WoW Token (EU)";
    private static final ZoneId ZAGREB_ZONE = ZoneId.of("Europe/Zagreb");
    private static final DateTimeFormatter HISTORY_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "d MMM uuuu, HH:mm z",
            Locale.ENGLISH
    );

    private final BlizzardAuctionService auctionService;
    private final WowTokenPriceHistoryService historyService;
    private final Clock clock;

    public WowTokenReportService(
            BlizzardAuctionService auctionService,
            WowTokenPriceHistoryService historyService,
            Clock clock
    ) {
        this.auctionService = auctionService;
        this.historyService = historyService;
        this.clock = clock;
    }

    public String currentPrice() {
        try {
            BlizzardAuctionService.PriceResult result = auctionService.getWowTokenPrice();
            if (!result.available()) {
                return "No pricing data for WoW Token (" + TOKEN_SCOPE + ").";
            }
            return TOKEN_SCOPE + " avg for WoW Token: " + formatCopper(result.avgCopper());
        } catch (RuntimeException _) {
            return "Blizzard token lookup failed.";
        }
    }

    public String lowestPrice(Duration lookback, String periodLabel) {
        return extremePrice(lookback, periodLabel, PriceExtreme.LOWEST);
    }

    public String highestPrice(Duration lookback, String periodLabel) {
        return extremePrice(lookback, periodLabel, PriceExtreme.HIGHEST);
    }

    public String bestTradingHours() {
        try {
            Optional<TokenTradingHours> tradingHours = historyService.bestTradingHoursSince(
                    clock.instant().minus(MONTH_LOOKBACK),
                    ZAGREB_ZONE
            );
            if (tradingHours.isEmpty()) {
                return "Not enough WoW Token history yet. Each hour needs at least "
                        + WowTokenPriceHistoryService.MINIMUM_SAMPLES_PER_HOUR + " samples.";
            }
            return formatBestTradingHours(tradingHours.get());
        } catch (RuntimeException _) {
            return "Could not analyze the WoW Token price history.";
        }
    }

    private String extremePrice(Duration lookback, String periodLabel, PriceExtreme extreme) {
        try {
            Instant capturedAt = clock.instant().minus(lookback);
            Optional<TokenPricePoint> price = extreme == PriceExtreme.LOWEST
                    ? historyService.lowestPriceSince(capturedAt)
                    : historyService.highestPriceSince(capturedAt);
            if (price.isEmpty()) {
                return "No saved WoW Token prices for the " + periodLabel + " yet.";
            }
            return formatExtreme(price.get(), periodLabel, extreme);
        } catch (RuntimeException _) {
            return "Could not read the WoW Token price history.";
        }
    }

    private static String formatExtreme(TokenPricePoint price, String periodLabel, PriceExtreme extreme) {
        String priceTime = price.priceAt().atZone(ZAGREB_ZONE).format(HISTORY_TIME_FORMATTER);
        return extreme.displayName + " WoW Token price (EU) in the " + periodLabel + ": "
                + formatCopper(price.priceCopper()) + "\nDate: " + priceTime;
    }

    private static String formatBestTradingHours(TokenTradingHours tradingHours) {
        return "Best recurring WoW Token times (EU, " + MONTH_LABEL + "; Europe/Zagreb):\n"
                + "Buy with gold: " + formatHour(tradingHours.buy()) + "\n"
                + "Sell for gold: " + formatHour(tradingHours.sell())
                + "\nBased on hourly averages; historical patterns do not guarantee future prices.";
    }

    private static String formatHour(TokenHourAverage hour) {
        return "%02d:00–%02d:59 — avg %s (%d daily samples)".formatted(
                hour.hour(),
                hour.hour(),
                formatCopper(hour.averageCopper()),
                hour.sampleCount()
        );
    }

    private static String formatCopper(long copper) {
        long gold = copper / 10_000;
        long silver = copper % 10_000 / 100;
        return gold + "g " + silver + "s";
    }

    private enum PriceExtreme {
        LOWEST("Lowest"),
        HIGHEST("Highest");

        private final String displayName;

        PriceExtreme(String displayName) {
            this.displayName = displayName;
        }
    }
}
