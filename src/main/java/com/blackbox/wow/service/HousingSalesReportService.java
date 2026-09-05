package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardDecorService;
import com.blackbox.wow.client.SaddlebagExchangeClient;
import com.blackbox.wow.client.SaddlebagExchangeClient.RegionItem;
import com.blackbox.wow.properties.HousingMarketProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static com.blackbox.wow.service.HousingMarketUnavailableException.DataSource.BLIZZARD_DECOR;
import static com.blackbox.wow.service.HousingMarketUnavailableException.DataSource.SADDLEBAG_TSM;

@Service
public class HousingSalesReportService {

    private static final long COPPER_PER_GOLD = 10_000L;
    private static final String UNAVAILABLE_PRICE = "n/a";
    private static final DateTimeFormatter UPDATED_DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final BlizzardDecorService decorService;
    private final SaddlebagExchangeClient marketClient;
    private final int resultLimit;

    public HousingSalesReportService(
            BlizzardDecorService decorService,
            SaddlebagExchangeClient marketClient,
            HousingMarketProperties properties
    ) {
        this.decorService = decorService;
        this.marketClient = marketClient;
        this.resultLimit = properties.resultLimit();
    }

    public String rankedMessage(HousingRanking ranking) {
        Objects.requireNonNull(ranking, "Housing ranking is required.");
        Set<Long> decorItemIds = loadDecorItemIds();
        List<RegionItem> rankedItems = loadMarketItems(decorItemIds).stream()
                .filter(item -> decorItemIds.contains(item.itemId()))
                .filter(item -> hasRankingData(item, ranking))
                .sorted(comparatorFor(ranking))
                .limit(resultLimit)
                .toList();
        if (rankedItems.isEmpty()) {
            return "No auctionable housing decor with EU sales data is currently available.";
        }

        StringBuilder message = new StringBuilder(ranking.title()).append('\n');
        for (int index = 0; index < rankedItems.size(); index++) {
            appendItem(message, index + 1, rankedItems.get(index));
        }
        Instant updatedAt = rankedItems.stream()
                .map(RegionItem::updatedAt)
                .min(Instant::compareTo)
                .orElseThrow();
        return message.append("\nUpdated ")
                .append(UPDATED_DATE_FORMAT.format(updatedAt))
                .append(" UTC. Saddlebag Exchange/TSM EU estimates; realm sales are not guaranteed.")
                .toString();
    }

    private Set<Long> loadDecorItemIds() {
        try {
            return decorService.getDecorAuctionItemIds();
        } catch (RuntimeException e) {
            throw HousingMarketUnavailableException.from(BLIZZARD_DECOR, e);
        }
    }

    private List<RegionItem> loadMarketItems(Set<Long> decorItemIds) {
        try {
            return marketClient.getEuRetailItems(decorItemIds);
        } catch (RuntimeException e) {
            throw HousingMarketUnavailableException.from(SADDLEBAG_TSM, e);
        }
    }

    private static boolean hasRankingData(RegionItem item, HousingRanking ranking) {
        if (item.soldPerDay() <= 0D && item.saleRate() <= 0D) {
            return false;
        }
        return switch (ranking) {
            case SALES, AVERAGE_PRICE -> item.averageSalePriceCopper() > 0;
            case MARKET_PRICE -> item.marketValueCopper() > 0;
        };
    }

    private static Comparator<RegionItem> comparatorFor(HousingRanking ranking) {
        Comparator<RegionItem> comparator = switch (ranking) {
            case SALES -> Comparator.comparingDouble(RegionItem::soldPerDay)
                    .thenComparingDouble(RegionItem::saleRate);
            case AVERAGE_PRICE -> Comparator.comparingLong(RegionItem::averageSalePriceCopper)
                    .thenComparingDouble(RegionItem::soldPerDay);
            case MARKET_PRICE -> Comparator.comparingLong(RegionItem::marketValueCopper)
                    .thenComparingDouble(RegionItem::soldPerDay);
        };
        return comparator.reversed().thenComparing(RegionItem::name);
    }

    private static void appendItem(StringBuilder message, int rank, RegionItem item) {
        message.append('\n')
                .append(rank)
                .append(". ")
                .append(item.name())
                .append(" [")
                .append(item.itemId())
                .append("]\n   ")
                .append(formatRate(item.soldPerDay()))
                .append("/day | ")
                .append(formatPercent(item.saleRate()))
                .append(" sale | avg ")
                .append(formatGold(item.averageSalePriceCopper()))
                .append(" | market ")
                .append(formatGold(item.marketValueCopper()));
    }

    private static String formatRate(double rate) {
        return String.format(Locale.ROOT, "%.3f", rate);
    }

    private static String formatPercent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100D);
    }

    private static String formatGold(long copper) {
        if (copper <= 0) {
            return UNAVAILABLE_PRICE;
        }
        return String.format(Locale.ROOT, "%,dg", copper / COPPER_PER_GOLD);
    }

    public enum HousingRanking {
        SALES("sale", "Housing decor — top EU sellers"),
        AVERAGE_PRICE("avg", "Housing decor — highest EU average prices"),
        MARKET_PRICE("price", "Housing decor — highest EU market prices");

        private final String key;
        private final String title;

        HousingRanking(String key, String title) {
            this.key = key;
            this.title = title;
        }

        public String key() {
            return key;
        }

        private String title() {
            return title;
        }

        public static HousingRanking fromKey(String key) {
            for (HousingRanking ranking : values()) {
                if (ranking.key.equals(key)) {
                    return ranking;
                }
            }
            return null;
        }
    }
}
